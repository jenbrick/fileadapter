#!/usr/bin/env python3
"""Regenerate synthetic consent input files and expected bodies. No third-party Python dependencies."""
import csv
import io
import json
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'examples/consent'
COLUMNS = '''EXTERNAL_CONTRACT_ID RELATIONSHIP_CODE LAST_NAME FIRST_NAME MIDDLE_NAME GENDER_CODE DATE_OF_BIRTH EXTERNAL_GROUP_ID PROVIDER_CLINICAL_CONSENT_EFFECTIVE_DATE PROVIDER_CLINICAL_CONSENT_PREFERENCE PAYER_CONSENT_PREFERENCE_EFFECTIVE_DATE PAYER_CONSENT_PREFERENCE PAYER_NAME PAYER_ADDRESS_1 PAYER_ADDRESS_2 PAYER_CITY PAYER_STATE PAYER_ZIP PAYER_ID PAYER_POLICY_ID PAYER_GROUP_NUMBER PAYER_SUBSCRIBER_LAST_NAME PAYER_SUBSCRIBER_FIRST_NAME PAYER_SUBSCRIBER_MIDDLE_NAME PAYER_DATE_OF_BIRTH PAYER_GENDER_CODE PAYER_EFFECTIVE_DATE PAYER_CANCEL_DATE PAYER_PLAN_TYPE PAYER_COVERAGE_TYPE'''.split()
WIDTHS = [20,12,24,24,24,4,10,20,10,8,10,8,40,40,40,24,2,10,20,24,24,24,24,24,10,4,10,10,20,20]
ROWS = []
for n, (provider, payer) in enumerate([('RVOKE','GRANT'),('GRANT',''),('','RVOKE'),('GRANT','GRANT'),('','')], 1):
    row = dict.fromkeys(COLUMNS, '')
    row.update(EXTERNAL_CONTRACT_ID=f'SYN-100{n}', RELATIONSHIP_CODE='DEPENDENT' if n==3 else 'SELF',
        LAST_NAME='Example', FIRST_NAME=['Alex','Blair','Casey','Drew','Ellis'][n-1], MIDDLE_NAME='Q',
        GENDER_CODE='F' if n%2 else 'M', DATE_OF_BIRTH='2012-04-03' if n==3 else '1980-01-02',
        EXTERNAL_GROUP_ID='SYN-GROUP', PROVIDER_CLINICAL_CONSENT_EFFECTIVE_DATE='2026-10-01' if provider else '',
        PROVIDER_CLINICAL_CONSENT_PREFERENCE=provider, PAYER_CONSENT_PREFERENCE_EFFECTIVE_DATE='2026-10-02' if payer else '',
        PAYER_CONSENT_PREFERENCE=payer, PAYER_NAME='Synthetic Health', PAYER_ADDRESS_1='100 Example Way',
        PAYER_CITY='Example City', PAYER_STATE='PA', PAYER_ZIP='00000', PAYER_ID='SYN-PAYER',
        PAYER_POLICY_ID=f'SYN-POL-100{n}', PAYER_GROUP_NUMBER='SYN-GRP-01', PAYER_SUBSCRIBER_LAST_NAME='Example',
        PAYER_SUBSCRIBER_FIRST_NAME='Morgan' if n==3 else row.get('FIRST_NAME',''),
        PAYER_SUBSCRIBER_MIDDLE_NAME='Q', PAYER_DATE_OF_BIRTH='1980-01-02', PAYER_GENDER_CODE='F',
        PAYER_EFFECTIVE_DATE='2026-01-01', PAYER_PLAN_TYPE='SYN-PLAN', PAYER_COVERAGE_TYPE='MEDICAL')
    if n != 3: row['PAYER_SUBSCRIBER_FIRST_NAME'] = row['FIRST_NAME']
    ROWS.append(row)

def delimited(name, delimiter, header):
    with (OUT/name).open('w', newline='', encoding='utf-8') as f:
        writer = csv.writer(f, delimiter=delimiter, lineterminator='\n')
        if header: writer.writerow(COLUMNS)
        writer.writerows([[r[c] for c in COLUMNS] for r in ROWS])

if __name__ == '__main__':
    OUT.mkdir(parents=True, exist_ok=True)
    delimited('consent.csv', ',', True)
    delimited('consent-noheader.csv', ',', False)
    delimited('consent-header.psv', '|', True)
    delimited('consent-noheader.psv', '|', False)
    (OUT/'consent.fw').write_text(''.join(''.join(r[c].ljust(w) for c,w in zip(COLUMNS,WIDTHS))+'\n' for r in ROWS))
    (OUT/'consent-array.json').write_text(json.dumps(ROWS,indent=2)+'\n')
    (OUT/'consent-object.json').write_text(json.dumps(ROWS[0],indent=2)+'\n')
    (OUT/'consent.jsonl').write_text(''.join(json.dumps(r)+'\n' for r in ROWS))
    root=ET.Element('members')
    for row in ROWS:
        member=ET.SubElement(root,'member')
        for k,v in row.items(): ET.SubElement(member,k).text=v
    ET.indent(root)
    (OUT/'consent.xml').write_text(ET.tostring(root,encoding='unicode')+'\n')
    fixtures=[]
    expected=[]
    origins=[]
    for n,row in enumerate(ROWS,1):
        owner={'contract':f'99000000{n}','pin':'00A' if n==3 else '001','contractMemberGuid':f'SYN-MEMBER-{n}'}
        manager=owner if n!=3 else {'contract':'990000003','pin':'XYZ','contractMemberGuid':'SYN-GUARDIAN-3'}
        resolution={'owner':owner,'manager':manager,'providerConsentGuid':None,'payerConsentGuid':'SYN-CONSENT-20001' if n==1 else None}
        fixtures.append({'match':{k:row[k] for k in ['EXTERNAL_CONTRACT_ID','RELATIONSHIP_CODE','FIRST_NAME','LAST_NAME','DATE_OF_BIRTH']},'result':resolution})
        entries=[]
        for target,pref,date,guid in [('PCDS','PROVIDER_CLINICAL_CONSENT_PREFERENCE','PROVIDER_CLINICAL_CONSENT_EFFECTIVE_DATE',resolution['providerConsentGuid']),('P2PDS','PAYER_CONSENT_PREFERENCE','PAYER_CONSENT_PREFERENCE_EFFECTIVE_DATE',resolution['payerConsentGuid'])]:
            if not row[pref]: continue
            req={'consentGuid':guid,'consentTarget':target,'consentStatus':row[pref], 'consentOwner':owner,
                'effectiveDate':row[date],'effectiveEndDate':None,'transactionDetails':{'sourceSystem':'MDB','sourceDateTime':'2026-10-27T12:00:00Z','sourceTransactionIdentifier':'synthetic.demo','consentManager':manager}}
            if target=='P2PDS':
                req['coverage']={k:row[v] or None for k,v in {'payerId':'PAYER_ID','otherPayerMemberId':'PAYER_POLICY_ID','groupNumber':'PAYER_GROUP_NUMBER','policyHolderFirstName':'PAYER_SUBSCRIBER_FIRST_NAME','policyHolderLastName':'PAYER_SUBSCRIBER_LAST_NAME','policyHolderMiddleName':'PAYER_SUBSCRIBER_MIDDLE_NAME','policyHolderGender':'PAYER_GENDER_CODE','policyHolderDateOfBirth':'PAYER_DATE_OF_BIRTH','relationshipToPolicyHolder':'RELATIONSHIP_CODE'}.items()}
            entries.append(req)
        if entries:
            expected.append(entries)
            origins.append({'requestNumber':len(expected),'sourceRecordNumber':n,
                'consentTargets':[entry['consentTarget'] for entry in entries],'consentEntryCount':len(entries)})
    (OUT/'expected-requests.jsonl').write_text(''.join(json.dumps(r)+'\n' for r in expected))
    (OUT/'expected-provider-request.json').write_text(json.dumps([expected[0][0]],indent=2)+'\n')
    (OUT/'expected-payer-request.json').write_text(json.dumps([expected[0][1]],indent=2)+'\n')
    general=dict(ROWS[2])
    for key in ['PAYER_ID','PAYER_POLICY_ID','PAYER_GROUP_NUMBER','PAYER_SUBSCRIBER_FIRST_NAME',
                'PAYER_SUBSCRIBER_LAST_NAME','PAYER_SUBSCRIBER_MIDDLE_NAME','PAYER_GENDER_CODE','PAYER_DATE_OF_BIRTH']:
        general[key]=''
    (OUT/'consent-object-payer-general.json').write_text(json.dumps(general,indent=2)+'\n')
    general_expected=dict(expected[2][0])
    general_expected.pop('coverage')
    (OUT/'expected-payer-general-request.json').write_text(json.dumps([general_expected],indent=2)+'\n')
    (OUT/'expected-combined-request.json').write_text(json.dumps(expected[0],indent=2)+'\n')
    (OUT/'expected-request-details.json').write_text(json.dumps(origins,indent=2)+'\n')
    (OUT/'lookup-fixtures.json').write_text(json.dumps(fixtures,indent=2)+'\n')
    (OUT/'fixed-width-layout.json').write_text(json.dumps([{'name':c,'offset':sum(WIDTHS[:i]),'length':w} for i,(c,w) in enumerate(zip(COLUMNS,WIDTHS))],indent=2)+'\n')
