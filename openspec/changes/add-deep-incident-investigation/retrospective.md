# Retrospective: add-deep-incident-investigation

> Written: 2026-09-01 (after verify passed)
> Commit range: `9a95ff2b97ceaa327f7ac2ffea26f8ba2ece13bf..560edf1`
> Worktree: `/Users/allenj/work/AllenMuu/stackwatch/.worktrees/add-deep-incident-investigation`

---

## 0. Evidence

- **Commit range**: 20 commits
- **Diff size**: +5,674 / -4 lines across 88 files
- **Tasks done**: 17/17 (`tasks.md`)
- **Active hours**: approximately 8 hours across 2026-08-31 to 2026-09-01
- **Subagent dispatches**: 3 implementation/review agents
- **New external dependencies**: PostgreSQL JDBC, Flyway, and Testcontainers (versions managed by the Spring Boot parent); no new runtime provider dependency
- **Bugs encountered post-merge**: 2 controller-test defects (Mockito matcher mixing and invalid RUNNING transition), fixed in `462dfd9`
- **OpenSpec validate state at archive**: pass (`2/2` items valid)
- **Test coverage signal**: 88 passed, 12 skipped in host-enabled `jenv exec mvn -o test`; 35 focused tests passed in sandbox

Commit chain (high level): PostgreSQL foundation → immutable audit model → deterministic Skills/Toolsets →
five redaction/evidence hardening rounds → bounded runtime/escalation → APIs/metrics/evaluator → docs and
verification (`fb63d35..560edf1`).

---

## 1. Wins

- Bounded, asynchronous Deep Path was added without changing Fast Path return behavior (`79ca8fb`, `897e654`).
- Evidence governance became fail-closed: only SUCCESS observations can be cited, with two-source verification (`83c3da7`, `e4258a3`).
- The redaction scanner survived nested escaping, malformed quoting, chained credentials, and URL cases (`2dbe49e` through `83c3da7`; `ToolExecutorTest`).
- The deterministic Feign-timeout evaluator provides provider-free regression coverage (`3bed2bd`, `IncidentEvaluatorTest`).
- Opt-in configuration and disabled-startup behavior were verified (`IncidentDisabledStartupTest`, `IncidentFlywayConfigurationTest`).

## 2. Misses

- 🟡 PostgreSQL Testcontainers coverage could not execute because Docker was unavailable; 12 tests remain skipped (`verify.md` §5).
- 🟡 Sandbox Maven runs cannot attach Mockito's Byte Buddy agent; host-enabled execution is required for Mockito suites.
- 📌 Delta specs for four new capabilities still need `/opsx:sync` into `openspec/specs/` (`verify.md` §3).

## 3. Plan deviations

| Plan task | What changed | Why |
|---|---|---|
| 3.2/3.3 | Redaction was expanded through five review rounds | Security review found progressively deeper escaping and malformed-input bypasses. |
| 4.3 | Escalation was extended to all Fast Path paths, then guarded for missing cluster IDs | Preserve review-required paths while avoiding invalid L1 identities (`897e654`). |
| 6.1 | Docker integration tests were recorded as skipped | No Docker daemon was available on the host. |

## 4. Skill / workflow compliance

| Skill | Used |
|---|---|
| `grill-with-docs` | ✓ |
| `openspec-propose` | ✓ |
| `openspec-apply-change` | ✓ |
| `superpowers:subagent-driven-development` | ✓ |
| `superpowers:test-driven-development` | ✓ |
| `superpowers:requesting-code-review` | ✓ |
| `openspec` verification/retrospective flow | ✓ |

### Deliberately Skipped Skills

None.

## 5. Surprises

- Spring Boot 4.1's autoconfiguration package moves required explicit verification of the datasource,
  Kafka, and PgVector exclude names (`b69a069`, `IncidentFlywayConfigurationTest`).
- A single regex-based redactor was insufficient for nested stringified JSON; a syntax-preserving scanner
  was needed to satisfy fail-closed behavior (`83c3da7`).
- L1 cache results do not carry a cluster ID, so escalation must validate identity before scheduling (`897e654`).

## 6. Promote candidates → long-term learning

- [ ] 🟡 **Treat operational-output redaction as a parser boundary, not a regex convenience** → **Promote to project CLAUDE.md**
  > **Why**: Five consecutive security review rounds found progressively deeper escaping and malformed-value leaks.
  > **How to apply**: Any new adapter or persistence boundary must add nested/unterminated secret fixtures before review.

- [ ] 🟡 **Run host-enabled JDK 21 tests when Mockito or Testcontainers are present** → **Promote to memory**
  > **Why**: Sandbox runs consistently failed on Byte Buddy self-attach while the same suite passed with host access.
  > **How to apply**: When Maven reports MockMaker/agent or Docker errors, rerun with approved host execution before diagnosing code.

- [ ] 📌 **Sync new delta specs after archive** → **Promote to schema**
  > **Why**: Verification found four valid, unsynced capability deltas even though the change itself is complete.
  > **How to apply**: After `/opsx:archive`, run `/opsx:sync` when the capability should become part of the main spec set.
