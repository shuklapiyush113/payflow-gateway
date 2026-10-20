---
name: finish-step
description: Close out a PayFlow roadmap step or feature - verify, update README status and roadmap, record design decisions, commit. Use when a feature is done and about to be committed or pushed.
---

# Finish a step

1. Run the `verify` skill. Do not continue on red.
2. For anything user-visible, run the `smoke-test` skill (or the relevant part of it).
3. Update `README.md`: move the feature into "What works today", adjust the roadmap, and update the
   test count only with the number Maven actually reported.
4. Add each new design decision to `docs/architecture.md` in its existing format: the decision,
   why, and what it costs. Remove limitations the step fixed.
5. Resume numbers (TPS, latency, recall) go in docs only when measured; write how they were
   measured next to them.
6. Check `git status` for files that must not be committed (`deploy/k8s/secret.yaml`, `.env`,
   anything with real credentials).
7. Commit with a conventional message (`feat:`, `fix:`, `chore:`, `docs:`). Push only when asked;
   after a push, watch the CI run with `gh run watch` and report the result.
