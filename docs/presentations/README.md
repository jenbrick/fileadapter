# CMS consent developer walkthrough

[Download the PowerPoint](File_Adapter_POC_Developer_Walkthrough.pptx)

A 31-slide walkthrough of the implemented CMS Interop consent POC. Allow about 45–60 minutes including the demos and contract discussion. Slides include presenter notes, editable tables/diagrams and links to source. This revision replaces the employee-focused walkthrough with the supplied member and consent use case. Generic employee examples remain supported in the application.

The deck covers the consent implementation and 99 passing tests as of 27 October 2026. The lookup facade and unsettled output rules are labelled assumptions pending the company API contracts. See [the consent guide](../consent.md) for the complete 30-field disposition and runnable commands.

## Agenda

| Slide | Topic |
| --- | --- |
| 1 | CMS Interop Consent |
| 2 | Consent processing flow |
| 3 | Source fields and request targets |
| 4 | One member, independent consent intents |
| 5 | Adapter contract |
| 6 | Supported file formats |
| 7 | Consent profile |
| 8 | Normalized member row |
| 9 | Member enrichment boundary |
| 10 | Lookup request and resolution |
| 11 | Provider clinical request |
| 12 | Payer-to-payer request |
| 13 | Coverage object |
| 14 | Transaction details |
| 15 | Consent validation rules |
| 16 | Whole-file preflight |
| 17 | Durable member plans |
| 18 | Run and request identity |
| 19 | Previews with request origins |
| 20 | HTTP delivery outcomes |
| 21 | Partial success across consent targets |
| 22 | Processing states and recovery |
| 23 | Code responsibilities |
| 24 | Configuration and Java changes |
| 25 | Git Bash consent preview |
| 26 | Multiple format demo |
| 27 | Local HTTP lookup and delivery demo |
| 28 | Partial-success recovery demo |
| 29 | Validation and operational boundaries |
| 30 | Contract decisions for the team |
| 31 | Code tour and demo references |

## Build and preview in Git Bash

```bash
java -version
./gradlew test bootJar
java -jar build/libs/fileadapter-0.1.0.jar \
  --spring.profiles.active=consent-demo \
  --ingestion.file=examples/consent/consent.csv
cat var-consent-demo/preview/*/consent.csv.preview.json
```

Five member rows produce six consent requests: provider and payer together, provider only, payer only, both granted, and a no-change row. Inspect the file/type metadata, body shapes, GUID insert/update behavior and `requestDetails` origins. The dependent member has a separately resolved manager. The demo profile uses synthetic fixtures and no HTTP calls. Normal consent PREVIEW can use HTTP member lookup, but never posts consent upserts.

Repeat with fixed width, XML, headerless pipe, JSON array or JSON Lines using the [multi-format commands](../consent.md#every-input-format). The single-object JSON example produces two requests for one member. Python is optional for fixture previews.

## HTTP and recovery exercises

Start `python scripts/mock_consent_services.py` in a separate terminal, then follow [the local HTTP demo](../consent.md#local-http-demo). It exercises the proposed lookup facade and consent POSTs through a loopback-only synthetic server. Omit the consent-demo profile for LIVE mode. The application refuses LIVE fixture lookup.

The failure exercise uses `--fail-once-target P2PDS`, a fresh work directory, replay-safe delivery and a one-attempt budget. The provider succeeds, payer pauses, and resume skips the provider while reusing the payer body/key. Keep the same mock process running because its idempotency memory is temporary. Configure replay safety for a real service only after its owners guarantee it.

A malformed or unresolved later member blocks every consent upsert from the file. The input may already have caused read-only lookups for earlier valid members. Automated tests cover this behavior and multiple error cases.

## Code navigation

Start with [application.yml](../../src/main/resources/application.yml), then `FileFormatAdapter`, `SourceNormalizer`, `ConsentRequestPlanner`, `MemberResolver`, `ConsentPlanStore`, `FileProcessor`, `ProcessingLedger`, `PreviewWriter` and `HttpUpsertClient`. Java sources are under `src/main/java/com/jenbrick/fileadapter`.

Supporting guides: [consent](../consent.md), [architecture](../architecture.md), [editable Mermaid](../architecture.mmd), [formats](../formats.md) and [extensions](../extensions.md). Synthetic inputs and expected bodies live in `examples/consent`. The mock and example generator live in `scripts`.

## Separate internal repository

Bring the updated source, examples and this presentation into the company's `file-adapter-poc` clone using its established Git workflow. The public GitHub origin and the internal remote are separate repositories. The Gradle artifact remains `build/libs/fileadapter-0.1.0.jar` regardless of the clone folder name.
