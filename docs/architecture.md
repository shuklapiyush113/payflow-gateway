# Architecture and design decisions

Each section states the decision, why, and what it costs. These are the questions an interviewer
is likely to ask about this project.

## Code layout

`payment-service` is split by responsibility, with ports (interfaces) at the edges:

| Package | Role | Pattern |
|---|---|---|
| `domain` | `Payment` aggregate, `Money`, `PaymentStatus` | Rich domain model, state machine, value object |
| `processor` | One class per payment method; `BankConnector` port | Strategy, factory (`ProcessorRegistry`), adapter |
| `risk` | `RiskScorer` port | Port, to become a chain of responsibility |
| `ledger` | Double-entry journals | Append-only log |
| `outbox` | `OutboxWriter`, `OutboxRelay`, `EventPublisher` port | Transactional outbox, adapter (log / Kafka) |
| `idempotency` | Key claim and replay | Idempotent receiver |
| `merchant` | Merchants and API keys | Repository with `JdbcClient` |
| `security` | API-key filter, security chain | Servlet filter, stateless authentication |
| `application` | Use cases, transaction boundaries, `PaymentReconciler` | Application service, scheduled job |
| `api` | Controllers, request/response records, error mapping | DTOs separate from the domain |

The JPA annotations live on the domain classes. A pure hexagonal design would map separate
persistence entities; for a service this size the duplication is not worth it.

## Money

Amounts are `long` minor units with an ISO 4217 currency. Floating point cannot represent 0.1
exactly, so it is never used for money. `Money` rejects mixed-currency arithmetic.

## State machine

Allowed transitions live in `PaymentStatus`, and `Payment` refuses everything else. Rules are in one
place, invalid moves fail loudly (`409`), and the diagram in the enum's Javadoc is the spec.

## Idempotency

Clients retry on timeouts; without protection a retry charges twice.

- The key is scoped per merchant: primary key `(merchant_id, idem_key)`.
- Claiming is one atomic statement, `INSERT … ON CONFLICT DO NOTHING`, so two concurrent requests
  cannot both win. No application-level lock is needed.
- The request body is hashed (SHA-256). Reusing a key with a different body is a client bug → `422`.
- A second request while the first is still running → `409`, and the client retries.
- If the action throws, the key is released so a retry can run. A claim left by a crashed instance is
  taken over after `lock-timeout` (60 s).
- Keys expire after 24 hours (scheduled purge).

The stored response is written after the payment transaction commits, so a crash in between leaves
a completed payment with an `IN_PROGRESS` key, and after the lock timeout a retry runs the create
again. To stop that from charging twice, the payment row also stores the idempotency key, unique per
merchant. The re-run finds the existing payment and returns it; if two instances race, the unique
constraint rejects the second insert. Storing the whole response in the payment transaction would
also work, but it would tie the idempotency module to every use case.

Remaining gap: refunds are not records of their own yet, so the same crash during a refund lets a
retry refund again (still capped at the captured amount). Captures and voids are safe, because a
repeat fails the state machine with `409`. Refunds get their own table, and the key with it, when
settlement needs them.

## Transaction boundaries and the bank call

`create` uses several short transactions instead of one long one:

1. Save the payment as `CREATED`.
2. Run the risk check.
3. Call the bank, **outside any transaction**.
4. In one transaction: record the result, ledger journal (if captured) and outbox events.

Holding a DB transaction open across a network call ties up a connection and row locks for as long
as the bank takes, which exhausts the pool under load. Persisting `CREATED` first means a crash
during the bank call leaves a record for reconciliation instead of a charge with no trace.

A bank timeout is not a failure: the bank may have approved. Such payments go to `PENDING` and are
settled by reconciliation (next section).

## Reconciliation

Two things leave a payment without a recorded bank answer: a timeout (`PENDING`), or a crash between
saving the payment and recording the answer (`CREATED`). `PaymentReconciler` runs every 30 s, finds
such payments older than `stale-after` (2 min), and asks the bank by reference:

| Bank says | Result |
|---|---|
| Approved | `AUTHORIZED`, then `CAPTURED` with its ledger journal if the request asked for capture (`auto_capture` is stored for this) |
| Declined | `DECLINED` |
| No record | `FAILED` (`not_received_by_bank`): nothing was charged |
| Unreachable | Left alone; tried again when the lease runs out; logged as an error after 10 attempts |

- **The payment id is the bank reference.** The bank dedupes on it, and lookups use it. Without a
  reference we chose, a lost answer could never be matched to a bank record.
- **Several replicas:** one `UPDATE … WHERE id IN (SELECT … FOR UPDATE SKIP LOCKED) RETURNING` claims
  a batch and sets `next_reconcile_at` (a lease). The bank lookup then runs outside any transaction,
  and the lease keeps other replicas from asking about the same payment. The final update goes through
  the normal optimistic-locking path, so a reconciler racing the original request cannot record
  twice. A test runs four reconcilers in parallel over ten payments and checks one journal each.
- **API:** a create that ends `PENDING` returns `202` with a `Location`; the client polls it (and will
  get a webhook later in step 2). An idempotent replay returns the stored `PENDING` response, as
  Stripe does; the current state is on the payment.
- **Trade-off:** `stale-after` must be longer than the bank call timeout. Otherwise the job could see
  "no record" for a request still in flight and fail a payment the bank then approves. If that
  happens anyway, the authorization is never captured and lapses at the bank, so the customer is not
  charged. Production would also void it explicitly.
- **Not covered:** the mock bank keeps outcomes in memory, so after a restart it has "no record" of
  older requests. A real bank's lookup API does not have this limit.
- Interview angle: this is the same reasoning as payment-provider "requery" APIs (UPI transaction
  status checks, card network inquiries): never turn an unknown into a failure without asking.

## Concurrency

`Payment` has a `@Version` column. Two concurrent captures both read version N; the first commit
bumps it to N+1 and the second gets an optimistic-lock failure → `409`. The integration test fires
six captures at once and checks that exactly one ledger journal exists.

Optimistic rather than pessimistic locking: conflicts on a single payment are rare, and optimistic
locking holds no lock while the request runs.

## Double-entry ledger

| Event | Debit | Credit |
|---|---|---|
| Capture 1,000 (2% fee) | `PSP_RECEIVABLE` 1,000 | `MERCHANT_PAYABLE` 980, `FEE_REVENUE` 20 |
| Refund 300 | `MERCHANT_PAYABLE` 300 | `PSP_RECEIVABLE` 300 |

Every journal is checked to balance before it is written, entries are never updated, and
corrections are new journals. Balances are derived by summing entries. At scale they would be
snapshotted per account (with the ledger sharded by merchant) instead of summed on every read.

## Transactional outbox

Writing to the database and then to Kafka is a dual write: if the process dies in between, the
event is lost, or it is published for a change that rolled back. Instead the event row is inserted
in the same transaction as the change, and `OutboxRelay` publishes it afterwards.

- **Delivery is at-least-once.** A crash after Kafka accepts an event but before the relay commits
  sends it again. Every event has an `eventId` for consumers to dedupe on.
- **Ordering:** the Kafka key is the payment id, so one payment's events land on one partition in
  order. On a publish failure the relay stops the batch rather than skipping ahead.
- **Several replicas:** `FOR UPDATE SKIP LOCKED` lets each replica lock a different batch, so the
  relay scales out without duplicates or blocking. The k3s deployment runs two replicas.
- **Alternative:** change data capture (Debezium) reading the Postgres WAL removes the polling, but
  adds Kafka Connect, which is too heavy for a free VM.

`EventPublisher` is a port: `LoggingEventPublisher` for local runs, `KafkaEventPublisher` in
production. A Google Pub/Sub adapter would be one more class.

## API keys

Merchants call the API with `Authorization: Bearer sk_…`. `ApiKeyAuthenticationFilter` hashes the key,
looks the hash up in `api_keys`, and puts an `AuthenticatedMerchant` principal in the security context;
controllers take the merchant id from it. Until this step the service trusted an `X-Merchant-Id`
header, so any caller could act as any merchant.

- **Authenticate in the service, not only at a gateway.** A gateway that checks the key and forwards a
  trusted header is common, but then everything rests on nobody reaching the service directly. Checking
  the key in the service holds even if the network is misconfigured, and it saves a gateway hop on a
  small VM. The gateway planned for rate limiting can still reject bad keys early.
- **Only a SHA-256 hash is stored.** A leaked table yields no usable keys. Keys are 32 random bytes,
  so a fast hash is enough; bcrypt exists to slow down guessing of low-entropy passwords, and would add
  around 100 ms to every call. Lookup is by hash on a unique index, so there is no string comparison
  whose timing could leak anything.
- **Rotation without downtime:** a merchant can hold several active keys. Issue a new one, deploy it,
  then revoke the old one. Revocation takes effect on the next request, since there is no cache.
- **Admin API:** `/admin/**` needs a separate admin token (at least 32 characters, from the k8s
  Secret; the service refuses to start with a shorter one), compared in constant time. The ingress only
  routes `/v1`, so the admin API is reachable only through `kubectl port-forward`. The admin token
  cannot call `/v1` and merchant keys cannot call `/admin` (`403`).
- **Cost:** one indexed lookup per request. If it shows up in the load test, a short-lived cache
  (Redis, which step 2 adds for rate limiting) would remove it, at the price of revocation taking up to
  the cache TTL.
- Payments created before this step belong to merchant ids without a `merchants` row, so there is no
  foreign key from `payments` to `merchants` yet.

## Security notes

- Card numbers are never accepted, only vault tokens (`tok_…`). Sources are masked before they are stored.
- Merchant isolation: every query is filtered by merchant id, and a payment id from another merchant returns `404`.
- The merchant id comes only from the API key; an `X-Merchant-Id` header is ignored (see API keys).
- The container runs as a non-root user with a read-only root filesystem.
