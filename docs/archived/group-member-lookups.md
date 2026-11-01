# Group service first, then member-only demographics lookup

The lookup sequence is now explicit:

1. Read and normalize the file record.
2. Pass `EXTERNAL_GROUP_ID` to the group-service port.
3. Receive `internalGroupNumber` and `customerId` and save the group context.
4. Pass member demographics/identifiers plus that context to the member-demographics-service port.
5. Receive the resolved internal contract, PIN and contract member GUID.
6. Use the configured projection to construct the consent JSON, then preview or POST it.

Neither lookup receives the combined consent upsert JSON. The member call also excludes payer/coverage fields, consent preferences, consent targets, resolve-provider/resolve-payer flags, audit columns and arbitrary extra source fields.

The existing company group service is the intended integration. Its actual HTTP path, verb, authentication and response contract have not been supplied. The included HTTP adapters therefore use small **POC facade contracts**, described below. Adapt `GroupResolver` and `MemberResolver` to those company APIs without changing the file adapters, JSON templates or result files.

## Walk one CSV record through the two services

An abbreviated input record:

```csv
EXTERNAL_GROUP_ID,EXTERNAL_CONTRACT_ID,RELATIONSHIP_CODE,FIRST_NAME,LAST_NAME,DATE_OF_BIRTH
EXT-GROUP-1,EXTERNAL-002,SELF,John,Smith,1980-01-01
```

The full [CSV fixture](../examples/flexible/flexible-consents.csv) includes the consent and payer fields used by the projection. Those additional fields stay inside the pipeline and do not become member-demographics request properties.

`GroupEnricher` calls the group port with only the trimmed external group ID. The supplied HTTP facade POSTs:

```json
{"externalGroupId":"EXT-GROUP-1"}
```

The facade must return HTTP 200 and a complete group context:

```json
{"internalGroupNumber":"10001","customerId":"CUST-1"}
```

The enricher adds this source-side namespace:

```json
{
  "resolvedGroup": {
    "internalGroupNumber": "10001",
    "customerId": "CUST-1"
  }
}
```

`ConsentMemberEnricher` requires that context before calling the member service. It constructs a typed `MemberLookupPayload`, serialized as the **entire request body**, with no `criteria` or consent wrapper:

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

These are the nine allowed request properties. Demographic/identifier strings are trimmed and date of birth is normalized using the configured input date pattern. The internal group number and customer ID come from the group service, not from trusted-looking fields supplied in the input file. The external group ID has already served its lookup purpose and is not forwarded as a second group selector.

The member port returns a normalized resolution. For example, the demo facade’s HTTP 200 body is:

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

The resolver boundary adapts the company response to this shape; this is not a claim that the existing demographics service returns these exact property names. The plugin validates nonblank internal contract/member GUID and a three-character PIN. It adds `resolved.owner`, `resolved.manager`, nullable legacy consent-GUID fields and a source timestamp alongside `resolvedGroup` and the original normalized values. It does not decide where those values appear in outgoing JSON.

An internal **group number** is different from an internal **member contract number**. The group number/customer ID identify the context for member matching. The member service supplies the contract and member identity used in `consentOwner` and contract batching.

## Configure the ordered enrichment chain

Every consent demo profile now uses:

```yaml
projection:
  enrichers:
    - group-context
    - consent-member
```

Startup rejects a consent-member chain that omits group-context or puts it later. The generic per-record person-mapping example still has no business lookups. All source-format demos now populate EXTERNAL_GROUP_ID, including headerless, fixed-width, XML, JSON and JSON Lines files. The consent JSON fixtures retain their previous shapes.

Group settings are separate from member settings:

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

Member settings remain in `profile.consent` for compatibility with the existing profile organization. Fixture matches now address the member payload’s **lower-camel-case** names and include the resolved group/customer context:

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

This is a fragment; use the checked-in full profile for HTTP timeouts, endpoints, all fixtures and the projection. The contract-batch fixture matches multiple members sharing the same external contract using names/date of birth as well as resolved group context. Member matching must resolve uniquely according to the real service’s rules.

`provider-target` and `payer-target` settings have been removed from member lookup configuration. Targets remain in the output mapping, where they belong. Old saved settings with those fields can still be deserialized for recovery of already staged request bodies.

## PREVIEW and LIVE

The existing preview command still runs without company services:

```bash
./gradlew test bootJar
java -jar build/libs/fileadapter-0.1.0.jar \
  --spring.profiles.active=flexible-demo \
  --ingestion.file=examples/flexible/flexible-consents.csv
```

Both lookup modes default to FIXTURE in the demo. Group fixtures cover EXT-GROUP-1/2/3 and return group numbers 10001/20002/30003 with customer IDs CUST-1/2/3. Fixtures are synthetic; LIVE refuses fixture lookup at either stage.

To use compatible HTTP facades, configure both stages before starting:

```bash
export GROUP_LOOKUP_MODE=HTTP
export GROUP_LOOKUP_URL='http://localhost:8083/groups/resolve'
export FLEXIBLE_LOOKUP_MODE=HTTP
export CONSENT_LOOKUP_URL='http://localhost:8082/members/resolve'
# Set GROUP_LOOKUP_BEARER_TOKEN and CONSENT_LOOKUP_BEARER_TOKEN if required.
# Set UPSERT_URL / UPSERT_BEARER_TOKEN independently for consent delivery.
```

PREVIEW can perform real read-only HTTP lookups while still avoiding the upsert POST. Add `--ingestion.mode=LIVE` only when delivery is intended. Group, member and upsert credentials are independent and are not saved in snapshots or customer files.

## Persistence, errors and recovery

`GroupContextStore` freezes one successful resolution per `(runId, externalGroupId)`. Multiple member rows in the same group share it, even when those rows are nonadjacent. A later file/run resolves again; there is no global stale group cache. Successful group context survives a failed or interrupted member lookup. If a crash occurs after the external group response but before saving it, that read-only group lookup may repeat.

Completed member-enriched rows continue to use `EnrichedRecordStore`. During preparation recovery, saved rows bypass both calls; incomplete rows can reuse the group context before retrying member lookup. Delivery recovery uses staged JSON bodies and performs neither lookup again. The run snapshot includes both lookup configurations and their order.

Missing external group ID, group lookup failure, missing internal group number/customer ID, or member lookup failure prevents that row from reaching projection. Existing [severity policies](error-handling.md) apply: default HARD fails preparation; configured MEDIUM enrichment handling isolates the source contract when its stable contract key is known. No member call occurs when group resolution fails. Neither call is needed for a row whose two preferences are blank.

The `resolvedGroup` and `resolved` namespaces are reserved. Customer returns continue to contain original source fields plus result code/remarks; internal lookup context is not appended to the customer file. Group/member services are read-only; unsafe side effects do not belong in these lookup ports.

Previously staged READY/DELIVERING bodies and terminal runs can still be recovered without invoking the new lookup chain. An older STAGING snapshot containing consent-member without group-context is paused for reconciliation and use of the previous release or a corrected fresh intake. The new service payload must not be silently substituted into an unfinished old lookup run. New input/configuration creates a new run ID; reconcile prior LIVE delivery before resubmitting old files.

## Payer consent identity remains separate

PAYER_CONSENT_KEY still distinguishes payer-consent groups in the projection; it is **not** sent to the member-demographics service. Payer ID, policy ID and consent preferences also remain outside that request. This replaces the earlier suggestion that member lookup should resolve a particular payer consent using those fields.

Legacy optional providerConsentGuid/payerConsentGuid response properties can still pass through the resolution object, and absent values become null. The demos illustrate new inserts with null consent GUIDs. Do not assume a demographics response can identify several existing payer consents from member demographics alone. If updates require per-consent GUID lookup, implement a separate consent-identity enrichment step using the eventual consent-service contract, after member resolution. That lookup is not part of this change.

## Code and tests to follow

| Responsibility | Class |
| --- | --- |
| Run named enrichers in order | ProjectionEngine / RecordEnricher.enrichForRun |
| Read external group ID, add group context | GroupEnricher |
| Existing group-service integration boundary | GroupResolver / ConfiguredGroupResolver |
| Save one group resolution per run and group | GroupContextStore |
| Build the member-only request | ConsentMemberEnricher / MemberLookupPayload |
| Member-demographics integration boundary | MemberResolver / ConfiguredMemberResolver |

GroupMemberLookupTest verifies exact request fields, group-before-member call order, one group call shared by repeated rows, missing/failed/incomplete group handling, no-change rows and recovery reuse. The existing HTTP delivery/result tests now exercise both lookup stages. All input-format and customer-return fixtures have been updated without changing their projected consent shapes.
