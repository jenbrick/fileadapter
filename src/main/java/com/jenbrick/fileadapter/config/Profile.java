package com.jenbrick.fileadapter.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Keep the instructions for one feed together: how to read it, what to look up, how to build JSON,
 * and how to report errors.
 */
// Ignore removed settings when reading an older saved run; its already-staged requests can still resume.
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(value = {"mappings", "contract"})
public record Profile(String id, String filenamePattern, Layout layout,
                      @JsonInclude(JsonInclude.Include.NON_NULL)
                      com.jenbrick.fileadapter.consent.ConsentSettings consent,
                      com.jenbrick.fileadapter.projection.Projection projection,
                      com.jenbrick.fileadapter.results.ErrorPolicy errors,
                      @JsonInclude(JsonInclude.Include.NON_NULL)
                      com.jenbrick.fileadapter.consent.GroupSettings group) {
    @ConstructorBinding public Profile {}
    // CSV, pipe, semicolon and tab all use DELIMITED with a different delimiter setting.
    public enum Format { DELIMITED, FIXED_WIDTH, XML, JSON }
    public enum HeaderMode { FIRST_RECORD, NONE }
    public enum BlankPolicy { OMIT, NULL, EMPTY, REJECT }
    public enum ValueType { STRING, INTEGER, DECIMAL, BOOLEAN, OBJECT, ARRAY }
    public enum JsonMode { OBJECT, ARRAY, LINES }

    // The layout describes the incoming file. The projection describes the outgoing JSON.
    public record Layout(Format format, String charset, String delimiter,
                         HeaderMode headerMode, List<String> columns,
                         Map<String, SourceField> fields,
                         @JsonInclude(JsonInclude.Include.NON_NULL) FixedWidth fixedWidth,
                         @JsonInclude(JsonInclude.Include.NON_NULL) Xml xml,
                         @JsonInclude(JsonInclude.Include.NON_NULL) Json json) {
        @ConstructorBinding
        public Layout {}

        // Convenience constructor for delimited layouts.
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

    // Use aliases when producers use different names for the same logical field.
    public record SourceField(String column, List<String> aliases) {}

    public record ScalarMapping(String source, boolean required,
                               BlankPolicy blankPolicy, String defaultValue,
                               List<String> transforms, Map<String, String> values) {}

    public record ContractField(String path, ValueType type, boolean required,
                                boolean nullable) {}
}
