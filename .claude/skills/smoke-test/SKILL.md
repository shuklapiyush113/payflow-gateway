---
name: smoke-test
description: Start the local Docker Compose stack and drive the payments API end to end (create, idempotent retry, refund, balances, Kafka events). Use to confirm a change works in the running app, not just in tests.
---

# Smoke test

1. `docker compose up -d --build` from the repo root, then wait until
   `curl -sf localhost:8080/actuator/health` returns `UP`.
2. Onboard a fresh merchant with the admin token from `docker-compose.yml` and keep its key:

   ```bash
   AUTH="Authorization: Bearer $(curl -s localhost:8080/admin/merchants \
     -H 'Authorization: Bearer local-dev-admin-token-0123456789abcdef' -H 'Content-Type: application/json' \
     -d '{"name":"smoke"}' | python3 -c 'import json,sys;print(json.load(sys.stdin)["apiKey"]["key"])')"
   ```

   Then create a captured payment with a fresh idempotency key and keep the `id`:

   ```bash
   KEY=smoke-$(date +%s)
   curl -s -i localhost:8080/v1/payments \
     -H "$AUTH" -H "Idempotency-Key: $KEY" -H 'Content-Type: application/json' \
     -d '{"amountMinor":100000,"currency":"INR","method":"UPI","source":"alice@okbank","capture":true}'
   ```

   Expect `201` and `"status":"CAPTURED"`.
3. Send the identical request again. Expect the same `id` and header `Idempotent-Replayed: true`.
4. Refund part of it:
   `curl -s localhost:8080/v1/payments/<id>/refunds -H "$AUTH" -H "Idempotency-Key: $KEY-r1" -H 'Content-Type: application/json' -d '{"amountMinor":30000}'`.
   Expect `"status":"PARTIALLY_REFUNDED"` and `refundedMinor` 30000.
5. `curl -s localhost:8080/v1/balances -H "$AUTH"`: the balances must sum to zero
   (double entry), and the fee must match `payflow.ledger.fee-basis-points` in `application.yml`.
6. Check events reached Kafka:
   `docker compose exec redpanda rpk topic consume payments.events -n 20 -f '%k %h\n'`.
   All events for the payment share one key and arrive in order.
7. Report what matched and what did not. Leave the stack running unless asked to stop it
   (`docker compose down`).

Failure paths worth checking when relevant: a `source` containing `decline` is declined,
`unavailable` simulates a bank outage, amounts above 50000000 minor units are blocked by risk (`payflow.risk.block-above-minor`).
