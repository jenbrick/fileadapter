package com.jenbrick.fileadapter.config;

import java.nio.charset.Charset;
import java.util.Comparator;
import java.util.HashSet;

public final class LayoutValidator {
    private LayoutValidator() {}

    public static void validate(Profile.Layout layout) {
        if (layout == null || layout.format() == null) {
            throw new IllegalArgumentException("A layout and format are required");
        }
        Charset.forName(layout.charset());
        switch (layout.format()) {
            case DELIMITED -> {
                if (layout.delimiter() == null || layout.delimiter().length() != 1
                        || "\r\n\"\0".contains(layout.delimiter())) {
                    throw new IllegalArgumentException("Delimited layout requires one valid delimiter");
                }
                if (layout.headerMode() == null) throw new IllegalArgumentException("header-mode is required");
                if (layout.headerMode() == Profile.HeaderMode.NONE
                        && (layout.columns() == null || layout.columns().isEmpty()
                        || layout.columns().stream().anyMatch(s -> s == null || s.isBlank())
                        || new HashSet<>(layout.columns()).size() != layout.columns().size())) {
                    throw new IllegalArgumentException("Headerless layouts require unique named columns");
                }
            }
            case FIXED_WIDTH -> {
                if (layout.headerMode() == Profile.HeaderMode.FIRST_RECORD) {
                    throw new IllegalArgumentException("Fixed-width input does not use a header");
                }
                var fixed = layout.fixedWidth();
                if (fixed == null || fixed.columns() == null || fixed.columns().isEmpty()) {
                    throw new IllegalArgumentException("Fixed-width layout requires column ranges");
                }
                var names = new HashSet<String>();
                long previousEnd = 0;
                for (var column : fixed.columns().stream()
                        .sorted(Comparator.comparingInt(Profile.FixedColumn::offset)).toList()) {
                    long end = (long) column.offset() + column.length();
                    if (column.name() == null || column.name().isBlank() || !names.add(column.name())
                            || column.offset() < previousEnd || column.length() < 1 || end > 1_000_000) {
                        throw new IllegalArgumentException("Invalid, duplicate, or overlapping fixed-width columns");
                    }
                    previousEnd = end;
                }
                if (fixed.recordLength() != null
                        && (fixed.recordLength() < previousEnd || fixed.recordLength() > 1_000_000)) {
                    throw new IllegalArgumentException("record-length must contain all fields and be at most 1000000");
                }
            }
            case XML -> XmlNames.recordPath(layout.xml());
            case JSON -> {
                if (layout.json() == null || layout.json().mode() == null) {
                    throw new IllegalArgumentException("JSON layout requires mode OBJECT, ARRAY, or LINES");
                }
            }
        }
        if (layout.fields() != null) layout.fields().forEach((name, field) -> {
            if (!name.matches("[A-Za-z_][A-Za-z0-9_-]*") || field.column() == null || field.column().isBlank()) {
                throw new IllegalArgumentException("Logical fields require a name and source column");
            }
            checkSourceSelector(field.column());
            if (field.aliases() != null) field.aliases().forEach(LayoutValidator::checkSourceSelector);
        });
    }

    private static void checkSourceSelector(String selector) {
        if (selector == null || selector.isBlank()
                || (selector.startsWith("/") && selector.matches(".*~(?![01]).*"))) {
            throw new IllegalArgumentException("Invalid source field name or JSON Pointer");
        }
    }

    public static int recordLength(Profile.FixedWidth options) {
        return options.recordLength() != null ? options.recordLength()
                : options.columns().stream().mapToInt(c -> c.offset() + c.length()).max().orElseThrow();
    }
}
