# Send one complete contract or several complete contracts

The receiving service is still being designed. These are **two possible JSON formats for the POC**. We are not claiming the endpoint accepts them yet. Consent fields follow the [original JSON example](../examples/json-sample/CONSENT%20JSON%20ARRAY.txt).

## What each request contains

| Profile | HTTP body | Number of contracts |
| --- | --- | --- |
| `flexible-contract-single-v1` | `{ "contractId": "...", "consents": [...] }` | One complete contract |
| `flexible-contract-array-v1` | `[ { "contractId": "...", "consents": [...] }, ... ]` | Up to `CONTRACTS_PER_REQUEST`, default 2 |

Each consent identifies its member through `consentOwner`: contract, PIN and member GUID. Several consents can belong to the same member. These examples do not have a separate `members` array. If the service wants one, change the YAML nesting/grouping.

`contractId` is the **internal contract returned by member lookup**. External IDs and demographics are used to find the member. The CSV repeats an external contract ID across several members. Fixtures distinguish them using that ID, first/last name and date of birth, with group context. The real integration must follow the company’s matching rules.

Rows for a contract can appear anywhere in the file. The batcher gathers them before building JSON. Contracts follow first appearance in the file, and rows within each contract keep their input order. Members and payer coverages for a contract stay in one request. External IDs resolving to the same internal contract are deliberately combined. To group differently, change `batching.group-by` and the root contract field together.

## Run both examples

From the repository root in Git Bash, build first:

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

Set limits before starting; no rebuild is needed:

```bash
export CONTRACTS_PER_REQUEST=2
export CONTRACT_MAX_MEMBERS=25
export CONTRACT_MAX_ROWS=100
```

`CONTRACTS_PER_REQUEST` affects only the array profile. Single-contract mode always uses one. Row/member limits apply to both. The member limit must not exceed the row limit, and the row limit must not exceed the profile’s `max-records` (1,000 here).

With defaults, an array request can contain at most two contracts, 50 distinct members and 200 source rows. These settings count items; they do not check the JSON’s byte size.

Find outputs in `var-flexible-demo/preview/<run-id>/`. `contract-batches.csv.preview.json` has a `requests` array. Each line of `requests.jsonl` is one complete prospective HTTP body. Changing profiles or limits creates a different saved configuration and run ID.

## What the sample produces

The [CSV](../examples/flexible/contract-batches.csv) has eight records, three external contracts and five members. Rows for a contract are deliberately scattered.

| Internal contract | External contract | Member GUID suffixes | Consent entries | Request in single / array mode |
| --- | --- | --- | --- | --- |
| 123456789 | EXT-CONTRACT-A | 001, 002 | 2 PCDS + 2 P2PDS | 1 / 1 |
| 222222222 | EXT-CONTRACT-B | 003, 004 | 1 PCDS + 2 P2PDS | 2 / 1 |
| 333333333 | EXT-CONTRACT-C | 005 | 1 PCDS + 1 P2PDS | 3 / 2 |

The full GUIDs are `MEMBER-GUID-001` through `MEMBER-GUID-005`.

Compare with:

- [Single-contract expected requests](../examples/flexible/expected-contract-single-requests.json): three bodies, each an object.
- [Array expected requests](../examples/flexible/expected-contract-array-requests.json): two bodies, each an array, containing two contracts then one.

The outer array in either expected file is the verifier’s list of requests. Do not send that extra list as an HTTP wrapper. Both `bash scripts/run-examples.sh` and `python scripts/verify-examples.py` include these cases.

## A member can have several payer consents and coverages

`PAYER_CONSENT_KEY` distinguishes separate payer consents **for one member** in these profiles. It is a made-up POC field, not an agreed production feed/API field.

For John, `MEMBER-GUID-002`:

- Rows 2 and 4 use key `P2P-A`, GRANT and different payers/policies. They produce one P2PDS consent with two coverages.
- Row 7 uses `P2P-B`, RVOKE and another payer/policy. It produces a separate payer consent for John.
- Repeated provider information produces one PCDS consent.

Payer consents group by `[resolved.owner.contractMemberGuid, PAYER_CONSENT_KEY]`. Coverages inside them group by `[PAYER_ID, PAYER_POLICY_ID]`. Provider consents group by member GUID. Conflicting statuses, dates or manager values in one consent group are rejected. The engine does not just take the last row.

The key is required when a payer preference is present, but is not emitted in JSON. A real feed should use its agreed consent identifier or distinguishing value. Do not invent a consent identity from row number or payer ID unless that is the agreed business meaning. Older examples still show one payer consent per member with several coverages.

The member lookup gets demographics/identifiers and the internal group/customer values from the group service. It does not get `PAYER_CONSENT_KEY` or payer/consent fields. Demo consent GUIDs are null for inserts. An existing GUID for each consent needs a separate lookup after member resolution; a grouping key alone cannot establish it. See [lookup details](group-member-lookups-v2.md).

We still need the service team to confirm multiple payer consents, mixed GRANT/RVOKE changes and whether coverage is merged or replaced. The grouping and JSON structure can change with those decisions.

## How the settings work

Use `GROUPED` mode:

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

`where` removes no-change rows before reading grouping keys. Those rows need no resolved identity, but still count toward the file limit. Grouping keys must be present scalar values. Member limits count distinct resolved members, not coverage rows. Every complete contract must fit its row/member limits. The last request is sent even if it has fewer contracts than the maximum.

For one contract, use `max-groups-per-request: 1` and a root object reading `contractId` from `resolved.owner.contract`. For several, put that object inside a root collection grouped by the same contract. See the [full YAML](../src/main/resources/application-flexible-demo.yml).

`GROUPED` decides which records go into each **request**. Nested template `group-by` decides which records form **collections inside the JSON**. Those are separate jobs. Different field names, nesting or an explicit member array can be configured without changing Java.

These examples use CSV, but the batcher works on normalized/enriched `SourceRecord` objects from any adapter. Another layout used with these templates must also provide the consent key. The original 11 format examples remain available.

## Preparation, limits and restart

`FileProcessor` prepares a supported-size file before sending. `ProjectionEngine.project()` filters eligible rows, calculates grouping keys and asks [RequestBatcher](../src/main/java/com/jenbrick/fileadapter/projection/RequestBatcher.java) to keep complete groups together. It builds each resulting request separately.

By default, a limit failure or conflicting grouped value anywhere stops all POSTs for the file. A profile can choose MEDIUM handling to reject only the affected contract. Broken file syntax and missing grouping identities remain HARD. See [error rules](error-handling-v2.md).

Sending smaller requests does not mean we can process unlimited files. The file must fit `max-records`, and enriched rows/request bodies are still held in memory during preparation. Huge unsorted files would need disk-backed grouping and a separately designed recovery approach.

An oversized contract is rejected rather than split. With MEDIUM projection handling, other contracts continue; default HARD handling stops the file. Choose limits that fit the service. Maximum contributing rows per request is `max-groups-per-request * max-records-per-group`, also limited by the file cap. Checking serialized bytes would be additional work.

Each request saves its contributing record numbers. Single-contract examples use `[1,2,4,7]`, `[3,8,5]` and `[6]`; arrays use `[1,2,4,7,3,8,5]` and `[6]`. These lists record first use, so they are not necessarily sorted.

Keys are `<run-id>:<request-number>`. If request 1 succeeds and request 2 fails, resume skips 1 and handles the saved body for 2 under the saved retry rules. It does not regroup remaining records using new settings.

By default, HTTP status determines acknowledgement. Optional `SCOPED_V1` responses can identify rejected contracts/members, but do not define all-or-nothing batches or individual coverage failures. Duplicate-safe replay is disabled until the service guarantees it.

The [runtime diagrams](runtime-diagrams-v2.md) and [CSV walkthrough](csv-debug-walkthrough-v2.md) follow the code. `flexible-csv-v1` still demonstrates whole-file mapping; use the new profiles to show requests containing a limited number of complete contracts.

## Return the original failed records

Customer results are separate from consent JSON. A rejected contract returns its original member/coverage rows with code/remarks. A reliably identified rejected member returns only that member’s submitted rows, including repeated payer-consent rows. The [result guide](error-handling-v2.md) explains the same-format files, IT report and ready manifests.

## Reuse group information within a run

Active rows contain `EXTERNAL_GROUP_ID`. `group-context` runs before `consent-member` and supplies the internal group number/customer ID for demographics lookup. That does not change contract grouping or the JSON format.

The sample uses EXT-GROUP-1/2/3. Members in the same group share a saved group result within the run. Customer returns retain the original external group ID, with code/remarks added.
