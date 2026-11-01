# Input formats you can run

> V2: plain-English copy. [Original for comparison](formats.md). Commands, configuration examples and diagrams are unchanged.
All examples use [the demo YAML](../src/main/resources/application-flexible-demo.yml), flexible JSON mapping and synthetic group/member results. From the repository root in Git Bash:

```bash
./gradlew test bootJar
bash scripts/run-examples.sh
```

The script requires Java and a built JAR, then runs PREVIEW. These filenames automatically choose the correct feed profile. Contract-batching variants are selected explicitly later in the script.

| Input | Profile | Expected body |
| --- | --- | --- |
| [CSV with header](../examples/flexible/flexible-consents.csv) | `flexible-csv-v1` | `expected-envelope.json` |
| [CSV without header](../examples/flexible/flexible-noheader.csv) | `flexible-csv-noheader-v1` | `expected-envelope.json` |
| [Pipe with header](../examples/flexible/flexible-consents.psv) | `flexible-pipe-v1` | `expected-envelope.json` |
| [Pipe without header](../examples/flexible/flexible-noheader.psv) | `flexible-psv-noheader-v1` | `expected-envelope.json` |
| [Semicolon](../examples/flexible/flexible-consents.scsv) | `flexible-semicolon-v1` | `expected-envelope.json` |
| [Tab](../examples/flexible/flexible-consents.tsv) | `flexible-tab-v1` | `expected-envelope.json` |
| [Fixed width](../examples/flexible/flexible-consents.fw) | `flexible-fixed-v1` | `expected-envelope.json` |
| [XML](../examples/flexible/flexible-consents.xml) | `flexible-xml-v1` | `expected-envelope.json` |
| [JSON array](../examples/flexible/flexible-consents.json) | `flexible-json-v1` | `expected-envelope.json` |
| [JSON object](../examples/flexible/flexible-object.json) | `flexible-json-object-v1` | `expected-object-envelope.json` |
| [JSON Lines](../examples/flexible/flexible-consents.jsonl) | `flexible-json-lines-v1` | `expected-envelope.json` |

Expected files are in [examples/flexible](../examples/flexible). Most contain the same three records and produce the same request. The JSON object example has only the first member and produces one provider consent.

Run one file by substituting its name:

```bash
java -jar build/libs/fileadapter-0.1.0.jar \
  --spring.profiles.active=flexible-demo \
  --ingestion.mode=PREVIEW \
  --ingestion.file=examples/flexible/flexible-noheader.csv
```

Find `<input-file>.preview.json` and `requests.jsonl` under `var-flexible-demo/preview/<run-id>/`. The preview includes source/type/profile information. Each JSONL line is one complete prospective HTTP body. Identical input/settings can reuse a completed preview.

## What each reader accepts

| Reader | Rules |
| --- | --- |
| Delimited | One configured delimiter, standard double-quote escaping, UTF-8 and optional BOM. Quoted delimiters/newlines work. Duplicate/blank headers, wrong column counts or bad encoding fail. |
| Headerless CSV/pipe | All 34 field names are configured in physical order. Update `layout.columns` when order changes. |
| Fixed width | 34 fields of 32 Unicode code points: 1,088 per record. Offsets start at zero. Padding remains until explicitly trimmed; wrong lengths fail. |
| XML | Select `/members/member`. Attributes use `@name`, repeats become arrays, empty elements are empty strings and `xsi:nil` is null. Match namespaces by configured URI. Reject DTDs/entities and mixed content. |
| JSON | ARRAY reads root-array objects; OBJECT reads one root object; LINES reads one object per nonblank line. Reject duplicate keys, non-object records and trailing documents. A wrapper does not automatically expand its children into records. |
| Normalization | JSON Pointers can select nested values. Configured fields replace the logical record; they are not just additions to it. |

## Different JSON from the same input

The runner selects these profiles against `flexible-consents.csv`:

| Profile | Expected output | Difference |
| --- | --- | --- |
| `flexible-root-array-v1` | `expected-root-array.json` | Whole body is an array |
| `flexible-contract-envelope-v1` | `expected-contract-envelope.json` | Adds root `contractId` |
| `flexible-reshaped-v1` | `expected-reshaped.json` | Moves wrapper, manager and coverage |
| `flexible-record-v1` | Three separate person objects | One request per row, no lookup |

With Python 3, compare actual JSON with expected files:

```bash
python scripts/verify-examples.py
```

The verifier runs the JAR in temporary work folders and compares parsed JSON, so formatting differences do not matter. `JAVA` can select a Java executable; `FILEADAPTER_JAR` can select a different built JAR. Python is optional for running the application itself.

## Complete-contract examples

[contract-batches.csv](../examples/flexible/contract-batches.csv) has eight scattered rows for three contracts/five members. `PAYER_CONSENT_KEY` distinguishes separate payer consents for one member. Repeated keys can gather several coverages.

| Profile | Bodies | Expected file |
| --- | --- | --- |
| `flexible-contract-single-v1` | Three objects, one contract per request | `expected-contract-single-requests.json` |
| `flexible-contract-array-v1` | Two arrays containing two then one contracts by default | `expected-contract-array-requests.json` |

Each expected file lists all request bodies; older expected files contain one body. Select `--ingestion.profile-id=<profile>` and `--ingestion.file=examples/flexible/contract-batches.csv`. See [limits and JSON examples](contract-batching-v2.md).

The runners cover 17 input/mapping cases plus three error cases. Older format examples do not need the new payer-consent key.

## Customer results use the input format

Tests write and read back customer results for all 11 layouts. Original values are retained with `RESULT_CODE` / `RESULT_REMARKS`. Headerless stays headerless; fixed width appends 16/160-character fields; XML adds elements; JSON adds properties. See [result details](error-handling-v2.md).

`bash scripts/run-error-examples.sh` runs `error-demo-hard-v1`, `error-demo-medium-v1` and `error-demo-soft-v1`. Expected files are `expected-customer-hard.csv`, `expected-customer-medium.csv` and `expected-customer-soft.csv`.

The Python verifier checks **20 packaged cases**, including those returns and IT manifests. HARD intentionally exits 1. `error-demo-service-v1` is a separate profile with approved codes for compatible LIVE member/contract-response tests.

## Group information is in every format

All 11 layouts supply `EXTERNAL_GROUP_ID`. Consent profiles run `group-context` before `consent-member`, using fixtures in PREVIEW. The per-record person example still has no lookup. Expected consent JSON is unchanged; customer results include original external group IDs. See [lookup fields and order](group-member-lookups-v2.md).
