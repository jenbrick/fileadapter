package com.jenbrick.fileadapter;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.jenbrick.fileadapter.config.Profile;
import com.jenbrick.fileadapter.mapping.MappingValues;
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

    @Test void scalarMappingPreservesDefaultsTypesAndPresencePolicies() {
        var values = new MappingValues(new TransformRegistry(List.of()));
        var source = JsonNodeFactory.instance.objectNode().put("id", "001").put("blank", " ")
                .putNull("explicitNull").put("amount", "12.50");
        assertThat(values.resolve(source, mapping("id", true, null, null, List.of())).asText()).isEqualTo("001");
        assertThat(values.resolve(source, mapping("blank", false, Profile.BlankPolicy.OMIT, null, List.of("trim"))).isMissingNode()).isTrue();
        assertThat(values.resolve(source, mapping("blank", false, Profile.BlankPolicy.NULL, null, List.of("trim"))).isNull()).isTrue();
        assertThat(values.resolve(source, mapping("blank", false, Profile.BlankPolicy.EMPTY, null, List.of("trim"))).asText()).isEmpty();
        assertThat(values.resolve(source, mapping("explicitNull", false, null, null, List.of())).isNull()).isTrue();
        assertThat(values.resolve(source, mapping("missing", false, null, "true", List.of("boolean"))).isBoolean()).isTrue();
        assertThat(values.resolve(source, mapping("amount", false, null, null, List.of("decimal"))).decimalValue()).isEqualByComparingTo("12.50");
    }

    @Test void rejectsInvalidDatesAndRequiredMissingFields() {
        var values = new MappingValues(new TransformRegistry(List.of()));
        var field = mapping("date", true, null, null, List.of("us-date"));
        assertThatThrownBy(() -> values.resolve(JsonNodeFactory.instance.objectNode().put("date", "02/30/2027"), field))
                .isInstanceOf(java.time.format.DateTimeParseException.class);
        assertThatThrownBy(() -> values.resolve(JsonNodeFactory.instance.objectNode(), field))
                .hasMessageContaining("missing");
    }

    @Test void validatesRequiredPathsNullabilityAndOutputTypes() {
        var validator = new RequestValidator();
        var rules = List.of(new Profile.ContractField("person.id", Profile.ValueType.STRING, true, false));
        var request = JsonNodeFactory.instance.objectNode();
        assertThatThrownBy(() -> validator.validateFields(request, rules)).hasMessageContaining("missing");
        request.putObject("person").put("id", 42);
        assertThatThrownBy(() -> validator.validateFields(request, rules)).hasMessageContaining("wrong type");
        ((com.fasterxml.jackson.databind.node.ObjectNode) request.get("person")).putNull("id");
        assertThatThrownBy(() -> validator.validateFields(request, rules)).hasMessageContaining("cannot be null");
    }

    private Profile.Layout layout(String delimiter, Profile.HeaderMode mode, List<String> columns) {
        return new Profile.Layout(Profile.Format.DELIMITED, "UTF-8", delimiter, mode, columns, Map.of());
    }

    private Profile.ScalarMapping mapping(String source, boolean required,
            Profile.BlankPolicy blank, String defaultValue, List<String> transforms) {
        return new Profile.ScalarMapping(source, required, blank, defaultValue, transforms, Map.of());
    }
}
