package com.jenbrick.fileadapter.consent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jenbrick.fileadapter.consent.ConsentSettings.*;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class ConsentRulesTest {
    final ObjectMapper json=new ObjectMapper();
    ConsentSettings settings(String datePattern) {
        return new ConsentSettings("test-v1","PCDS","P2PDS",Map.of("Y","GRANT","N","RVOKE"),
                Map.of("01","SELF"),datePattern,"MDB","unit.test",null,
                new Lookup(LookupMode.FIXTURE,null,null,null,List.of(new Fixture(Map.of("x","y"),null))));
    }
    ObjectNode row()throws Exception{
        var row=(ObjectNode)json.readTree(Path.of("examples/consent/consent-object.json").toFile());
        row.put("RELATIONSHIP_CODE","01");row.put("PROVIDER_CLINICAL_CONSENT_PREFERENCE"," n ");
        row.put("PAYER_CONSENT_PREFERENCE","y");return row;
    }
    Resolution resolution(String pin){return new Resolution(new MemberIdentity("000123",pin,"blind-member"),
            new MemberIdentity("000123","XYZ","blind-manager"),null,"existing-guid");}
    @Test void translatesExplicitCodesPreservesIdentifiersAndKeepsManagerDistinct()throws Exception{
        var planner=new ConsentRequestPlanner((criteria,s)->resolution("00A"),null,json);
        var requests=planner.assemble(row(),settings("uuuu-MM-dd"));
        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).path("consentStatus").asText()).isEqualTo("RVOKE");
        assertThat(requests.get(1).path("consentStatus").asText()).isEqualTo("GRANT");
        assertThat(requests.get(0).at("/consentOwner/contract").asText()).isEqualTo("000123");
        assertThat(requests.get(0).at("/consentOwner/pin").asText()).isEqualTo("00A");
        assertThat(requests.get(0).at("/transactionDetails/consentManager/pin").asText()).isEqualTo("XYZ");
        assertThat(requests.get(1).at("/coverage/relationshipToPolicyHolder").asText()).isEqualTo("SELF");
    }
    @ParameterizedTest @ValueSource(strings={"", "01", "1234"})
    void invalidResolvedPinNeverProducesRequest(String pin)throws Exception{
        var planner=new ConsentRequestPlanner((criteria,s)->resolution(pin),null,json);
        assertThatThrownBy(()->planner.assemble(row(),settings("uuuu-MM-dd"))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void nullManagerIsRejectedRatherThanAssumedToBeOwner()throws Exception{
        var planner=new ConsentRequestPlanner((criteria,s)->new Resolution(resolution("001").owner(),null,null,null),null,json);
        assertThatThrownBy(()->planner.assemble(row(),settings("uuuu-MM-dd"))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void configuredDateFormatProducesIsoDates()throws Exception{
        var row=row();
        row.put("DATE_OF_BIRTH","01/02/1980");row.put("PAYER_DATE_OF_BIRTH","01/02/1980");
        row.put("PROVIDER_CLINICAL_CONSENT_EFFECTIVE_DATE","10/01/2026");
        row.put("PAYER_CONSENT_PREFERENCE_EFFECTIVE_DATE","10/02/2026");
        var planner=new ConsentRequestPlanner((criteria,s)->{
            assertThat(criteria.path("DATE_OF_BIRTH").asText()).isEqualTo("1980-01-02");
            return resolution("001");},null,json);
        var requests=planner.assemble(row,settings("MM/dd/uuuu"));
        assertThat(requests.get(1).path("effectiveDate").asText()).isEqualTo("2026-10-02");
        assertThat(requests.get(1).at("/coverage/policyHolderDateOfBirth").asText()).isEqualTo("1980-01-02");
    }
    @Test void ambiguousFixturesDoNotSelectAnArbitraryMember()throws Exception{
        var base=settings("uuuu-MM-dd");
        var fixture=new Fixture(Map.of("EXTERNAL_CONTRACT_ID","SYN-1001"),resolution("001"));
        var settings=new ConsentSettings(base.version(),base.providerTarget(),base.payerTarget(),base.preferences(),
            base.relationships(),base.datePattern(),base.sourceSystem(),base.sourceTransactionIdentifier(),null,
            new Lookup(LookupMode.FIXTURE,null,null,null,List.of(fixture,fixture)));
        var resolver=new ConfiguredMemberResolver(org.springframework.web.client.RestClient.builder(),"");
        assertThatThrownBy(()->resolver.resolve(row(),settings)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void liveNeverUsesSyntheticMemberFixtures()throws Exception{
        var planner=new ConsentRequestPlanner((criteria,s)->resolution("001"),null,json);
        assertThatThrownBy(()->planner.plan("run",new com.jenbrick.fileadapter.source.SourceRecord(1,row()),
            settings("uuuu-MM-dd"),com.jenbrick.fileadapter.config.IngestionProperties.Mode.LIVE))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("LIVE");
    }
}
