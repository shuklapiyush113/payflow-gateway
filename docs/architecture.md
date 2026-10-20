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
| `application` | Use cases and transaction boundaries | Application service |
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

Trade-off: the stored response is written after the payment transaction commits. A crash in between
leaves a completed payment with an `IN_PROGRESS` key; after the lock timeout a retry would run the
create again. Closing that gap fully means storing the response in the same transaction as the
payment, which couples the two modules. The reconciliation job in step 2 covers it.

## Transaction boundaries and the bank call

`create` uses several short transactions instead of one long one:

1. Save the payment as `CREATED`.
2. Run the risk check.
3. Call the bank, **outside any transaction**.
4. In one transaction: record the result, ledger journal (if captured) and outbox events.

Holding a DB transaction open across a network call ties up a connection and row locks for as long
as the bank takes, which exhausts the pool under load. Persisting `CREATED` first means a crash
during the bank call leaves a record for reconciliation instead of a charge with no trace.

A bank timeout is treated as `FAILED` today. In reality the outcome is unknown (the bank may have
approved), so step 2 adds a reconciliation job that queries the bank for payments in that state.

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

## Security notes

- Card numbers are never accepted, only vault tokens (`tok_…`). Sources are masked before they are stored.
- Merchant isolation: every query is filtered by merchant id, and a payment id from another merchant returns `404`.
- `X-Merchant-Id` is trusted for now; in step 2 the gateway sets it from an authenticated API key and strips any client-supplied value.
- The container runs as a non-root user with a read-only root filesystem.
