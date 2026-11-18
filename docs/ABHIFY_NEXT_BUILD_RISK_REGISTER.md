# Abhify.AI next build: risk register

| # | Risk | Likelihood / impact | Mitigation |
|---|---|---|---|
| R1 | 149 uncommitted paths since `a9325d2` | high / severe (all work lost on disk failure) | recommend a commit or verified backup; owner approval needed |
| R2 | Overwriting `Abhify-5.4.apk` | low / high | internal build goes only to `build/internal/`; the script refuses an existing file; hash re-checked at the end |
| R3 | Agent creates wrong tasks | medium / medium | AI proposals shown as a preview; each marked "found in your notes" (exact quote verified) or "suggestion"; creation only after approval; read-back verification; undo |
| R4 | Retrieved text tries to steer the agent | medium / high | notes and documents are fenced as untrusted data; the agent can only propose tasks (a typed list); tools are executed by code, never by model output |
| R5 | Invented deadlines or progress | medium / medium | planner and briefing use only stored dates and statuses; unit tests with no-date data |
| R6 | Battery from scheduled briefing | low / low | off by default; one WorkManager job per day; no AI in the background; quiet hours |
| R7 | Desktop changes break web cache or companion routes | medium / medium | every new prefix added to `sw.js` API_PATHS and README (guard tests); no reuse of `/companion` |
| R8 | Phone validation still pending (RAG, 5.3, 5.4) | – | the owner's manual checklist; no device claims made |
| R9 | Small-model quality (Qwen 1.5B) for project review | medium / low | the preview with verification makes weak proposals visible; online provider optional under consent |
