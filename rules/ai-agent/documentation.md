# Documentation Rules

- CLAUDE.md is index-only — never add explanations, rules, architecture,
  progress, or history directly into it. Add a link instead.
- Each fact has exactly one source of truth (see docs/architecture/README.md
  §"Why two apps, one repo" for the pattern) — link to it, don't copy it.
- Session log format (`logs/ai-agent/sessions/YYYY/MM/YYYY-MM-DD-session-N.md`):

```
# AI Session Log

Date:
Agent:

## Task
## Context Read
## Work Done
## Files Created
## Files Modified
## Files Deleted
## Tests
## Problems
## Decisions
## Remaining Work
## Next Recommended Action
```

- Do not paste full conversation transcripts into a session log — only
  what the next agent needs.
