# Coding-agent files

Everything for AI coding assistants (Claude Code) lives in this folder.

| Path | Purpose |
|---|---|
| `CLAUDE.md` | Project rules every session loads: stack, money and transaction rules, how to run checks |
| `skills/verify/` | Build and run all tests, with the Colima Docker settings |
| `skills/smoke-test/` | Start the Compose stack and exercise the API end to end |
| `skills/finish-step/` | Checklist for closing a roadmap step: verify, docs, commit |
| `settings.json` | Shared permissions so routine build/test commands don't prompt |
| `settings.local.json` | Personal overrides (git-ignored) |

Add new skills as `skills/<name>/SKILL.md` with `name` and `description` front matter; they are
invoked as `/<name>`. Subagents go in `agents/`, slash-command style prompts in `skills/` too.
