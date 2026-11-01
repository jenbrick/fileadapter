# Verification notes

## Original-record result update — November 1, 2026

Added durable original-source capture, HARD/MEDIUM/SOFT rules and contract isolation, incoming-format customer result files, internal diagnostics/outbox manifests, and opt-in correlated member/contract service results. The example verifier now runs 20 packaged cases, including the hard/medium/soft customer CSV fixtures. The original 17 mapping/input fixtures remain unchanged.

[GitHub Actions run 41322630031](https://github.com/jenbrick/fileadapter/actions/runs/41322630031) passed the full Gradle test/build and all 20 packaged examples for the initial result implementation. Additional tests cover namespaced XML, result-field collisions, contract projection failures, unmapped service codes and LIVE hard stops preserving earlier acknowledgements. The refinements, including the namespace-preservation fix, passed in [GitHub Actions run 41323498325](https://github.com/jenbrick/fileadapter/actions/runs/41323498325): **all 106 tests, the executable-JAR build and all 20 packaged example comparisons passed** for runtime commit `9dbe7f6`.

Result tests use local HTTP servers and file-backed H2. They cover all 11 source layouts, approved customer text, complete-source versus partial-input handoff, row provenance, scoped member versus contract rejection, unknown response identities, persisted issue replay, and skipping acknowledged batches. Local Java syntax, YAML, script syntax, documentation links and embedded/source diagram consistency are also checked. Full local Gradle remains network-blocked; GitHub CI supplies the full build and runtime verification.


## Contract batching update — November 1, 2026

Added GROUPED request partitioning, single-contract and bounded contract-array fixtures, explicit per-member payer-consent grouping, and JUnit coverage for complete-group limits, exact shapes and HTTP retry/resume. Both example runners now include 17 cases. The new GitHub verification workflow runs the full Gradle tests/build followed by the packaged example verifier on pushes to main and pull requests.

**Remote verification passed:** [GitHub Actions run 41320722630](https://github.com/jenbrick/fileadapter/actions/runs/41320722630) for runtime commit `13e7dbc` completed successfully. It ran `./gradlew --no-daemon test bootJar` (including the Spring/HTTP integration tests) and all **17 packaged example comparisons**, including both contract output shapes.

Local checks completed for this update:

- Compiled the production `RequestBatcher` and `RecordFailure` with Java 17 and ran 13 assertions covering nonadjacent rows, one/many contracts, last partial batches, distinct-member and row limits, invalid/missing keys, empty input and immutable output.
- Validated YAML structure for all 17 profiles, unique multi-field fixture resolution for all 8 new CSV rows, expected output grouping/counts, Markdown links and fences, diagram source consistency, Bash syntax and Python syntax.
- Full local `./gradlew test bootJar` is blocked: no cached Gradle distribution and the distribution download reports network unreachable. The focused Java checks alone do not verify Spring binding, template rendering or HTTP integration; the successful remote workflow above covers those. See the [workflow definition](../.github/workflows/verify.yml).

The older verification record below applies to the earlier cleanup, not to the new batching code.

Cleanup verified October 30, 2026.

## Checks completed

- Compiled every production Java source with the installed Java 17 compiler module and the existing packaged application dependencies.
- Constructed a temporary verification JAR from those compiled classes, current resources and existing dependency JARs. No generated binary is committed.
- Ran `scripts/verify-examples.py` against that JAR: all 15 cases passed. These include 11 physical input layouts, three alternate aggregated JSON shapes and the independent per-record mapping. Both readable previews and exact JSON Lines bodies were compared with expected results.
- Executed 19 additional checks against a real Spring application context, file-backed H2 and a local HTTP server. These exercised exact LIVE envelope delivery, content deduplication, HTTP lookup, retry/resume with unchanged body/key/lookup count, permanent rejection, uncertain 202, grouped conflicts, value-free reports, lookup failure, no-change rows, invalid later data, unsafe interrupted delivery, rejection of removed engine versions, prior flexible snapshot compatibility, record limits and invalid templates.
- Checked Markdown file links, Bash command syntax, executable example-runner syntax, Python syntax, embedded Mermaid/source consistency, required projections on all 15 feed profiles and exact parity with the original user-supplied JSON sample.

## Build limitation

The full Gradle/JUnit suite was not re-run for this cleanup. The environment had no cached Gradle distribution, and the wrapper's Gradle download failed/timed out. The direct compilation and application checks above do not substitute for a successful Gradle test run.

On a workstation with the configured Gradle/JDK access, run:

```bash
./gradlew test bootJar
bash scripts/run-examples.sh
# Optional, when Python 3 is installed:
python scripts/verify-examples.py
```

The retained JUnit suite has been updated to exercise the projection-only code path and expanded input examples. Tests for the removed employee mapper and specialized consent planner were removed. Generic parser/security, scalar mapping, validation and delivery/recovery checks remain or were moved to the flexible suites.
