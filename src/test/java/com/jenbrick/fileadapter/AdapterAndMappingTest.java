package com.jenbrick.fileadapter;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.jenbrick.fileadapter.config.Profile;
import com.jenbrick.fileadapter.mapping.ConfigurableRequestMapper;
import com.jenbrick.fileadapter.mapping.RequestValidator;
import com.jenbrick.fileadapter.mapping.TransformRegistry;
import com.jenbrick.fileadapter.source.AdapterRegistry;
import com.jenbrick.fileadapter.source.DelimitedFileAdapter;
import com.jenbrick.fileadapter.source.SourceNormalizer;
import com.jenbrick.fileadapter.source.SourceRecord;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class AdapterAndMappingTest {
    @TempDir Path directory;
    private final DelimitedFileAdapter adapter = new DelimitedFileAdapter();

    @Test void readsBomQuotedCommasAndMultilineFields() throws Exception {
        Path file = directory.resolve("input.csv");
        Files.writeString(file, "\uFEFFid,name,note\r\n001,\"Chen, Jr.\",\"line one\r\nline two\"\r\n");
        var records = new ArrayList<SourceRecord>();
        adapter.read(file, layout(",", Profile.HeaderMode.FIRST_RECORD, null), records::add);
        assertThat(records).hasSize(1);
        assertThat(records.get(0).payload().get("id").asText()).isEqualTo("001");
        assertThat(records.get(0).payload().get("name").asText()).isEqualTo("Chen, Jr.");
        assertThat(records.get(0).payload().get("note").asText()).contains("line one\r\nline two");
    }

    @Test void readsHeaderlessPipesWithoutDroppingFirstRecord() throws Exception {
        Path file = directory.resolve("input.txt");
        Files.writeString(file, "001|Jordan\n002|Alex\n");
        var records = new ArrayList<SourceRecord>();
        adapter.read(file, layout("|", Profile.HeaderMode.NONE, List.of("id", "name")), records::add);
        assertThat(records).hasSize(2);
        assertThat(records.get(0).payload().get("id").asText()).isEqualTo("001");
    }

    @Test void rejectsDuplicateHeadersAndInconsistentRows() throws Exception {
        Path file = directory.resolve("input.csv");
        Files.writeString(file, "id,id\n1,2\n");
        assertThatThrownBy(() -> adapter.read(file,
                layout(",", Profile.HeaderMode.FIRST_RECORD, null), r -> {}))
                .isInstanceOf(IllegalArgumentException.class);
        Files.writeString(file, "id,name\n1,Alex,extra\n");
        assertThatThrownBy(() -> adapter.read(file,
                layout(",", Profile.HeaderMode.FIRST_RECORD, null), r -> {}))
                .hasMessageContaining("Column count mismatch");
    }

    @Test void rejectsFormatsNotRegisteredInThisRegistry() {
        var registry = new AdapterRegistry(List.of(adapter));
        for (var format : List.of(Profile.Format.XML, Profile.Format.JSON, Profile.Format.FIXED_WIDTH)) {
            assertThatThrownBy(() -> registry.require(format)).hasMessageContaining("not implemented");
        }
    }

    @Test void rejectsInvalidEncodingRatherThanReplacingSourceCharacters() throws Exception {
        Path file = directory.resolve("input.csv");
        Files.write(file, new byte[] {'i', 'd', '\n', (byte) 0xc3, (byte) 0x28, '\n'});
        assertThatThrownBy(() -> adapter.read(file,
                layout(",", Profile.HeaderMode.FIRST_RECORD, null), r -> {}))
                .isInstanceOf(Exception.class);
    }

    @Test void resolvesAliasesAndRejectsConflictingValues() {
        var data = JsonNodeFactory.instance.objectNode().put("emp_id", "001");
        var layout = new Profile.Layout(Profile.Format.DELIMITED, "UTF-8", ",",
                Profile.HeaderMode.FIRST_RECORD, null,
                Map.of("employeeId", new Profile.SourceField("employee_id", List.of("emp_id"))));
        var normalizer = new SourceNormalizer();
        assertThat(normalizer.normalize(new SourceRecord(1, data), layout)
                .payload().get("employeeId").asText()).isEqualTo("001");
        data.put("employee_id", "002");
        assertThatThrownBy(() -> normalizer.normalize(new SourceRecord(1, data), layout))
                .hasMessageContaining("conflicting source aliases");
    }

    @Test void mapsNestedTypesDefaultsAndPresencePolicies() {
        var mapper = new ConfigurableRequestMapper(new TransformRegistry(List.of()));
        var source = JsonNodeFactory.instance.objectNode()
                .put("id", "001").put("blank", " ").putNull("explicitNull").put("amount", "12.50");
        var fields = List.of(
                mapping("id", "employeeId", true, null, null, List.of()),
                mapping("blank", "contact.omitted", false, Profile.BlankPolicy.OMIT, null, List.of("trim")),
                mapping("blank", "contact.cleared", false, Profile.BlankPolicy.NULL, null, List.of("trim")),
                mapping("blank", "contact.empty", false, Profile.BlankPolicy.EMPTY, null, List.of("trim")),
                mapping("explicitNull", "contact.explicitNull", false, null, null, List.of()),
                mapping("missing", "active", false, null, "true", List.of("boolean")),
                mapping("amount", "amount", false, null, null, List.of("decimal")));
        var profile = new Profile("test", "*", null, fields, List.of());
        var result = mapper.map(new SourceRecord(1, source), profile);
        assertThat(result.get("employeeId").asText()).isEqualTo("001");
        assertThat(result.at("/contact/omitted").isMissingNode()).isTrue();
        assertThat(result.at("/contact/cleared").isNull()).isTrue();
        assertThat(result.at("/contact/empty").asText()).isEmpty();
        assertThat(result.at("/contact/explicitNull").isNull()).isTrue();
        assertThat(result.get("active").isBoolean()).isTrue();
        assertThat(result.get("amount").decimalValue()).isEqualByComparingTo("12.50");
    }

    @Test void rejectsInvalidDatesAndRequiredMissingFieldsWithoutEchoingValues() {
        var mapper = new ConfigurableRequestMapper(new TransformRegistry(List.of()));
        var profile = new Profile("test", "*", null,
                List.of(mapping("date", "date", true, null, null, List.of("us-date"))), List.of());
        var source = JsonNodeFactory.instance.objectNode().put("date", "02/30/2027");
        assertThatThrownBy(() -> mapper.map(new SourceRecord(3, source), profile))
                .hasMessageContaining("Record 3").hasMessageNotContaining("02/30/2027");
        assertThatThrownBy(() -> mapper.map(new SourceRecord(3,
                JsonNodeFactory.instance.objectNode()), profile)).hasMessageContaining("Record 3");
    }

    @Test void validatesRequiredPathsNullabilityAndOutputTypes() {
        var validator = new RequestValidator();
        var profile = new Profile("test", "*", null, List.of(), List.of(
                new Profile.ContractField("person.id", Profile.ValueType.STRING, true, false)));
        var request = JsonNodeFactory.instance.objectNode();
        assertThatThrownBy(() -> validator.validate(request, profile)).hasMessageContaining("missing");
        request.putObject("person").put("id", 42);
        assertThatThrownBy(() -> validator.validate(request, profile)).hasMessageContaining("wrong type");
        ((com.fasterxml.jackson.databind.node.ObjectNode) request.get("person")).putNull("id");
        assertThatThrownBy(() -> validator.validate(request, profile)).hasMessageContaining("cannot be null");
    }

    private Profile.Layout layout(String delimiter, Profile.HeaderMode mode, List<String> columns) {
        return new Profile.Layout(Profile.Format.DELIMITED, "UTF-8", delimiter, mode, columns, Map.of());
    }

    private Profile.FieldMapping mapping(String source, String target, boolean required,
            Profile.BlankPolicy blank, String defaultValue, List<String> transforms) {
        return new Profile.FieldMapping(source, target, required, blank, defaultValue, transforms, Map.of());
    }
}
