# Developer walkthrough

[Download the PowerPoint](File_Adapter_POC_Developer_Walkthrough.pptx)

A 26-slide technical walkthrough for the development team. Allow about 35–45 minutes including a preview demo and discussion. Every slide includes presenter notes and links to the relevant code. Tables, code excerpts, and diagrams remain editable.

The content follows application source baseline `87a8c08` from 27 October 2026, including the source-labelled previews and the 70-test suite. The internal folder/repository can be named `file-adapter-poc`; the unchanged Gradle project still builds `fileadapter-0.1.0.jar`.

## Agenda

| Slide | Topic |
| --- | --- |
| 1 | File Adapter POC |
| 2 | Application flow |
| 3 | Code responsibilities |
| 4 | Input discovery and profile routing |
| 5 | Processing profile |
| 6 | Source representation and request body |
| 7 | Adapter contract |
| 8 | Supported file formats |
| 9 | Delimited and fixed-width layouts |
| 10 | XML record selection |
| 11 | JSON record boundaries |
| 12 | Physical fields and target paths |
| 13 | Mapping rules and conversions |
| 14 | Missing, null and blank values |
| 15 | Whole-file preflight |
| 16 | Run identity and saved configuration |
| 17 | Previews with source traceability |
| 18 | HTTP delivery outcomes |
| 19 | Partial success and replay safety |
| 20 | Processing states and recovery |
| 21 | Change configuration or extend Java |
| 22 | Git Bash build and preview demo |
| 23 | Live delivery and failure exercise |
| 24 | Validation evidence and POC boundaries |
| 25 | Contracts to agree with the team |
| 26 | Code tour and demo references |

## Preview demo

Run from the project root in Git Bash with Java 17 or later available:

```bash
java -version
./gradlew test bootJar

java -jar build/libs/fileadapter-0.1.0.jar \
  --ingestion.file=examples/enrollment.xml

cat var/preview/*/enrollment.xml.preview.json
cat var/preview/*/requests.jsonl
```

Compare the named preview's `source` metadata with its `requests` array and with the raw request objects. Repeat with `examples/enrollment.fw`, `examples/enrollment-noheader.psv`, or `examples/enrollment-array.json`. All use the same example employee request contract.

The first build may download Gradle, dependencies and a JDK 17 toolchain. The initial Java runtime must already be installed and available through `JAVA_HOME`/`Path`. Use your company's approved network and certificate configuration.

## Delivery and failure demo

Use a controlled mock or agreed POC service endpoint for LIVE mode. The application does not ship an always-running mock service. Tests create an in-process HTTP server.

```bash
export UPSERT_URL='http://localhost:8081/employees/upsert'

java -jar build/libs/fileadapter-0.1.0.jar \
  --ingestion.file=examples/enrollment.csv \
  --ingestion.mode=LIVE
```

Set `UPSERT_BEARER_TOKEN` through the environment if the endpoint requires bearer authentication. Enable `replay-safe` only when the service guarantees the agreed idempotency header. The deck explains why an uncertain timeout remains paused when replay is unsafe.

For a parsing-failure exercise, use a copy of an example with a malformed later record and a filename that still matches its profile. Inspect `var/errors/<run-id>/error.json`; whole-file preflight prevents HTTP delivery. The corresponding automated scenarios are in `LiveIntegrationTest`.

## Source navigation

Begin with [application.yml](../../src/main/resources/application.yml), then follow the adapter interface, a concrete parser, `SourceNormalizer`, `ConfigurableRequestMapper`, `RequestValidator`, `FileProcessor`, `ProcessingLedger`, `PreviewWriter`, and `HttpUpsertClient`.

Supporting guides: [architecture](../architecture.md), [Mermaid source](../architecture.mmd), [file formats](../formats.md), and [extensions](../extensions.md).

## Internal repository copy

Copy this directory into the internal `file-adapter-poc` repository when bringing the presentation into that separate clone. The deck contains the shared POC design and synthetic examples. Confirm the real source layout, API semantics, idempotency guarantee and operational ownership with the team.
