package com.jenbrick.fileadapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.jenbrick.fileadapter.config.LayoutValidator;
import com.jenbrick.fileadapter.config.Profile;
import com.jenbrick.fileadapter.config.RuntimeConfiguration;
import com.jenbrick.fileadapter.source.FixedWidthFileAdapter;
import com.jenbrick.fileadapter.source.JsonFileAdapter;
import com.jenbrick.fileadapter.source.RecordFailure;
import com.jenbrick.fileadapter.source.SourceNormalizer;
import com.jenbrick.fileadapter.source.SourceRecord;
import com.jenbrick.fileadapter.source.XmlFileAdapter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class AdditionalAdaptersTest {
    @TempDir Path directory;
    private final ObjectMapper json = new RuntimeConfiguration().objectMapper();

    @Test void readsHeaderlessFixedWidthWithUnicodeOffsetsAndPreservesPadding() throws Exception {
        var layout = fixed(new Profile.FixedWidth(5, List.of(
                new Profile.FixedColumn("id", 0, 2), new Profile.FixedColumn("name", 2, 3))));
        Path file = write("fixed.txt", "\uFEFF01😀 A\r\n02B  \r\n");
        var records = new ArrayList<SourceRecord>();
        new FixedWidthFileAdapter().read(file, layout, records::add);
        assertThat(records).hasSize(2);
        assertThat(records.get(0).number()).isEqualTo(1);
        assertThat(records.get(0).payload().get("id").asText()).isEqualTo("01");
        assertThat(records.get(0).payload().get("name").asText()).isEqualTo("😀 A");
        assertThat(records.get(1).payload().get("name").asText()).isEqualTo("B  ");
    }

    @ParameterizedTest @ValueSource(strings = {"1234", "123456", "\n"})
    void rejectsIncorrectFixedRecordLengths(String line) throws Exception {
        var layout = fixed(new Profile.FixedWidth(null, List.of(new Profile.FixedColumn("id", 0, 5))));
        assertThatThrownBy(() -> new FixedWidthFileAdapter().read(write("fixed.txt", line), layout, r -> {}))
                .isInstanceOf(RecordFailure.class).hasMessageContaining("exactly 5");
    }

    @Test void rejectsInvalidFixedRangesAndDuplicateNames() {
        for (var columns : List.of(
                List.of(new Profile.FixedColumn("a", 0, 4), new Profile.FixedColumn("b", 3, 2)),
                List.of(new Profile.FixedColumn("a", -1, 1)),
                List.of(new Profile.FixedColumn("a", 0, 0)),
                List.of(new Profile.FixedColumn("a", 0, 2), new Profile.FixedColumn("a", 2, 2)))) {
            assertThatThrownBy(() -> LayoutValidator.validate(fixed(new Profile.FixedWidth(null, columns))))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> LayoutValidator.validate(fixed(new Profile.FixedWidth(2,
                List.of(new Profile.FixedColumn("a", 0, 3)))))).hasMessageContaining("record-length");
    }

    @Test void jsonPreservesNestedStructuresTypesNullsAndDecimalPrecision() throws Exception {
        var records = readJson("""
                [{"id":"001","active":true,"amount":123456789.123456789,
                  "person":{"email":null},"children":[{"id":2}]}]
                """, Profile.JsonMode.ARRAY);
        var data = records.get(0).payload();
        assertThat(data.get("id").asText()).isEqualTo("001");
        assertThat(data.get("active").isBoolean()).isTrue();
        assertThat(data.get("amount").decimalValue()).isEqualByComparingTo("123456789.123456789");
        assertThat(data.at("/person/email").isNull()).isTrue();
        assertThat(data.at("/person/missing").isMissingNode()).isTrue();
        assertThat(data.at("/children/0/id").intValue()).isEqualTo(2);
    }

    @Test void supportsJsonObjectAndLinesWithLogicalRecordNumbers() throws Exception {
        assertThat(readJson("{\"id\":1}", Profile.JsonMode.OBJECT)).hasSize(1);
        var records = readJson("\uFEFF{\"id\":1}\r\n\r\n{\"id\":2}\n", Profile.JsonMode.LINES);
        assertThat(records).extracting(SourceRecord::number).containsExactly(1L, 2L);
    }

    @ParameterizedTest @ValueSource(strings = {
            "[{\"id\":1,\"id\":2}]", "[{\"person\":{\"id\":1,\"id\":2}}]",
            "[{\"id\":1}] {}", "[{\"id\":1}", "[1]", "[null]", "{}"})
    void rejectsInvalidArrayDocuments(String content) {
        assertThatThrownBy(() -> readJson(content, Profile.JsonMode.ARRAY)).isInstanceOf(Exception.class);
    }

    @ParameterizedTest @ValueSource(strings = {"{} {}", "[]", "null", "{\"id\":1,\"id\":2}"})
    void rejectsInvalidSingleObjects(String content) {
        assertThatThrownBy(() -> readJson(content, Profile.JsonMode.OBJECT)).isInstanceOf(Exception.class);
    }

    @Test void linesRejectsMultipleObjectsOnOneLineAndMultilineRecords() {
        assertThatThrownBy(() -> readJson("{} {}\n", Profile.JsonMode.LINES)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> readJson("{\n\"id\":1}\n", Profile.JsonMode.LINES)).isInstanceOf(Exception.class);
    }

    @Test void xmlPreservesAttributesRepeatedChildrenEmptyElementsAndExplicitNull() throws Exception {
        var records = readXml("""
                <employees xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                  <employee id="001"><person><name>A &amp; B</name></person>
                    <tag>one</tag><tag>two</tag><empty/><email xsi:nil="true"/>
                    <weight unit="kg">12</weight></employee>
                  <employee id="002"><person><name>C</name></person></employee>
                </employees>
                """, new Profile.Xml("/employees/employee", Map.of()));
        assertThat(records).hasSize(2);
        var data = records.get(0).payload();
        assertThat(data.get("@id").asText()).isEqualTo("001");
        assertThat(data.at("/person/name").asText()).isEqualTo("A & B");
        assertThat(data.get("tag").isArray()).isTrue();
        assertThat(data.at("/tag/1").asText()).isEqualTo("two");
        assertThat(data.get("empty").asText()).isEmpty();
        assertThat(data.get("email").isNull()).isTrue();
        assertThat(data.at("/weight/@unit").asText()).isEqualTo("kg");
        assertThat(data.at("/weight/#text").asText()).isEqualTo("12");
    }

    @Test void xmlMatchesNamespaceUrisRatherThanDocumentPrefixSpellings() throws Exception {
        var options = new Profile.Xml("/e:employees/e:employee", Map.of("e", "urn:employee"));
        for (String input : List.of(
                "<p:employees xmlns:p='urn:employee'><p:employee id='1'><p:name>Alex</p:name></p:employee></p:employees>",
                "<employees xmlns='urn:employee'><employee id='1'><name>Alex</name></employee></employees>")) {
            var records = readXml(input, options);
            assertThat(records).hasSize(1);
            assertThat(records.get(0).payload().get("e:name").asText()).isEqualTo("Alex");
        }
        assertThat(readXml("<employees><employee id='1'/></employees>", options)).isEmpty();
    }

    @ParameterizedTest @ValueSource(strings = {
            "<!DOCTYPE employees [<!ENTITY secret SYSTEM 'file:///etc/passwd'>]><employees><employee>&secret;</employee></employees>",
            "<!DOCTYPE employees SYSTEM 'http://127.0.0.1:9/external.dtd'><employees/>",
            "<!DOCTYPE employees [<!ENTITY word 'hello'>]><employees/>"})
    void rejectsDtdsAndEntities(String content) {
        assertThatThrownBy(() -> readXml(content, new Profile.Xml("/employees/employee", Map.of())))
                .isInstanceOf(RecordFailure.class).hasMessageContaining("DTD");
    }

    @Test void xmlRejectsMixedContentNilWithDataMalformedTailAndExcessiveDepth() {
        for (String content : List.of(
                "<employees><employee><name>Hello <b>world</b></name></employee></employees>",
                "<employees xmlns:xsi='http://www.w3.org/2001/XMLSchema-instance'><employee><name xsi:nil='true'>Alex</name></employee></employees>",
                "<employees><employee id='1'/></broken>",
                "<employees><employee>" + "<a>".repeat(128) + "x" + "</a>".repeat(128)
                        + "</employee></employees>")) {
            assertThatThrownBy(() -> readXml(content, new Profile.Xml("/employees/employee", Map.of())))
                    .isInstanceOf(Exception.class);
        }
    }

    @Test void rejectsUnboundXmlPrefixesAndNonElementSelectors() {
        for (String path : List.of("employees/employee", "/employees//employee", "/employees/*",
                "/x:employees/x:employee", "/employees/employee[1]")) {
            var layout = xml(new Profile.Xml(path, Map.of()));
            assertThatThrownBy(() -> LayoutValidator.validate(layout)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void normalizesNestedPointersWithoutLosingNullOrLiteralColumnNames() {
        var data = JsonNodeFactory.instance.objectNode();
        data.putObject("person").putNull("email");
        data.put("a.b", "literal").put("slash/key", "escaped");
        var fields = Map.of(
                "email", new Profile.SourceField("/person/email", null),
                "literal", new Profile.SourceField("a.b", null),
                "escaped", new Profile.SourceField("/slash~1key", null));
        var layout = new Profile.Layout(Profile.Format.JSON, "UTF-8", null, null, null, fields,
                null, null, new Profile.Json(Profile.JsonMode.OBJECT));
        var result = new SourceNormalizer().normalize(new SourceRecord(1, data), layout).payload();
        assertThat(result.get("email").isNull()).isTrue();
        assertThat(result.get("literal").asText()).isEqualTo("literal");
        assertThat(result.get("escaped").asText()).isEqualTo("escaped");
    }

    @Test void delimitedLayoutSnapshotRoundTrips() throws Exception {
        String previous = """
                {"charset":"UTF-8","columns":null,"delimiter":",","fields":{},
                 "format":"DELIMITED","headerMode":"FIRST_RECORD"}
                """;
        var restored = json.readValue(previous, Profile.Layout.class);
        LayoutValidator.validate(restored);
        assertThat(json.<com.fasterxml.jackson.databind.JsonNode>valueToTree(restored))
                .isEqualTo(json.readTree(previous));
    }

    private Profile.Layout fixed(Profile.FixedWidth options) {
        return new Profile.Layout(Profile.Format.FIXED_WIDTH, "UTF-8", null, Profile.HeaderMode.NONE,
                null, Map.of(), options, null, null);
    }

    private Profile.Layout xml(Profile.Xml options) {
        return new Profile.Layout(Profile.Format.XML, "UTF-8", null, null, null, Map.of(), null, options, null);
    }

    private List<SourceRecord> readXml(String content, Profile.Xml options) throws Exception {
        var records = new ArrayList<SourceRecord>();
        new XmlFileAdapter().read(write("input.xml", content), xml(options), records::add);
        return records;
    }

    private List<SourceRecord> readJson(String content, Profile.JsonMode mode) throws Exception {
        var records = new ArrayList<SourceRecord>();
        var layout = new Profile.Layout(Profile.Format.JSON, "UTF-8", null, null, null, Map.of(),
                null, null, new Profile.Json(mode));
        new JsonFileAdapter(json).read(write("input.json", content), layout, records::add);
        return records;
    }

    private Path write(String name, String content) throws Exception {
        Path file = directory.resolve(name);
        Files.writeString(file, content);
        return file;
    }
}
