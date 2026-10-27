package com.jenbrick.fileadapter.config;

import java.util.List;
import java.util.Map;

/** Version this definition whenever the meaning of a feed changes. */
public record Profile(String id, String filenamePattern, Layout layout,
                      List<FieldMapping> mappings, List<ContractField> contract) {
    public enum Format { DELIMITED, FIXED_WIDTH, XML, JSON }
    public enum HeaderMode { FIRST_RECORD, NONE }
    public enum BlankPolicy { OMIT, NULL, EMPTY, REJECT }
    public enum ValueType { STRING, INTEGER, DECIMAL, BOOLEAN, OBJECT, ARRAY }

    public record Layout(Format format, String charset, String delimiter,
                         HeaderMode headerMode, List<String> columns,
                         Map<String, SourceField> fields) {}

    public record SourceField(String column, List<String> aliases) {}

    public record FieldMapping(String source, String target, boolean required,
                               BlankPolicy blankPolicy, String defaultValue,
                               List<String> transforms, Map<String, String> values) {}

    public record ContractField(String path, ValueType type, boolean required,
                                boolean nullable) {}
}
