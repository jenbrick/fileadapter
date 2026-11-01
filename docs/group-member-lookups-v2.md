# Look up the group first, then the member

For each record that requests a consent change:

1. Read the record and put its fields into a consistent form.
2. Send `EXTERNAL_GROUP_ID` to the group service.
3. Save the returned `internalGroupNumber` and `customerId`.
4. Send member demographics/identifiers and those group values to the member service.
5. Get the internal contract number, PIN and contract member GUID.
6. Build the consent JSON, then preview or send it.

Neither lookup gets the consent upsert JSON. The member lookup also excludes payer/coverage fields, preferences, consent targets, provider/payer lookup flags, audit columns and unrelated input fields.

We intend to use the existing company services. Their exact HTTP methods, paths, authentication and response formats have not been supplied. The included HTTP clients therefore use small **POC service formats** described below. Adapt `GroupResolver` and `MemberResolver` to the actual services. The file readers, JSON templates and customer results should not need to change for that integration.

## Follow one CSV record

Here is a shortened input record:

```csv
EXTERNAL_GROUP_ID,EXTERNAL_CONTRACT_ID,RELATIONSHIP_CODE,FIRST_NAME,LAST_NAME,DATE_OF_BIRTH
EXT-GROUP-1,EXTERNAL-002,SELF,John,Smith,1980-01-01
```

The [full CSV](../examples/flexible/flexible-consents.csv) also has consent and payer fields. They stay in the processing flow and are used later to build JSON. They are not sent as member-demographics properties.

`GroupEnricher` trims the external group ID. The POC HTTP client POSTs only this:

```json
{"externalGroupId":"EXT-GROUP-1"}
```

It expects HTTP 200 with both required group values:

```json
{"internalGroupNumber":"10001","customerId":"CUST-1"}
```

The enricher adds those values to the working record:

```json
{
  "resolvedGroup": {
    "internalGroupNumber": "10001",
    "customerId": "CUST-1"
  }
}
```

`ConsentMemberEnricher` requires that information before calling the member service. It creates `MemberLookupPayload`. This object is the **entire request body**, with no `criteria` or consent wrapper:

```json
{
  "externalContractId": "EXTERNAL-002",
  "relationshipCode": "SELF",
  "firstName": "John",
  "middleName": "",
  "lastName": "Smith",
  "genderCode": "M",
  "dateOfBirth": "1980-01-01",
  "internalGroupNumber": "10001",
  "customerId": "CUST-1"
}
```

Only these nine properties are allowed. Strings are trimmed, and date of birth is converted using the configured input date pattern. Internal group number/customer ID come from the group service, not input fields that happen to use those names. The external group ID has already been used and is not sent as another group selector.

The POC member client expects HTTP 200 with a result such as:

```json
{
  "owner": {
    "contract": "123456789",
    "pin": "002",
    "contractMemberGuid": "MEMBER-GUID-002"
  },
  "manager": {
    "contract": "123456789",
    "pin": "002",
    "contractMemberGuid": "MEMBER-GUID-002"
  }
}
```

The resolver adapts the real company response into this form; we are not assuming the company service already uses these field names. The code requires a nonblank internal contract and member GUID, plus a three-character PIN.

It adds `resolved.owner`, `resolved.manager`, optional legacy consent GUIDs and a timestamp to the working record. The original normalized values and `resolvedGroup` stay available. The outgoing mapping decides where these values go in the consent JSON.

The internal **group number** is not the internal **member contract number**. Group number/customer ID help find the member. The member result supplies the contract and member identity used in `consentOwner` and request grouping.

## Configure the call order

Every consent demo uses:

```yaml
projection:
  enrichers:
    - group-context
    - consent-member
```

Startup fails if `consent-member` is configured without `group-context` before it. The simple per-record person example does not use lookups. All format fixtures now contain `EXTERNAL_GROUP_ID`, including headerless, fixed-width, XML, JSON and JSON Lines. Their expected consent JSON stays the same.

Group settings are separate:

```yaml
group:
  version: group-lookup-v1
  source-field: EXTERNAL_GROUP_ID
  lookup:
    mode: FIXTURE
    endpoint: http://127.0.0.1:8083/groups/resolve
    connect-timeout: 5s
    read-timeout: 10s
    fixtures:
      - external-group-id: EXT-GROUP-1
        result:
          internal-group-number: '10001'
          customer-id: CUST-1
```

Member settings remain under `profile.consent` to stay compatible with the existing profile layout. Fixture matching uses the member payload’s **lower-camel-case** names, including the resolved group/customer values:

```yaml
consent:
  version: flexible-lookup-v1
  date-pattern: uuuu-MM-dd
  lookup:
    mode: FIXTURE
    fixtures:
      - match:
          externalContractId: EXTERNAL-002
          internalGroupNumber: '10001'
          customerId: CUST-1
        result:
          owner:
            contract: '123456789'
            pin: '002'
            contractMemberGuid: MEMBER-GUID-002
          manager:
            contract: '123456789'
            pin: '002'
            contractMemberGuid: MEMBER-GUID-002
```

These are fragments. Use the full checked-in profile for endpoints, timeouts, fixtures and JSON mapping. The contract example uses names/date of birth as well as external contract and group context to distinguish members sharing one contract. The real service must follow the company’s rules and return one unambiguous match.

`provider-target` and `payer-target` were removed from lookup settings. Consent targets belong in the output mapping. Old saved settings can still be read when recovering already prepared requests.

## Run with fixtures or HTTP services

This preview needs no company services:

```bash
./gradlew test bootJar
java -jar build/libs/fileadapter-0.1.0.jar \
  --spring.profiles.active=flexible-demo \
  --ingestion.file=examples/flexible/flexible-consents.csv
```

Both lookups default to `FIXTURE`. Group fixtures map EXT-GROUP-1/2/3 to 10001/20002/30003 and CUST-1/2/3. They are synthetic. LIVE refuses fixture lookup at either stage.

To call compatible HTTP services, configure both stages before starting:

```bash
export GROUP_LOOKUP_MODE=HTTP
export GROUP_LOOKUP_URL='http://localhost:8083/groups/resolve'
export FLEXIBLE_LOOKUP_MODE=HTTP
export CONSENT_LOOKUP_URL='http://localhost:8082/members/resolve'
# Set GROUP_LOOKUP_BEARER_TOKEN and CONSENT_LOOKUP_BEARER_TOKEN if required.
# Set UPSERT_URL / UPSERT_BEARER_TOKEN independently for consent delivery.
```

PREVIEW can call the real lookup services without sending a consent upsert. Use `--ingestion.mode=LIVE` only when you intend to send updates. Group, member and upsert tokens are separate and are not saved in snapshots or customer files.

## Save results and recover after interruption

`GroupContextStore` saves one successful group result for each `(runId, externalGroupId)`. All rows for that group reuse it within the run, even if scattered through the file. A new run looks it up again; there is no global group cache.

A saved group result survives a failed or interrupted member lookup. If a crash happens after the group response but before saving it, that read-only call can repeat.

`EnrichedRecordStore` saves completed member-enriched rows. During preparation recovery, saved rows skip both lookups. An unfinished row can reuse its saved group result before retrying member lookup. Delivery recovery uses saved JSON and repeats neither lookup. The run saves both lookup configurations and their order.

A missing external group ID, failed group lookup, incomplete group result or failed member lookup prevents that row from reaching JSON construction. The [error policy](error-handling-v2.md) applies: HARD stops preparation by default; configured MEDIUM handling rejects the source contract if its stable key is known. A failed group lookup never leads to a member call. If both preferences are blank, neither lookup is needed.

`resolvedGroup` and `resolved` are reserved names. Customer files contain original source fields and code/remarks, without internal lookup values. Both service integrations must remain read-only.

Previously saved READY/DELIVERING bodies and completed runs can be recovered without this lookup chain. An old STAGING run with `consent-member` but no `group-context` is paused. Resolve it with the previous release or a corrected fresh intake after reviewing prior delivery. Do not silently use the new member payload in an unfinished old run. New content/configuration creates a new run ID, so settle old LIVE outcomes before submitting again.

## Finding an existing payer consent is a separate job

`PAYER_CONSENT_KEY` groups payer consents in the mapping. It is not part of the member lookup. Neither are payer ID, policy ID or consent preferences. This replaces the earlier suggestion that demographics lookup should use those fields to find a particular consent.

Optional legacy `providerConsentGuid` / `payerConsentGuid` response fields can still pass through, and missing values become null. The demos use null GUIDs to show inserts.

Member demographics alone cannot identify several existing payer consents. If an update requires a specific consent GUID, add a separate consent-identity lookup after member resolution, based on the agreed consent-service rules. That lookup is not implemented in this change.

## Code and tests

| Job | Class |
| --- | --- |
| Run enrichers in order | `ProjectionEngine` / `RecordEnricher.enrichForRun` |
| Read external group ID and add group information | `GroupEnricher` |
| Adapt the group service | `GroupResolver` / `ConfiguredGroupResolver` |
| Save a result per run/group | `GroupContextStore` |
| Build the member-only request | `ConsentMemberEnricher` / `MemberLookupPayload` |
| Adapt the demographics service | `MemberResolver` / `ConfiguredMemberResolver` |

`GroupMemberLookupTest` checks allowed request fields, call order, shared group results, missing/failed/incomplete groups, no-change records and recovery. HTTP delivery/result tests also use both lookup stages. Input and customer-return examples include the external group ID without changing their expected consent JSON.
