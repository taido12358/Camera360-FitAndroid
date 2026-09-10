# After Coding

Update, in order:

1. `tasks/active/` — move the task file to `completed/` or `blocked/` if
   its status changed, or update it in place if still active.
2. `tasks/state/current-state.md` — reflect what's now true.
3. `logs/progress/current.md` — update phase/completed/in-progress/next.
4. `logs/ai-agent/sessions/YYYY/MM/YYYY-MM-DD-session-N.md` — write a new
   session log (see rules/ai-agent/documentation.md for the format).
5. `logs/ai-agent/latest.md` — point to the new session log.

If the change touches architecture, update the relevant
`docs/architecture/*.md` file. If it's a hard-to-reverse decision, write
an ADR in `docs/decisions/`. If it changes a convention, update the
relevant `rules/` file. Do not let any of these go stale — a wrong
doc is worse than no doc.

## Status vocabulary

Use exactly these terms (see docs/workflow/testing.md for what qualifies
as TESTED/VERIFIED for this repo, given near-zero existing test coverage):

```
PLANNED, SCAFFOLDED, IN_PROGRESS, IMPLEMENTED, TESTED, VERIFIED,
PRODUCTION_READY, BLOCKED, DEPRECATED
```

Never use "DONE" as a status.
