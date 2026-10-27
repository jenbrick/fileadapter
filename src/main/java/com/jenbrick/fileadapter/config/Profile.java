package com.jenbrick.fileadapter.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/** Version this definition whenever the meaning of a feed changes. */
public record Profile(String id, String filenamePattern, Layout layout,
                      List<FieldMapping> mappings, List<ContractField> contract) {
    public enum Format { DELIMITED, FIXED_WIDTH, XML, JSON }
    public enum HeaderMode { FIRST_RECORD, NONE }
    public enum BlankPolicy { OMIT, NULL, EMPTY, REJECT }
    public enum ValueType { STRING, INTEGER, DECIMAL, BOOLEAN, OBJECT, ARRAY }
    public enum JsonMode { OBJECT, ARRAY, LINES }

    public record Layout(Format format, String charset, String delimiter,
                         HeaderMode headerMode, List<String> columns,
                         Map<String, SourceField> fields,
                         @JsonInclude(JsonInclude.Include.NON_NULL) FixedWidth fixedWidth,
                         @JsonInclude(JsonInclude.Include.NON_NULL) Xml xml,
                         @JsonInclude(JsonInclude.Include.NON_NULL) Json json) {
        @ConstructorBinding
        public Layout {}

        // Preserve existing CSV call sites and old persisted layout snapshots.
        public Layout(Format format, String charset, String delimiter,
                      HeaderMode headerMode, List<String> columns, Map<String, SourceField> fields) {
            this(format, charset, delimiter, headerMode, columns, fields, null, null, null);
        }
    }

    /** Offsets and lengths count Unicode code points; offsets start at zero. */
    public record FixedColumn(String name, int offset, int length) {}
    public record FixedWidth(Integer recordLength, List<FixedColumn> columns) {}
    public record Xml(String recordPath, Map<String, String> namespaces) {}
    public record Json(JsonMode mode) {}

    public record SourceField(String column, List<String> aliases) {}

    public record FieldMapping(String source, String target, boolean required,
                               BlankPolicy blankPolicy, String defaultValue,
                               List<String> transforms, Map<String, String> values) {}

    public record ContractField(String path, ValueType type, boolean required,
                                boolean nullable) {}
}
