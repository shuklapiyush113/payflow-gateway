# Instructions for AI coding assistants

Shared by every coding agent (Codex, Cursor, Copilot, Claude Code). Claude-specific files (skills,
permissions) are in [.claude/](.claude/README.md); the skills' steps are plain Markdown any agent can
follow: [verify](.claude/skills/verify/SKILL.md), [smoke-test](.claude/skills/smoke-test/SKILL.md),
[finish-step](.claude/skills/finish-step/SKILL.md).

- Read [README.md](README.md) (status and roadmap) and [docs/architecture.md](docs/architecture.md)
  (decisions) before changing code. Record new design decisions in architecture.md.
- Java 21, Spring Boot 4.1 (Jackson 3: `tools.jackson.*`), Testcontainers 2.
- Checks before finishing: `./mvnw verify` (needs Docker). With Colima on macOS set
  `DOCKER_HOST=unix://$HOME/.colima/default/docker.sock` and
  `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`.
- Money is always `long` minor units plus currency. Status changes go through `Payment` methods only.
- State change, ledger journal and outbox event must commit in one transaction; never call the bank
  inside a transaction.
- No secrets in git (`deploy/k8s/secret.yaml` and `.env` are ignored).
- Git workflow: every feature or fix gets its own branch (`feat/…`, `fix/…`, `docs/…`) and a pull
  request against `main`; never commit to `main` directly. Conventional commit messages.
- No AI attribution in commits or pull requests: no `Co-Authored-By` trailer for an assistant and no
  "generated with" footer.
