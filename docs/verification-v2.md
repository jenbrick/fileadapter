# What was checked for earlier changes

> V2: plain-English copy. [Original for comparison](verification.md). Commands, configuration examples and diagrams are unchanged.
This is the verification history for the changes listed below. The dates, commits, workflow links and results refer to those changes, not to a new test run for this wording update.

## Code comments and cleanup — November 1, 2026

Added plain-language comments across 51 production Java files, nine test classes, scripts, Gradle and YAML/workflow files. They explain intake, lookup order, mapping, complete-contract requests, recovery and original-record returns.

Removed unused normalizer/projection dependencies from `FileProcessor` after preparation moved to `PreparationCoordinator`, plus unused imports and Profile/ledger overloads. Kept saved-run reading, database upgrades and recovery compatibility that are still needed. Class diagrams and CSV breakpoints now show the coordinator.

Checks confirmed YAML values/workflow behavior were unchanged, every production class had an explanation, and Java edits outside five cleanup files changed comments/whitespace only. Java, shell/Python syntax, Markdown links and embedded/source diagrams checked out.

[Actions run 41331692674](https://github.com/jenbrick/fileadapter/actions/runs/41331692674) passed the full Gradle test suite, runnable JAR build and **20 packaged examples** for `d491f6f`.

## Group lookup before member lookup — November 1, 2026

Added external-group resolution through `GroupResolver`, saved internal group/customer values per run/group and a typed nine-property member request. Input and customer-return examples now include external group IDs. Expected consent JSON stayed the same.

[Actions run 41328733959](https://github.com/jenbrick/fileadapter/actions/runs/41328733959) passed the full Gradle build/tests and 20 examples for `249be88`. `GroupMemberLookupTest` checks allowed payload fields, call order, reused group results, missing/failed/incomplete context, delivery recovery and interrupted member-lookup reuse.

The final [run 41329355585](https://github.com/jenbrick/fileadapter/actions/runs/41329355585) passed full tests, JAR build and **20 examples** for `9abf369`, including MEDIUM group-failure contract rejection and LIVE refusal of fixture group lookup.

Local checks covered Java syntax, lookup order, group-fixture rules, Markdown links and diagram consistency. Local Gradle downloads were network-blocked, so CI supplied the full compilation/integration/example runs.

## Original-record customer results — November 1, 2026

Added saved original records, HARD/MEDIUM/SOFT rules, contract rejection, same-format customer results, internal reports/ready manifests and optional member/contract service results. The verifier grew to 20 cases with HARD/MEDIUM/SOFT CSV returns. Earlier 17 mapping/input outputs stayed unchanged.

[Run 41322630031](https://github.com/jenbrick/fileadapter/actions/runs/41322630031) passed the full Gradle test/build and 20 examples for the first implementation. Further tests covered namespaced XML, result-field collisions, contract JSON failures, unknown service codes and later LIVE stops preserving earlier successes.

The final changes, including namespace preservation, passed in [run 41323498325](https://github.com/jenbrick/fileadapter/actions/runs/41323498325) for `9dbe7f6`: **106 tests, runnable JAR build and 20 example comparisons**.

Tests used local HTTP servers and H2 on disk. They checked all 11 layouts, approved customer text, complete versus partial input, source references, member versus contract rejection, unknown response identities, restored findings and skipped acknowledged requests.

Local syntax/YAML/link/diagram checks also passed. Full local Gradle remained blocked by download access; CI provided runtime verification.

## Complete-contract requests — November 1, 2026

Added `GROUPED`, single-contract/contract-array examples, distinct payer-consent grouping and tests for limits, JSON shape and HTTP retry/resume. Runners covered 17 cases at this point. The verification workflow runs Gradle tests/build and the example verifier on main pushes and pull requests.

[Run 41320722630](https://github.com/jenbrick/fileadapter/actions/runs/41320722630) passed for `13e7dbc`. It ran `./gradlew --no-daemon test bootJar`, including Spring/HTTP integration tests, and **17 example comparisons** with both contract formats.

Local checks for that update:

- Compiled `RequestBatcher` / `RecordFailure` with Java 17 and ran 13 assertions: scattered rows, one/several contracts, final smaller batch, member/row limits, missing/invalid keys, empty input and output that cannot be modified.
- Checked YAML for 17 profiles, unique fixture matches for eight new rows, expected grouping/counts, Markdown links/fences, matching diagram sources and Bash/Python syntax.
- Could not run full local Gradle because no distribution was cached and download access failed. Focused Java checks did not prove Spring binding, templates or HTTP integration. The successful remote workflow covered those. See [workflow](../.github/workflows/verify.yml).

## Earlier cleanup — October 30, 2026

The checks below apply to the earlier version, before contract batching and result files were added.

- Compiled all production Java with the installed Java 17 compiler and existing application dependencies.
- Built a temporary verification JAR from those classes/resources/dependencies. No generated binary was committed.
- Ran `scripts/verify-examples.py`: **15 cases passed**, covering 11 input layouts, three alternate grouped JSON formats and per-record mapping. Checked readable previews and exact JSON Lines bodies.
- Ran **19 further checks** with real Spring, H2 on disk and a local HTTP server: LIVE envelope, content deduplication, HTTP lookup, unchanged retry/resume body/key/lookup count, permanent rejection, uncertain 202, grouped conflicts, reports without input values, lookup failure, no-change rows, invalid later records, unsafe interrupted delivery, removed engine-version rejection, earlier flexible snapshots, record caps and bad templates.
- Checked Markdown links, Bash/Python syntax, matching embedded/source diagrams, required projections on 15 profiles and agreement with the submitted JSON sample.

### Build limitation at that time

The full Gradle/JUnit suite was not rerun for this earlier cleanup. No Gradle distribution was cached, and its download failed/timed out. Direct compilation/application checks were useful but did not replace a full Gradle run.

On a workstation with the required Gradle/JDK access:

```bash
./gradlew test bootJar
bash scripts/run-examples.sh
# Optional, when Python 3 is installed:
python scripts/verify-examples.py
```

The retained JUnit suite was updated for the projection-only flow and expanded examples. Removed employee/consent-planner tests went with their removed code. Generic parsing/security, mapping, validation and recovery checks stayed or moved into flexible suites.
