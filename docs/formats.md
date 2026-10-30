# Runnable input formats

All examples use the flexible projection flow and synthetic lookup fixtures in [application-flexible-demo.yml](../src/main/resources/application-flexible-demo.yml). From the repository root in Git Bash:

```bash
./gradlew test bootJar
bash scripts/run-examples.sh
```

The runner forces PREVIEW. It requires Java and an already built JAR. Every input below is automatically routed by filename, with no explicit feed ID required.

| Input | Feed profile | Expected request body |
| --- | --- | --- |
| [CSV with header](../examples/flexible/flexible-consents.csv) | `flexible-csv-v1` | `expected-envelope.json` |
| [CSV without header](../examples/flexible/flexible-noheader.csv) | `flexible-csv-noheader-v1` | `expected-envelope.json` |
| [Pipe with header](../examples/flexible/flexible-consents.psv) | `flexible-pipe-v1` | `expected-envelope.json` |
| [Pipe without header](../examples/flexible/flexible-noheader.psv) | `flexible-psv-noheader-v1` | `expected-envelope.json` |
| [Semicolon with header](../examples/flexible/flexible-consents.scsv) | `flexible-semicolon-v1` | `expected-envelope.json` |
| [Tab with header](../examples/flexible/flexible-consents.tsv) | `flexible-tab-v1` | `expected-envelope.json` |
| [Fixed width without header](../examples/flexible/flexible-consents.fw) | `flexible-fixed-v1` | `expected-envelope.json` |
| [XML](../examples/flexible/flexible-consents.xml) | `flexible-xml-v1` | `expected-envelope.json` |
| [JSON root array](../examples/flexible/flexible-consents.json) | `flexible-json-v1` | `expected-envelope.json` |
| [JSON root object](../examples/flexible/flexible-object.json) | `flexible-json-object-v1` | `expected-object-envelope.json` |
| [JSON Lines](../examples/flexible/flexible-consents.jsonl) | `flexible-json-lines-v1` | `expected-envelope.json` |

Expected files are in [examples/flexible](../examples/flexible). Most inputs contain the same three source records, so they produce the same one-request envelope. The single JSON object represents the first member only and produces one provider consent. It is intentionally a smaller example.

Run any one input by substituting its filename:

```bash
java -jar build/libs/fileadapter-0.1.0.jar \
  --spring.profiles.active=flexible-demo \
  --ingestion.mode=PREVIEW \
  --ingestion.file=examples/flexible/flexible-noheader.csv
```

Output is `var-flexible-demo/preview/<run-id>/<input-file>.preview.json` plus `requests.jsonl`. The first has file/type/profile metadata. Each JSONL line is the exact body of one prospective HTTP request. Existing completed previews may be reused for identical input and configuration.

## Format behavior

- Delimited input uses a single configured delimiter, standard CSV double-quote escaping, UTF-8 and optional BOM. Quoted delimiters/newlines are supported. Duplicate/blank headers, incorrect column counts and invalid encoding fail.
- Headerless CSV/pipe layouts name all 34 fields in order. A change in physical order requires updating `layout.columns`.
- Fixed width uses 34 fields of 32 characters, total 1,088 Unicode code points per record. Offsets are zero-based; padding remains available until a mapping trims it. Incorrect lengths fail.
- XML selects `/members/member`. Attributes have `@name` keys, repeated elements become arrays, empty elements are empty strings, and `xsi:nil` represents null. Namespace matching uses configured URIs. DTDs/entities and mixed-content structures are rejected.
- JSON `ARRAY` emits one source record per root-array object. `OBJECT` emits the one root object. `LINES` emits one object per nonblank line. Duplicate keys, non-object records and trailing documents fail. An object wrapper does not implicitly expand its children into records.
- Source normalization can select nested values using JSON Pointers. Its configured fields form the full logical record, rather than a partial overlay.

## Output variants using the same input

The runner also exercises these explicit profile selections against `flexible-consents.csv`:

| Profile | Expected JSON | Change |
| --- | --- | --- |
| `flexible-root-array-v1` | `expected-root-array.json` | Root becomes an array |
| `flexible-contract-envelope-v1` | `expected-contract-envelope.json` | Adds root `contractId` |
| `flexible-reshaped-v1` | `expected-reshaped.json` | Moves wrapper, manager and coverage |
| `flexible-record-v1` | Three independent person objects | RECORD scope, no lookup |

If Python 3 is available, validate actual packaged outputs against expected JSON:

```bash
python scripts/verify-examples.py
```

This creates temporary work directories, runs the JAR for each case and compares parsed JSON bodies. `JAVA` may name a Java executable; `FILEADAPTER_JAR` may name another built JAR. This verifier is optional; Java/Gradle and the Bash runner are sufficient to run the application.
