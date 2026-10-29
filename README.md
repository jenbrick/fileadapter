# FileAdapter POC

A Spring Boot / Gradle proof of concept for adapting files into configurable JSON request bodies. File syntax, enrichment, JSON shape and HTTP delivery have separate responsibilities. Consent is an example mapping, not a fixed application schema.

Java 17 · Spring Boot 3.5.16 · Gradle Wrapper 8.14.3 · Jackson · Apache Commons CSV · local H2 ledger.

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

## Use another adapter

```bash
for file in flexible-consents.csv flexible-consents.psv flexible-consents.fw \
            flexible-consents.xml flexible-consents.json; do
  java -jar build/libs/fileadapter-0.1.0.jar \
    --spring.profiles.active=flexible-demo \
    --ingestion.file="examples/flexible/$file" || break
done
```

All five formats produce the same JSON envelope. Headerless delimited layouts are also supported. Change source layout/aliases independently of output templates. Add a `FileFormatAdapter` for new physical syntax, a `RecordEnricher` for another lookup, or a `ValueTransform` for new scalar conversion.

## Build requirements

Java must already be available through `JAVA_HOME` and `PATH` to launch Gradle. The build compiles and tests with JDK 17 and uses Foojay to provision that toolchain when missing. The first build needs approved network access. Set `JAVA_HOME` to the JDK installation root, not `bin`, and reopen Git Bash after changing Windows environment variables.

```bash
java -version
javac -version
./gradlew -q javaToolchains
```

Git Bash uses `./gradlew`; Command Prompt can use `gradlew.bat`. The artifact is `build/libs/fileadapter-0.1.0.jar`, even if an internal clone is named `file-adapter-poc`.

## Delivery, persistence and scope

PREVIEW exports the selected request bodies; LIVE posts them with Jackson. Normal HTTP enrichment can run in PREVIEW, while fixture-based member enrichment is refused in LIVE. Use `UPSERT_URL` / `UPSERT_BEARER_TOKEN` for delivery, and the separate lookup variables described in the mapping guide.

The worker validates and stages the whole file before any upsert. It stores configuration snapshots, enriched rows, mapped payloads, source provenance and delivery state in H2. Resume uses saved bodies. Replay safety defaults to false and requires a service-side idempotency guarantee. Grouped requests track multiple contributing source rows.

This POC uses one worker, persistent local work storage and a mounted LAN input directory. Polling requires a closed input file plus its `.ready` marker; one-shot input is copied. To poll a real share, configure the input path, profile patterns and `--ingestion.poll-enabled=true`. The demo's filename patterns are illustrative. The application does not mount SMB shares or manage share credentials.

FILE scope supports bounded aggregation, not arbitrary-volume streaming: the example limit is 1,000 records, with a configurable maximum of 10,000. No batch atomicity, per-entry response handling, exactly-once transaction or distributed claiming is implied. Inputs, enrichment results, previews and archives can contain member data; use appropriate access and retention controls. Repository examples are synthetic.

## Documentation and earlier examples

- [Developer walkthrough](docs/developer-walkthrough.md): primary team guide.
- [Flexible mappings](docs/flexible-mappings.md): template syntax, grouping, enrichment and demos.
- [Architecture](docs/architecture.md) and [Mermaid source](docs/architecture.mmd).
- [Formats](docs/formats.md) and [extensions](docs/extensions.md).
- [Earlier specialized consent example](docs/consent.md): engine version 3, retained for compatibility; it uses the older per-row array contract.
- [PowerPoint snapshot](docs/presentations/README.md): historical reference, not the current configurable contract.

The original employee mappings still run without a demo profile:

```bash
java -jar build/libs/fileadapter-0.1.0.jar --ingestion.file=examples/enrollment.csv
```

Existing consent fixtures remain runnable with `--spring.profiles.active=consent-demo`. They are deliberately separate from the latest flexible mapping example so stored older snapshots retain their behavior.
