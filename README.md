# PayFlow

A payment gateway in Java 21 and Spring Boot 4: idempotent payments API, payment state machine,
double-entry ledger, transactional outbox to Kafka, and (in progress) ML fraud scoring and an
LLM dispute assistant. Built to run on free infrastructure (k3s on an Oracle Always Free ARM VM).

## What works today

| Capability | How |
|---|---|
| Card, UPI and wallet payments | Strategy per method, chosen by a registry (factory) |
| Payment lifecycle | Explicit state machine: `CREATED → AUTHORIZED → CAPTURED → PARTIALLY_REFUNDED → REFUNDED`, plus `PENDING`, `VOIDED`, `DECLINED`, `FAILED` |
| Merchant authentication | API keys (`Authorization: Bearer sk_…`) via a Spring Security filter; only SHA-256 hashes stored; several keys per merchant for rotation; revocation; admin API for onboarding kept off the public ingress |
| Rate limiting | Per-merchant token bucket in Redis (atomic Lua script, Redis server clock); `429` with `Retry-After` and `X-RateLimit-*` headers; fails open if Redis is down |
| Safe retries | `Idempotency-Key` header; atomic claim with `INSERT … ON CONFLICT DO NOTHING`; replays return the stored response; the key is also unique on the payment, so a crash cannot lead to a second charge |
| Lost bank answers | A bank timeout leaves the payment `PENDING` (`202`); a reconciliation job asks the bank and settles it, safe across replicas (`SKIP LOCKED` plus a lease) |
| No lost or double-counted money | Double-entry ledger; every journal balances; entries are append-only |
| Concurrent captures | Optimistic locking (`@Version`); exactly one wins, the rest get `409` |
| Reliable events | Transactional outbox, relayed to Kafka (Redpanda) with `FOR UPDATE SKIP LOCKED`, keyed by payment id for ordering |
| Fraud check hook | `RiskScorer` port (amount-based placeholder until step 3) |
| Errors | RFC 9457 problem details |
| Operations | Actuator health probes, Prometheus metrics, graceful shutdown, virtual threads |

47 tests: domain unit tests plus integration tests against real Postgres (Testcontainers),
including concurrency tests for idempotency, capture and parallel reconcilers, security tests, and a test that 20 parallel requests cannot overspend a 5-token bucket.

## Architecture

```
                 ┌──────────────────────────── payment-service ────────────────────────────┐
 client ──HTTP──►│ api ──► idempotency ──► application (PaymentService)                     │
                 │                           │        │          │            │             │
                 │                      processor   risk      ledger       outbox writer    │
                 │                     (strategy)  (port)  (double-entry)  (same tx)        │
                 │                           │                                  │           │
                 │                     BankConnector                       outbox relay     │
                 └───────────────────────────┼──────────────────────────────────┼──────────┘
                                         mock bank                         Kafka topic
                                                                       payments.events (6 partitions)
```

Design decisions and trade-offs: [docs/architecture.md](docs/architecture.md).

## Run locally

Needs Java 21 and Docker.

```bash
./mvnw verify                      # all tests (starts Postgres with Testcontainers)
docker compose up -d --build       # Postgres, Redis, Redpanda, Redpanda Console, payment-service
```

```bash
# Onboard a merchant with the local admin token from docker-compose.yml; the API key is shown once
curl -s localhost:8080/admin/merchants -H 'Authorization: Bearer local-dev-admin-token-0123456789abcdef' \
  -H 'Content-Type: application/json' -d '{"name":"Demo store"}'
KEY=sk_...   # apiKey.key from the response

# Create and capture a payment
curl -s localhost:8080/v1/payments \
  -H "Authorization: Bearer $KEY" -H 'Idempotency-Key: order-1001' -H 'Content-Type: application/json' \
  -d '{"amountMinor":149900,"currency":"INR","method":"UPI","source":"alice@okbank","capture":true}'

# Send the same request again: same payment, header Idempotent-Replayed: true, no second charge

# Partial refund, then balances
curl -s localhost:8080/v1/payments/<id>/refunds -H "Authorization: Bearer $KEY" \
  -H 'Idempotency-Key: refund-1001-a' -H 'Content-Type: application/json' -d '{"amountMinor":49900}'
curl -s localhost:8080/v1/balances -H "Authorization: Bearer $KEY"
```

Events are visible in Redpanda Console at http://localhost:8081.

Mock bank test sources: any source containing `decline` is declined, `unavailable` simulates a bank
outage, `timeout` is approved by the bank but the answer is lost, and `lost` never reaches the bank
(both return `202` with status `PENDING` until the reconciler settles them). Amounts above
5,00,000.00 are blocked by the risk check.

| Endpoint | Purpose |
|---|---|
| `POST /v1/payments` | Create (authorize, optionally capture) |
| `POST /v1/payments/{id}/capture` | Capture an authorization |
| `POST /v1/payments/{id}/void` | Release an authorization |
| `POST /v1/payments/{id}/refunds` | Full or partial refund |
| `GET /v1/payments/{id}` | Read a payment |
| `GET /v1/balances` | Merchant ledger balances |
| `POST /admin/merchants` | Onboard a merchant; returns its first API key (admin token) |
| `POST /admin/merchants/{id}/api-keys` | Issue another key, for rotation (admin token) |
| `GET /admin/merchants/{id}/api-keys` | List key prefixes and revocation status (admin token) |
| `DELETE /admin/api-keys/{keyId}` | Revoke a key (admin token) |

## Deploy for free

k3s manifests are in [deploy/k8s](deploy/k8s); the Oracle Always Free walkthrough is in
[docs/deploy-free.md](docs/deploy-free.md). CI (GitHub Actions) runs the tests and pushes a
multi-arch (amd64 + arm64) image to GHCR on every push to `main`.

## Roadmap

1. ~~Payments, state machine, idempotency, ledger, outbox, CI, k3s manifests~~
2. Step 2, in progress:
   - ~~Reconciliation for payments stuck in `CREATED` or `PENDING`~~
   - ~~API-key authentication (merchant id comes from the key, not a header)~~
   - ~~Per-merchant rate limiting (Redis token bucket)~~
   - Notification service consuming events with dedupe; signed merchant webhooks with retry and DLQ
   - Settlement batch job
3. Risk service: chain of checks (velocity in Redis, blocklist, ML model); XGBoost trained on synthetic
   data, exported to ONNX and scored in the JVM; shadow model and score logging
4. Dispute assistant: Spring AI, pgvector RAG over policies and transaction history, human approval
5. Observability (Grafana, OpenTelemetry), k6 load test with published numbers, CQRS read model
