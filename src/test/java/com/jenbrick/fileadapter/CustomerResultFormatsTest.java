package com.jenbrick.fileadapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jenbrick.fileadapter.config.*;
import com.jenbrick.fileadapter.source.*;
import com.jenbrick.fileadapter.results.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.*;

/**
 * Read customer returns back through the input adapters to check that original values and the two
 * result fields survive.
 */
@SpringBootTest
@ActiveProfiles("flexible-demo")
@DirtiesContext
class CustomerResultFormatsTest {
    static final Path WORK=temp();
    @DynamicPropertySource static void config(DynamicPropertyRegistry r){r.add("ingestion.work-directory",WORK::toString);}
    @Autowired AdapterRegistry adapters;
    @Autowired ProfileCatalog profiles;
    @Autowired SourceResultWriter writer;
    @Autowired ObjectMapper json;
    @ParameterizedTest @ValueSource(strings={"flexible-consents.csv","flexible-noheader.csv","flexible-consents.psv","flexible-noheader.psv","flexible-consents.scsv","flexible-consents.tsv","flexible-consents.fw","flexible-consents.xml","flexible-consents.json","flexible-object.json","flexible-consents.jsonl"})
    // The return has the original layout plus two fields, so headerless and fixed-width readers
    // need an extended result layout.
    void returnsOriginalValuesAndTwoResultFieldsInEveryInputFormat(String filename)throws Exception {
        var layout=profiles.resolve(Path.of(filename),null).layout();
        var source=new ArrayList<SourceRecord>();adapters.require(layout.format()).read(Path.of("examples/flexible",filename),layout,source::add);
        var output=new ArrayList<SourceResultWriter.Row>();
        for(var row:source)output.add(new SourceResultWriter.Row(new ResultStore.Source(row.number(),json.writeValueAsString(row.payload()),row.rawText(),null,null,null),"MEMBER_REJECTED","Please correct this member."));
        Path target=WORK.resolve(filename+".result");writer.write(target,layout,ErrorPolicy.defaults(),output);
        var resultLayout=layout;
        if(layout.format()==Profile.Format.DELIMITED && layout.headerMode()==Profile.HeaderMode.NONE) {
            var columns=new ArrayList<>(layout.columns());columns.add("RESULT_CODE");columns.add("RESULT_REMARKS");
            resultLayout=new Profile.Layout(layout.format(),layout.charset(),layout.delimiter(),layout.headerMode(),columns,null);
        }
        if(layout.format()==Profile.Format.FIXED_WIDTH) {
            int length=LayoutValidator.recordLength(layout.fixedWidth());var columns=new ArrayList<>(layout.fixedWidth().columns());
            columns.add(new Profile.FixedColumn("RESULT_CODE",length,16));columns.add(new Profile.FixedColumn("RESULT_REMARKS",length+16,160));
            resultLayout=new Profile.Layout(layout.format(),layout.charset(),null,null,null,null,new Profile.FixedWidth(length+176,columns),null,null);
            var lines=Files.readAllLines(target);for(int i=0;i<source.size();i++)assertThat(lines.get(i)).startsWith(source.get(i).rawText());
        }
        var actual=new ArrayList<SourceRecord>();adapters.require(layout.format()).read(target,resultLayout,actual::add);
        assertThat(actual).hasSameSizeAs(source);
        for(int i=0;i<source.size();i++) {
            var row=(com.fasterxml.jackson.databind.node.ObjectNode)actual.get(i).payload().deepCopy();
            assertThat(row.remove("RESULT_CODE").asText().trim()).isEqualTo("MEMBER_REJECTED");
            assertThat(row.remove("RESULT_REMARKS").asText().trim()).isEqualTo("Please correct this member.");
            assertThat(row).isEqualTo(source.get(i).payload());
        }
    }
    // A syntactically valid XML return can still lose an attribute namespace; compare the parsed
    // meaning as well.
    @org.junit.jupiter.api.Test void namespacedXmlAttributesRepeatedElementsAndNullsRoundTrip()throws Exception {
        Path input=WORK.resolve("namespaced.xml");
        Files.writeString(input,"<p:members xmlns:p='urn:members' xmlns:q='urn:other' xmlns:xsi='http://www.w3.org/2001/XMLSchema-instance'><p:member id='7'><p:NAME>Alex</p:NAME><q:note q:flag='yes'>one</q:note><q:note>two</q:note><p:optional xsi:nil='true'/></p:member></p:members>");
        var layout=new Profile.Layout(Profile.Format.XML,"UTF-8",null,null,null,null,null,
                new Profile.Xml("/p:members/p:member",Map.of("p","urn:members")),null);
        var source=new ArrayList<SourceRecord>();adapters.require(Profile.Format.XML).read(input,layout,source::add);
        var row=source.get(0);Path output=WORK.resolve("namespaced-result.xml");
        writer.write(output,layout,ErrorPolicy.defaults(),List.of(new SourceResultWriter.Row(new ResultStore.Source(1,json.writeValueAsString(row.payload()),null,null,null,null),"REVIEW","Please review.")));
        var reread=new ArrayList<SourceRecord>();adapters.require(Profile.Format.XML).read(output,layout,reread::add);
        var data=(com.fasterxml.jackson.databind.node.ObjectNode)reread.get(0).payload();
        assertThat(data.remove("RESULT_CODE").asText()).isEqualTo("REVIEW");data.remove("RESULT_REMARKS");
        assertThat(data).isEqualTo(row.payload());
    }
    // Existing customer values must survive even when their names clash with configured result fields.
    @org.junit.jupiter.api.Test void sourceFieldCollisionCannotOverwriteOriginalData()throws Exception {
        var layout=profiles.resolve(Path.of("flexible-object.json"),null).layout();
        var row=new ResultStore.Source(1,"{\"RESULT_CODE\":\"original\",\"name\":\"Alex\"}",null,null,null,null);
        assertThatThrownBy(()->writer.write(WORK.resolve("collision.json"),layout,ErrorPolicy.defaults(),
                List.of(new SourceResultWriter.Row(row,"REVIEW","Review.")))).hasMessageContaining("collides");
    }
    static Path temp(){try{return Files.createTempDirectory("customer-formats-");}catch(Exception e){throw new ExceptionInInitializerError(e);}}
}
