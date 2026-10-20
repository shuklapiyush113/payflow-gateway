# Coding-agent files

Claude Code files live in this folder. The rules shared by all coding agents are in
[AGENTS.md](../AGENTS.md) at the repo root, because Codex, Cursor and others only look for it there.

| Path | Purpose |
|---|---|
| `CLAUDE.md` | Loaded by every Claude Code session; imports `../AGENTS.md` and adds Claude-only notes |
| `skills/verify/` | Build and run all tests, with the Colima Docker settings |
| `skills/smoke-test/` | Start the Compose stack and exercise the API end to end |
| `skills/finish-step/` | Checklist for closing a roadmap step: verify, docs, commit |
| `settings.json` | Shared permissions so routine build/test commands don't prompt |
| `settings.local.json` | Personal overrides (git-ignored) |

Add new skills as `skills/<name>/SKILL.md` with `name` and `description` front matter; they are
invoked as `/<name>`. Subagents go in `agents/`, slash-command style prompts in `skills/` too.
