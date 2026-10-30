# Verification notes

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
