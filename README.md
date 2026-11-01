# FileAdapter POC

A Spring Boot / Gradle proof of concept for adapting files into configurable JSON request bodies. File syntax, enrichment, JSON shape and HTTP delivery have separate responsibilities. Consent is an example mapping, not a fixed application schema.

Java 17 · Spring Boot 3.5.16 · Gradle Wrapper 8.14.3 · Jackson · Apache Commons CSV · local H2 ledger.

## Planning the first production release

Start with the [January Delivery Plan Overview](docs/january-delivery-plan-overview.md) for the team discussion, then use the [January Delivery Plan Detailed](docs/january-delivery-plan-detailed.md) for scope, API decisions, acceptance and the four-week sequence. Both include Mermaid diagrams and distinguish the proposed initial release from the current POC.

See the [January release class and sequence diagrams](docs/january-release-diagrams.md) for the proposed classes, happy path, and error paths; optional scope remains marked for discussion.

## Group lookup before member lookup

The consent profiles now run **external group ID → group service → internal group number + customer ID → member demographics service → contract/PIN/member GUID → consent JSON**. `GroupEnricher` runs before `ConsentMemberEnricher`. The member service receives only the typed member payload plus resolved group context; consent preferences, payer/coverage data and the upsert JSON are excluded.

See [group and member lookup contracts](docs/group-member-lookups.md) for exact POC payloads, company-service adapter ports, fixtures and recovery. LIVE requires both `GROUP_LOOKUP_MODE=HTTP` / `GROUP_LOOKUP_URL` and `FLEXIBLE_LOOKUP_MODE=HTTP` / `CONSENT_LOOKUP_URL`; tokens are separate. The original format and batching demos still run in PREVIEW with both lookup stages using fixtures.

## Bounded contract requests: the two API candidates

Use `flexible-contract-single-v1` for one complete contract with its members’ consents per POST, or `flexible-contract-array-v1` for an array of up to `CONTRACTS_PER_REQUEST` complete contracts (default 2). Both retain each member’s multiple payer-consent groups and each consent’s coverage collection. These are candidate request wrappers for the evolving endpoint.

```bash
./gradlew test bootJar
java -jar build/libs/fileadapter-0.1.0.jar \
  --spring.profiles.active=flexible-demo \
  --ingestion.profile-id=flexible-contract-single-v1 \
  --ingestion.file=examples/flexible/contract-batches.csv
# Repeat with --ingestion.profile-id=flexible-contract-array-v1 for bounded arrays.
```

The interleaved eight-row CSV repeats external contract IDs across five members in three contracts. It produces three single-contract bodies or two array bodies. Contract limits default to 25 distinct members and 100 source rows; oversized contracts fail preparation. See [contract batching](docs/contract-batching.md) for both expected outputs, environment settings, payer-consent identity and recovery. The original whole-file examples below remain as mapping comparisons.

## Start with the flexible mapping example

In Git Bash, from the repository root:

```bash
./gradlew test bootJar
java -jar build/libs/fileadapter-0.1.0.jar \
  --spring.profiles.active=flexible-demo \
  --ingestion.file=examples/flexible/flexible-consents.csv
```

The explicit demo profile uses PREVIEW with synthetic lookup fixtures and makes no external HTTP calls. Open `var-flexible-demo/preview/<run-id>/flexible-consents.csv.preview.json`.

Three rows describe two members and two payer coverages. Configuration groups them into one request containing the latest sample's structure: `{ "consents": [...] }`, entry-level `consentManager`, provider `coverage: []`, and an array of coverage items under the payer consent. The result matches [expected-envelope.json](examples/flexible/expected-envelope.json) and the [user-supplied sample](examples/json-sample/CONSENT%20JSON%20ARRAY.txt), after its descriptive heading is removed.

See the [developer walkthrough](docs/developer-walkthrough.md) for the code tour and [flexible mapping guide](docs/flexible-mappings.md) for the configuration vocabulary and all commands.

## Change the output shape without changing Java

Run the same CSV as a root array:

```bash
java -jar build/libs/fileadapter-0.1.0.jar \
  --spring.profiles.active=flexible-demo \
  --ingestion.profile-id=flexible-root-array-v1 \
  --ingestion.file=examples/flexible/flexible-consents.csv
```

Or select `flexible-reshaped-v1` to produce `payload.entries`, move the manager inside transaction details, and rename coverage to `payerInfo.coverages`. Both are configuration-only variants, with expected JSON fixtures. They demonstrate changing contracts rather than claim the endpoint accepts every variant.

The [working YAML](src/main/resources/application-flexible-demo.yml) defines scalar source mappings, transforms, constants/nulls, nested objects, filtered/grouped arrays, required fields and request scope. It also includes a per-record name-mapping example without any consent enrichment.

## Customer results and IT diagnostics

The worker writes original source records plus `RESULT_CODE` and `RESULT_REMARKS` in the **incoming file format**. HARD validation rejects the file, MEDIUM rejects a contract, and SOFT warns without blocking. An opt-in service-response adapter can identify a rejected member and return only that member’s original rows. Customer remarks use approved text; detailed findings remain in a separate IT JSON report.

```bash
bash scripts/run-error-examples.sh
```

Default outboxes are `var-flexible-demo/results/customer/<run-id>/` and `var-flexible-demo/results/internal/<run-id>/`. Configure mounted LAN paths with `CUSTOMER_RESULTS_DIR` / `INTERNAL_RESULTS_DIR`; another process sends customer files and IT emails. Consume published `ready.json` manifests and honor PREVIEW/LIVE mode. See [error/result handling](docs/error-handling.md) for field widths, examples, response correlation and partial outcomes.

## Run every input format

```bash
bash scripts/run-examples.sh
```

Runnable examples cover CSV and pipe with/without headers, semicolon, tab, fixed width, XML, JSON array, JSON object and JSON Lines. The runner also exercises root-array, root-contract-ID, reshaped, per-record, bounded contract and severity/error-result variants. See the [format matrix](docs/formats.md) for filenames, profile IDs and expected results.

With Python 3 available, compare all packaged outputs against expected JSON:

```bash
python scripts/verify-examples.py
```

The flexible demo is the default Spring profile. Add a `FileFormatAdapter` for new physical syntax, a `RecordEnricher` for another lookup, or a `ValueTransform` for a new scalar conversion. A new delimiter or output shape is a configuration change.

For Windows setup and copy/paste commands, see the [Git Bash run guide](docs/git-bash-run-guide.md).

## Build requirements

Java must already be available through `JAVA_HOME` and `PATH` to launch Gradle. The build compiles and tests with JDK 17 and uses Foojay to provision that toolchain when missing. The first build needs approved network access. Set `JAVA_HOME` to the JDK installation root, not `bin`, and reopen Git Bash after changing Windows environment variables.

```bash
java -version
javac -version
./gradlew -q javaToolchains
```

Git Bash uses `./gradlew`; Command Prompt can use `gradlew.bat`. The artifact is `build/libs/fileadapter-0.1.0.jar`, even if an internal clone is named `file-adapter-poc`.

## Delivery, persistence and scope

PREVIEW exports the selected request bodies; LIVE posts them with Jackson. Normal group/member HTTP enrichment can run in PREVIEW, while fixture-based lookup at either stage is refused in LIVE. Use `UPSERT_URL` / `UPSERT_BEARER_TOKEN` for delivery, and the separate group/member lookup variables described in the [lookup guide](docs/group-member-lookups.md).

The worker validates and stages the bounded file before any upsert. `GROUPED` scope partitions complete contracts into bounded requests; `FILE` and `RECORD` remain available. It stores configuration snapshots, enriched rows, mapped payloads, source provenance and delivery state in H2. Resume uses saved bodies. Replay safety defaults to false and requires a service-side idempotency guarantee. Grouped requests track multiple contributing source rows.

This POC uses one worker, persistent local work storage and a mounted LAN input directory. Polling requires a closed input file plus its `.ready` marker; one-shot input is copied. To poll a real share, configure the input path, profile patterns and `--ingestion.poll-enabled=true`. The demo's filename patterns are illustrative. The application does not mount SMB shares or manage share credentials.

FILE scope supports bounded aggregation, not arbitrary-volume streaming: the example limit is 1,000 records, with a configurable maximum of 10,000. Contract batching bounds outgoing requests; it does not remove the in-memory file limit or enforce serialized byte-size limits. No batch atomicity, exactly-once transaction or distributed claiming is implied. Member/contract result handling uses an explicit opt-in demo response contract; it does not assume the actual service already supports it. Inputs, enrichment results, previews and archives can contain member data; use appropriate access and retention controls. Repository examples are synthetic.

## Documentation

- [Runtime sequence and class diagrams](docs/runtime-diagrams.md): happy path, alternate paths, retries and extension interfaces.
- [CSV debugger walkthrough](docs/csv-debug-walkthrough.md): breakpoints and data from receipt through consent delivery.
- [Group then member lookup](docs/group-member-lookups.md): external group resolution and the member-only demographics payload.
- [Customer results and IT diagnostics](docs/error-handling.md): same-format original-record returns, severity handling and service results.
- [Contract batching and two JSON shapes](docs/contract-batching.md): complete-contract limits, multiple members and payer consents.
- [Step-by-step code walkthrough and change guide](docs/developer-walkthrough.md): how to change layouts, delimiters, attributes, nesting and root arrays.
- [Flexible mappings](docs/flexible-mappings.md): template syntax, grouping and enrichment.
- [Runnable format matrix](docs/formats.md): all input examples and output variants.
- [Architecture](docs/architecture.md) and [Mermaid source](docs/architecture.mmd).
- [Extension points](docs/extensions.md) and [verification notes](docs/verification.md).

The superseded employee mappings, specialized consent builder, old examples/scripts and PowerPoint have been removed. The flexible projection path is the only processing strategy. Previously saved flexible projection snapshots remain readable; earlier engine versions 1–3 require the previous Git revision. Keep old work directories until delivery status is resolved. Configuration changes create new run identities, so do not automatically re-submit old LIVE inputs.
