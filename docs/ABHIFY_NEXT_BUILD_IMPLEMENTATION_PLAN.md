# Abhify.AI next build: implementation plan and acceptance

| Phase | Deliverable | Acceptance (tested) |
|---|---|---|
| 0 | Baseline audit, 5.4 protected | hash/size/version recorded; test baseline |
| 1 | ContextResolver | relevance, freshness, permissions, missing sources |
| 2 | Universal search additions | projects, AI tasks, recent searches; permission (disabled memory hidden) |
| 3 | Agent 2.0 (tool registry + project review workflow) | registration, validation, permission, approval, verification, cancel, undo, audit, failure report |
| 4 | Productivity (priority/blocked, daily plan, weekly review) | no invented deadlines; plan from real tasks |
| 5 | Voice connected (plan my day, brief me, weekly review, review project) | actions registered and executed |
| 6 | Personal briefing (on demand, spoken, optional reminder) | real records with sources; reminder off by default |
| 7 | Skill graph + adaptive next step | no score without evidence; weak topics from data |
| 8 | Desktop parity (five destinations, Study/Discover, News language, briefing, search) | backend tests + browser check |
| 9 | News intelligence (related, comparison, learn this topic) | grouping only from cached stories; labels |
| 10 | Security/regression, docs, internal APK in `build/internal/` | all suites; 5.4 hash unchanged |
