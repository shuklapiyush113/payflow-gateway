---
name: verify
description: Run the full PayFlow build and test suite (unit + Testcontainers integration tests). Use before finishing any code change or commit.
---

# Verify

1. Make sure Docker is up. On macOS with Colima: `colima status || colima start`.
2. Run from the repo root:

   ```bash
   DOCKER_HOST=unix://$HOME/.colima/default/docker.sock \
   TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
   ./mvnw -q verify
   ```

   Without Colima (Docker Desktop, Linux, CI) plain `./mvnw -q verify` is enough.
3. On failure, read the failing test's report in `services/*/target/surefire-reports/` or
   `failsafe-reports/` before changing code. Report the failing test names and the assertion message.
4. If a change touched concurrency (idempotency, capture, outbox relay), run the integration tests
   two more times; a pass once is not enough to call them stable.
