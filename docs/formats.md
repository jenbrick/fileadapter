# Input formats and runnable examples

## CMS consent formats

The [consent guide](consent.md#every-input-format) provides runnable examples for the supplied 30-field consent feed across all adapters. Headerless layouts preserve that order. Each format runs through the same consent planner and emits the same target-specific bodies. The employee layouts below remain generic examples.

Every format emits `SourceRecord(number, JsonNode)`, then uses the same logical-field normalization, configurable nested mapping, validation, durable staging, and HTTP POST delivery. The sample feeds use the employee request shape in `examples/expected-requests.jsonl`.

All profiles are active in `src/main/resources/application.yml`. YAML anchors share the employee mapping and output contract. Copy a mapping when a particular feed needs different business rules. Profiles are selected by filename; a one-shot command can override routing with `--ingestion.profile-id=...`.

## Run from Git Bash

```bash
git pull --ff-only
./gradlew test bootJar
```

Then choose any example:

```bash
java -jar build/libs/fileadapter-0.1.0.jar --ingestion.file=examples/enrollment.fw
java -jar build/libs/fileadapter-0.1.0.jar --ingestion.file=examples/enrollment.xml
java -jar build/libs/fileadapter-0.1.0.jar --ingestion.file=examples/enrollment-header.psv
java -jar build/libs/fileadapter-0.1.0.jar --ingestion.file=examples/enrollment-noheader.psv
java -jar build/libs/fileadapter-0.1.0.jar --ingestion.file=examples/enrollment-array.json
java -jar build/libs/fileadapter-0.1.0.jar --ingestion.file=examples/enrollment-object.json
java -jar build/libs/fileadapter-0.1.0.jar --ingestion.file=examples/enrollment.jsonl
```

Each command defaults to preview, with a source-labelled document at `var/preview/<run-id>/<original-filename>.preview.json` and raw request bodies at `var/preview/<run-id>/requests.jsonl`. The single-object JSON example produces one request; every other sample produces two. To POST, configure the service URL/authentication as described in the README and add `--ingestion.mode=LIVE`. LAN polling uses exactly the same profiles and parsers, with `.ready` markers.

| Input | Profile | Parser source |
| --- | --- | --- |
| `enrollment.csv` | `enrollment-csv-v1` | `DelimitedFileAdapter.java` |
| `enrollment.fw` | `enrollment-fixed-v1` | `FixedWidthFileAdapter.java` |
| `enrollment.xml` | `enrollment-xml-v1` | `XmlFileAdapter.java` |
| `enrollment-header.psv` | `enrollment-pipe-header-v1` | `DelimitedFileAdapter.java` |
| `enrollment-noheader.psv` | `enrollment-pipe-noheader-v1` | `DelimitedFileAdapter.java` |
| `enrollment-array.json` | `enrollment-json-array-v1` | `JsonFileAdapter.java` |
| `enrollment-object.json` | `enrollment-json-object-v1` | `JsonFileAdapter.java` |
| `enrollment.jsonl` | `enrollment-json-lines-v1` | `JsonFileAdapter.java` |

Parser sources are under `src/main/java/com/jenbrick/fileadapter/source/`.

## Identify the source of a preview

For `examples/enrollment.xml`, open `var/preview/<run-id>/enrollment.xml.preview.json`. The document has `previewVersion`, `mode`, a `source` object, and a `requests` array containing the actual mapped request bodies, and `requestDetails` associating each body with its source record number (and consent target for consent profiles). Existing preview files are backfilled only when missing.

The source object contains the original `fileName`, a readable `fileType` (CSV, PIPE_DELIMITED, TAB_DELIMITED, FIXED_WIDTH, XML, or JSON), the adapter `format`, `charset`, `profileId`, `runId`, `contentSha256`, and `requestCount`. Format-specific details identify headers/delimiters, fixed-width record length, XML record path, or JSON document mode.

For example, the XML source section looks like:

```json
{
  "fileName": "enrollment.xml",
  "fileType": "XML",
  "format": "XML",
  "charset": "UTF-8",
  "profileId": "enrollment-xml-v1",
  "runId": "<run-id>",
  "contentSha256": "<source-content-sha256>",
  "requestCount": 2,
  "recordPath": "/employees/employee"
}
```

Use Git Bash to find or display the labelled previews:

```bash
find var/preview -name '*.preview.json'
cat var/preview/*/*.preview.json
```

The metadata wrapper is only for human-readable previews. `requests.jsonl` and live HTTP POST bodies retain the original request shape. An existing PREVIEWED run gets its missing labelled document automatically on the next application startup, from saved payloads without reparsing or POSTing. Only completed preview runs are backfilled; LIVE runs do not produce preview artifacts. Identical content/profile/settings reuse the original run, so its source filename remains the name first registered for that run. Unsafe filename characters are replaced in the preview's filename; the full original name remains in `source.fileName`.

## Fixed-width without a header

Each physical line is one record. Offsets start at **zero** and lengths count Unicode code points, not bytes. Padding is preserved by the parser; `trim` in the shared mapping removes it from the request fields. The record must have exactly the declared number of characters, excluding its line terminator. Blank, short, and long lines are rejected. LF, CRLF, and CR line endings are supported.

The example has 106 characters per record:

| Field | Offset | Length |
| --- | ---: | ---: |
| employee_id | 0 | 8 |
| first_name | 8 | 12 |
| last_name | 20 | 16 |
| email | 36 | 28 |
| city | 64 | 14 |
| state | 78 | 2 |
| plan_code | 80 | 16 |
| effective_date | 96 | 10 |

The full active layout is in `application.yml`. A small layout would look like:

```yaml
layout:
  format: FIXED_WIDTH
  charset: UTF-8
  header-mode: NONE
  fixed-width:
    record-length: 20
    columns:
      - { name: employee_id, offset: 0, length: 8 }
      - { name: first_name, offset: 8, length: 12 }
```

`record-length` is optional; it defaults to the greatest field end offset. Gaps are allowed; overlapping ranges, duplicate names, negative offsets, and nonpositive lengths are rejected at startup. The maximum supported record length is 1,000,000 code points. Byte-oriented mainframe layouts, packed decimals, binary fields, and records without line separators require a separate agreed layout/parser.

## Pipe-delimited input

This uses the existing CSV-aware parser with `delimiter: "|"`, so quoted delimiters and multiline quoted values work. No separate pipe syntax parser is needed.

With headers:

```yaml
layout:
  format: DELIMITED
  charset: UTF-8
  delimiter: "|"
  header-mode: FIRST_RECORD
```

Without headers:

```yaml
layout:
  format: DELIMITED
  charset: UTF-8
  delimiter: "|"
  header-mode: NONE
  columns: [employee_id, first_name, last_name, email, city, state, plan_code, effective_date]
```

The first data record is retained in headerless mode. Missing/extra columns and duplicate/blank headers fail preflight.

## XML

The adapter uses the JDK's streaming StAX parser. It selects records by an absolute element path and materializes one selected record at a time:

```yaml
layout:
  format: XML
  charset: UTF-8
  xml:
    record-path: /employees/employee
  fields:
    employeeId: { column: "/@id" }
    firstName: { column: /person/firstName }
```

For this input:

```xml
<employees>
  <employee id="E1001">
    <person><firstName>Jordan</firstName></person>
  </employee>
</employees>
```

the raw source record is:

```json
{"@id":"E1001","person":{"firstName":"Jordan"}}
```

The logical-field normalizer resolves the attribute and nested value before your business mappings run.

XML representation conventions:

- Child elements become object properties; leaf values remain strings.
- Repeated sibling names become arrays, in source order. One occurrence stays scalar/object. Configure a custom transform if the target requires an array even for one occurrence.
- Attributes are prefixed with `@`.
- A leaf with attributes uses `#text` for its text content, such as `{"@unit":"kg","#text":"12"}`.
- Empty leaf elements become empty strings; `xsi:nil="true"` or `"1"` becomes JSON null. A nil element with children/nonblank text is rejected. Other attributes on a nil element are not retained.
- Whitespace in leaf values is preserved for the mapping layer to handle. Formatting whitespace between child elements is discarded.
- Mixed nonblank text and child elements is rejected because flattening would lose their order.

Namespaces use configured aliases, independent of the prefixes chosen by the input document:

```yaml
xml:
  record-path: /e:employees/e:employee
  namespaces: { e: "urn:employee" }
```

A corresponding child is exposed as `e:person`, and a source selector could be `/e:person/e:firstName`. Unprefixed selector segments match elements with **no namespace**, including when the document declares a default namespace elsewhere. Every URI used in the record path must have a configured alias. Unknown child/attribute namespaces use `{namespace-uri}localName` keys rather than silently dropping namespace identity. Namespace aliases must map to distinct URIs.

The path syntax supports named elements only, not general XPath, wildcards, predicates, or descendant selectors. The whole document is parsed, so malformed content after an otherwise valid record still fails preflight. DTDs, custom entities, and external resource resolution are disabled; ordinary XML escapes such as `&amp;` work. Limits are 128 nesting levels, 100,000 element/attribute nodes, and 1,000,000 text/attribute characters per selected record. The configured charset must agree with any XML encoding declaration.

## JSON

Set an explicit document mode:

```yaml
layout:
  format: JSON
  charset: UTF-8
  json: { mode: ARRAY }
  fields:
    employeeId: { column: employeeId }
    firstName: { column: /person/firstName }
```

| Mode | Input shape | Record boundary |
| --- | --- | --- |
| `OBJECT` | `{ "employeeId": "E1001", ... }` | The one root object |
| `ARRAY` | `[{ ... }, { ... }]` | Each root-array object |
| `LINES` | One compact object per physical line | Each nonblank line |

Nested objects, arrays, native booleans/numbers, and explicit nulls are preserved. Decimal numbers use BigDecimal during parsing to avoid binary floating-point rounding. Missing properties stay missing. Duplicate keys anywhere in a record, non-object records, truncated documents, and trailing documents/content are rejected. JSON Lines rejects multiple objects on one line or a record spanning multiple physical lines. Blank JSON Lines rows are ignored, and record numbers remain sequential.

Array mode streams one object at a time. A single object is held in memory; JSON Lines allows at most 1,000,000 UTF-16 characters per physical line. JSON parsing limits are 128 nesting levels, 1,000,000 characters per string, and 1,000 characters per number. There is no hard overall object-size limit in OBJECT/ARRAY mode, so size capacity should be considered for exceptionally large individual records. Empty arrays/documents with no records fail the file processor's existing no-data check.

A wrapper such as `{"employees":[...]}` is one object in OBJECT mode; it is not automatically expanded into multiple requests. Add an explicit envelope-record selection feature when that input contract is known.

## Nested input selectors and output arrays

`layout.fields.<logical-name>.column` and its aliases accept either a literal field name or an RFC 6901 JSON Pointer beginning with `/`. For example, `/person/contact/email`, `/dependents/0/name`, or `/weight/#text`. Escape `/` as `~1` and `~` as `~0` inside a key. A physical column named `a.b` remains a literal column name, not a nested lookup. Field-name matching remains case-sensitive.

To carry a complete input array into the request, normalize it as a logical field and map that field without scalar transforms:

```yaml
fields:
  children: { column: /dependents }
```

```yaml
mappings:
  - source: children
    target: dependents
contract:
  - path: dependents
    type: ARRAY
```

These snippets show sections of a profile, not a complete standalone configuration. Many source rows becoming one output entity still requires an explicit aggregation step; format parsing does not infer business relationships.

## Existing runs

The new format-specific layout settings are optional and omitted from snapshots when absent. This preserves the old CSV snapshot shape and its duplicate identity. Existing paused runs continue to use their saved layout, mapping, request payloads, and delivery settings. Every format retains whole-file preflight before any HTTP request is sent.
