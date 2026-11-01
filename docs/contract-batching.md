# Complete-contract requests: two configurable JSON shapes

The receiving API is still being designed. These are **two POC candidate shapes**, using the existing consent-entry structure. They do not assert that the endpoint already accepts either new wrapper. The [original submitted JSON](../examples/json-sample/CONSENT%20JSON%20ARRAY.txt) remains the source example for consent fields.

## Request boundaries and JSON shapes

| Demo profile | HTTP body | Contracts per request |
| --- | --- | --- |
| `flexible-contract-single-v1` | `{ "contractId": "...", "consents": [...] }` | Exactly one complete contract |
| `flexible-contract-array-v1` | `[ { "contractId": "...", "consents": [...] }, ... ]` | Up to `CONTRACTS_PER_REQUEST` (default 2) |

Members are represented by each consent entry’s `consentOwner` (contract, PIN and member GUID), as in the original sample. There is no additional `members` wrapper in these fixtures. Multiple consents can have the same member owner. Adding an explicit `members` array is another YAML nesting/grouping change if that becomes the agreed API shape.

`contractId` is the **resolved internal contract**. External IDs and member demographics are lookup inputs. The new CSV deliberately repeats one external contract ID for several members; fixture lookup uses the external ID plus first name, last name and date of birth, rather than assuming the external contract uniquely identifies a member. A real facade must implement the company’s member-matching rules.

A contract’s rows may be interleaved anywhere in the file. The batcher gathers all of them before rendering. Contract order is first appearance in the input; order within each contract is the original row order. It never splits one contract’s members or payer coverages across requests. Different external identifiers that resolve to the same internal contract are intentionally combined. To use another boundary, change `batching.group-by` and the root contract mapping together.

## Run both demos

Build from the repository root in Git Bash:

```bash
./gradlew test bootJar
```

One contract per request:

```bash
java -jar build/libs/fileadapter-0.1.0.jar \
  --spring.profiles.active=flexible-demo \
  --ingestion.profile-id=flexible-contract-single-v1 \
  --ingestion.file=examples/flexible/contract-batches.csv
```

Up to two contracts per request:

```bash
java -jar build/libs/fileadapter-0.1.0.jar \
  --spring.profiles.active=flexible-demo \
  --ingestion.profile-id=flexible-contract-array-v1 \
  --ingestion.file=examples/flexible/contract-batches.csv
```

To change limits without rebuilding, set these environment variables before starting:

```bash
export CONTRACTS_PER_REQUEST=2
export CONTRACT_MAX_MEMBERS=25
export CONTRACT_MAX_ROWS=100
```

`CONTRACTS_PER_REQUEST` affects the array profile only. The single-contract profile always uses 1. Member and row limits apply to both. `CONTRACT_MAX_MEMBERS` must be no greater than `CONTRACT_MAX_ROWS`, which must be no greater than the profile’s `max-records` (1000 in the demos). Default array requests therefore contain at most 2 contracts, 50 distinct members and 200 source rows. These are count bounds, not a serialized JSON byte-size limit.

Outputs appear under `var-flexible-demo/preview/<run-id>/`. `contract-batches.csv.preview.json` contains a `requests` array; each line in `requests.jsonl` is one complete HTTP body. Distinct profile/limit settings produce distinct run snapshots and IDs.

The [CSV](../examples/flexible/contract-batches.csv) has 8 records, 3 external contracts and 5 resolved members, with nonadjacent rows for the same contract:

| Internal contract | External contract | Member GUIDs | Consent entries | Request in single / array mode |
| --- | --- | --- | --- | --- |
| 123456789 | EXT-CONTRACT-A | 001, 002 | 2 PCDS + 2 P2PDS | 1 / 1 |
| 222222222 | EXT-CONTRACT-B | 003, 004 | 1 PCDS + 2 P2PDS | 2 / 1 |
| 333333333 | EXT-CONTRACT-C | 005 | 1 PCDS + 1 P2PDS | 3 / 2 |

GUIDs in the table abbreviate `MEMBER-GUID-001` through `MEMBER-GUID-005`.

Compare all generated request bodies with:

- [expected-contract-single-requests.json](../examples/flexible/expected-contract-single-requests.json): array of **three separate HTTP bodies** for verification. Each actual body is an object.
- [expected-contract-array-requests.json](../examples/flexible/expected-contract-array-requests.json): array of **two separate HTTP bodies** for verification. Each actual body is itself an array, with two contracts then one contract.

The outer array in each expected fixture is the verifier’s list of requests. It is not an extra HTTP wrapper. `bash scripts/run-examples.sh` and `python scripts/verify-examples.py` include both flavors alongside all existing input formats.

## Multiple payer consents and payer coverages for a member

The new profiles add an explicit source field, `PAYER_CONSENT_KEY`, to distinguish payer-consent identities **within a member**. This is a synthetic POC field, not an assumption about the final incoming feed or receiving API.

For John (`MEMBER-GUID-002`):

- Rows 2 and 4 have key `P2P-A`, status GRANT and different payers/policies. They become one P2PDS entry containing two coverage objects.
- Row 7 has key `P2P-B`, status RVOKE and another payer/policy. It becomes a separate P2PDS entry for the same member.
- The repeated provider data produces one PCDS entry for John.

The payer collection groups by `[resolved.owner.contractMemberGuid, PAYER_CONSENT_KEY]`. Inside that consent, coverage groups by `[PAYER_ID, PAYER_POLICY_ID]`. Provider entries group only by member GUID. Different statuses/dates/manager values within a single consent group are rejected; the engine does not choose the last row.

`PAYER_CONSENT_KEY` is a lookup/grouping input and is not emitted in the JSON. With these profiles it must be populated whenever a payer preference is present. In another feed, use its true consent identifier or a configured discriminator. Do not generate separate consent identities from a row number or each coverage’s payer ID unless that is the agreed business model. The original profiles continue to demonstrate one payer consent per member with many coverages.

The optional enricher forwards the key in HTTP lookup criteria. A real facade must return `payerConsentGuid` for **that member and that consent key** (and a consistent provider GUID for repeated member rows). The demo fixtures return null to illustrate inserts. Merely changing the grouping key cannot establish the correct identity of an existing consent; do not assume the old member-only lookup uniquely resolves multiple payer consents.

Whether the final service allows multiple P2PDS entries for a member, merges/replaces coverages, and accepts mixed GRANT/RVOKE entries remains a contract decision. Both the grouping and output tree are configurable so the POC can change with that decision.

## How the configuration controls batching

The new generic scope is `GROUPED`:

```yaml
scope: GROUPED
max-records: 1000
batching:
  group-by: [resolved.owner.contract]
  max-groups-per-request: 2
  max-records-per-group: 100
  member-key: resolved.owner.contractMemberGuid
  max-members-per-group: 25
  where:
    any-present:
      - PROVIDER_CLINICAL_CONSENT_PREFERENCE
      - PAYER_CONSENT_PREFERENCE
```

`where` filters rows with no requested changes before extracting keys, so those rows need no resolved identity. They still count toward the file safety limit. Grouping keys must be present scalars. Member limits count distinct resolved member keys, not payer/coverage rows. Each whole contract must satisfy the row and member caps. The final partially filled request is emitted; there is no padding or dropped remainder.

For the single shape, use `max-groups-per-request: 1` and a root object whose `contractId` reads `resolved.owner.contract`. For the array shape, wrap that same object in a root collection grouped by `resolved.owner.contract`. The full templates are in [application-flexible-demo.yml](../src/main/resources/application-flexible-demo.yml).

`GROUPED` partitions **requests**; nested template `group-by` constructs **JSON collections** inside those requests. They are separate operations. Templates remain generic and can be changed without Java when the endpoint adopts an explicit member wrapper, different names or nesting.

These profiles use CSV, but the batcher consumes normalized/enriched `SourceRecord` objects. The same projection can be paired with the other existing adapters. Their original 11 runnable layouts are retained; the new consent key must also be present in any layout used for these multi-consent templates.

## Processing, limits and recovery

`FileProcessor` still validates/enriches a bounded file before delivery. `ProjectionEngine.project()` filters the batch population, computes grouping keys and delegates complete-group packing to [RequestBatcher](../src/main/java/com/jenbrick/fileadapter/projection/RequestBatcher.java). It then renders each partition independently. By default, limits or grouped-value conflicts anywhere in preparation block **all** POSTs for that file. Profiles can opt into MEDIUM contract isolation through [error handling](error-handling.md); a rejected contract is then omitted while other contracts proceed. File syntax corruption and missing grouping identity remain HARD.

This change removes the requirement to send an entire file as one HTTP body. It does **not** implement unlimited-file streaming or disk-based aggregation. The file still fits within `max-records`, and preparation retains enriched rows and rendered requests in memory. Supporting arbitrarily large, unsorted files would require a disk-backed grouping strategy and a separately designed preparation/recovery policy.

Oversized individual contracts fail preflight rather than being split. With MEDIUM projection handling, that contract is rejected and other contracts continue; the original profiles keep HARD file handling. Change the caps only with the receiving service’s limits in mind. A request’s worst-case source-row count is bounded by `max-groups-per-request * max-records-per-group`, and the file limit also applies. An endpoint payload-byte cap would require an additional serialized-size check.

Each rendered request is staged separately with all contributing CSV record numbers. In single mode the origins are `[1,2,4,7]`, `[3,8,5]` and `[6]`; array mode uses `[1,2,4,7,3,8,5]` and `[6]`. These are first-use provenance lists, not necessarily sorted numbers.

Delivery uses stable `<run-id>:<request-number>` keys. If request 1 is acknowledged and request 2 fails, resume skips request 1 and retries the saved complete body for request 2 under the saved replay policy. It does not regroup the remaining members using newly edited settings. Acknowledgement defaults to HTTP-status based. The new opt-in SCOPED_V1 response contract supports member/contract rejections using source identity correlation; it does not establish batch atomicity or per-coverage failure semantics. Replay safety remains disabled by default until the service guarantees it.

Use the [updated diagrams](runtime-diagrams.md) and the [CSV debugger walkthrough](csv-debug-walkthrough.md) to follow the implementation. The original whole-file sample remains available as `flexible-csv-v1`; it is useful for comparison, but use the new profiles to demonstrate bounded contract delivery.

## Return failed source records

The [error/result guide](error-handling.md) adds customer-facing original-record returns in each incoming format and comprehensive internal diagnostics. A contract rejection returns its original member/coverage rows with code/remarks appended; a reliably identified service member rejection returns only that member’s submitted source rows. A member’s multiple payer consents still map back to that member’s rows. Result files are separate from the consent request JSON and are handed off through outbox manifests.
