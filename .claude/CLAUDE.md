# Instructions for AI coding assistants

- Read [README.md](../README.md) (status and roadmap) and [docs/architecture.md](../docs/architecture.md)
  (decisions) before changing code. Record new design decisions in architecture.md.
- Java 21, Spring Boot 4.1 (Jackson 3: `tools.jackson.*`), Testcontainers 2.
- Checks before finishing: `./mvnw verify` (needs Docker). With Colima on macOS set
  `DOCKER_HOST=unix://$HOME/.colima/default/docker.sock` and
  `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`.
- Money is always `long` minor units plus currency. Status changes go through `Payment` methods only.
- State change, ledger journal and outbox event must commit in one transaction; never call the bank
  inside a transaction.
- No secrets in git (`deploy/k8s/secret.yaml` and `.env` are ignored).
