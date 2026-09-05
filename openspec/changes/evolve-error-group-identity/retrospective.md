# Retrospective: evolve-error-group-identity

> Written: 2026-09-05 (after verify passed)
> Commit range: `c5e8ae9..743f447`
> Worktree: `/Users/allenj/work/AllenMuu/stackwatch/.worktrees/evolve-error-group-identity`

---

## 0. Evidence

- **Commit range**: `c5e8ae9..743f447` (16 commits)
- **Diff size**: +3183 / -180 lines across 43 committed implementation files
- **Tasks done**: 18/18 (`tasks.md` has no unchecked implementation rows)
- **Active hours**: approximately 22.5 hours between first implementation commit and final documentation clarification
- **Subagent dispatches**: 15 implementation/review dispatches, including retries and follow-up fix rounds
- **New external dependencies**: none; `pom.xml` was unchanged
- **Bugs encountered post-merge**: 6 review findings, all fixed before verification (API arity, datasource isolation, idempotency edge cases, fallback authority, and documentation wording)
- **OpenSpec validate state at archive**: pass (`openspec validate --all --json`, 9/9 valid)
- **Test coverage signal**: 141 Maven tests passed; 22 Docker/LLM-gated tests skipped on this host

Commit chain (时序):

```
b1c9cb4 structured throwable ingress
dcff5c5 composed ingress contracts
f200f9f normalized V2 fingerprints
abce250 harden normalization boundaries
611b78d opt-in history schema
31009d4 harden history models and wiring
c9963ea restore unrelated report artifact
baf506b assert identity isolation
7890668 PostgreSQL occurrence repository
7ff4d60 isolate history datasource transactions
631a13d bind history transaction manager in tests
5d46a02 isolate Incident and History transactions
c9397e0 typed exact-match analyzer routing
a5b69af operator documentation
c2ce70a harden analyzer fallback routing
743f447 clarify V1 occurrence accounting
```

## 1. Wins

- [evidence: c9397e0, c2ce70a, ErrorAnalyzerUnitTest] Exact matching is now typed, normalized once, occurrence-aware, V2-primary, and resilient to History outages without requiring an LLM in unit tests.
- [evidence: 7890668, 5d46a02, PostgresErrorGroupRepositoryTest] PostgreSQL identity and idempotency boundaries are explicit, with dedicated datasource and transaction-manager wiring for both Incident and History.
- [evidence: a5b69af, 743f447, docs/guide] Operators now have executable nested datasource and fingerprint configuration examples, stable event-ID guidance, and a clear #6 boundary.
- [evidence: mvn -o test] The full local regression suite is green with 141 passing tests and only environment-gated skips.

## 2. Misses

- 🟡 [painful | evidence: task3/task4 review logs] Several public model and datasource boundaries were initially too implicit; independent review was needed to catch parameter limits and two-datasource auto-configuration routing.
- 🟡 [painful | evidence: PostgresErrorGroupRepositoryTest, verify.md] Docker was unavailable, so PostgreSQL migration, concurrency, and dual-datasource runtime behavior could not execute locally.
- 📌 [nit | evidence: task1 re-review] Source-compatible long Java constructors were intentionally not retained because the repository's <=5-parameter rule and HTTP compatibility contract take precedence; downstream Java callers may need the composed factories.

## 3. Plan deviations

| Plan task | What changed | Why |
|---|---|---|
| 3.1–3.5 | Added explicit Incident-side JdbcTemplate/transaction-manager beans and blank-ID consistency tests beyond the initial task brief | Review exposed Spring Boot's multi-datasource default-candidate behavior and an idempotency mismatch; these were required to preserve independent feature flags. |
| 4.2–4.4 | Added authoritative-group handling for `accepted=false` and a hard stop on V1 lookup after History write failure | Review identified a race/fallback path that could return a non-authoritative RCA. |
| 5.1–5.2 | Added three main OpenSpec capability specs and verify/retrospective artifacts | The change introduced new capabilities with no existing main spec files, so sync required creating them with Purpose sections. |

## 4. Skill / workflow compliance

| Skill | Used |
|---|---|
| superpowers:brainstorming | ✓ |
| superpowers:writing-plans | ✓ |
| superpowers:using-git-worktrees | ✓ |
| superpowers:subagent-driven-development | ✓ |
| superpowers:test-driven-development | ✓ (through implementer briefs and RED/GREEN task flow) |
| superpowers:requesting-code-review | ✓ (independent review agents after each task) |
| superpowers:finishing-a-development-branch | pending archive handoff |

### Deliberately Skipped Skills

None.

## 5. Surprises

- The first History-specific JdbcTemplate bean changed Spring Boot's default JDBC candidate selection; Incident had to receive its own explicit template and transaction manager to remain isolated.
- A database event ID can collide across different strict identities, so the ledger must be checked before group creation to avoid an empty group.
- “V1 read-only” is ambiguous: identity migration is read-only, but accepted occurrences still update V1 counters and timestamps; operator docs now state this explicitly.

## 6. Promote candidates → long-term learning

- [ ] 🟡 **Treat multi-datasource wiring as an explicit interface from the first task** → **Promote to project CLAUDE.md** (configuration section)
  > **Why**: Adding a second JdbcTemplate can silently redirect unqualified repositories and transaction annotations, as exposed by the Task 4 review.
  > **How to apply**: Whenever a feature adds a DataSource, require named JdbcTemplate and transaction-manager beans plus qualifier tests before enabling it.

- [ ] 🟡 **Distinguish compatibility read-only from mutation read-only in specs and docs** → **Promote to OpenSpec schema**
  > **Why**: V1 lookup remained compatible while occurrence accounting mutated the existing group, and this wording mismatch required a documentation review fix.
  > **How to apply**: For versioned compatibility requirements, state separately whether re-keying, record mutation, and RCA reuse are permitted.

- [ ] 📌 **Keep Docker-gated persistence tests runnable without Docker** → **Promote to one-off**
  > **Why**: The repository tests correctly skipped under `disabledWithoutDocker=true`, but actual SQL/concurrency behavior remains unobserved in this environment.
  > **How to apply**: Run the focused PostgreSQL and Incident integration classes in CI or a Docker-enabled host before production rollout.
