package com.jenbrick.fileadapter.projection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.jenbrick.fileadapter.config.IngestionProperties.Mode;
import com.jenbrick.fileadapter.config.Profile;
import com.jenbrick.fileadapter.mapping.JsonPaths;
import com.jenbrick.fileadapter.mapping.MappingValues;
import com.jenbrick.fileadapter.mapping.RequestValidator;
import com.jenbrick.fileadapter.mapping.TransformRegistry;
import com.jenbrick.fileadapter.source.RecordFailure;
import com.jenbrick.fileadapter.source.SourceRecord;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Generic bounded tree projection: scalar mappings, objects, filtered/grouped collections and literals. */
@Component
public class ProjectionEngine {
    public record Request(JsonNode body, List<Long> sourceRecords) {}
    private final ObjectMapper json;
    private final MappingValues values;
    private final RequestValidator validator;
    private final TransformRegistry transforms;
    private final EnrichedRecordStore store;
    private final Map<String,RecordEnricher> enrichers=new LinkedHashMap<>();

    public ProjectionEngine(ObjectMapper json, MappingValues values, RequestValidator validator,
                            TransformRegistry transforms, EnrichedRecordStore store, List<RecordEnricher> extensions) {
        this.json=json;this.values=values;this.validator=validator;this.transforms=transforms;this.store=store;
        for(var extension:extensions) if(enrichers.putIfAbsent(extension.name(),extension)!=null)
            throw new IllegalArgumentException("Duplicate record enricher");
    }

    public void validate(Projection projection) {
        if(projection.version()==null || projection.version().isBlank() || projection.scope()==null
                || projection.maxRecords()<1 || projection.maxRecords()>10_000)
            throw new IllegalArgumentException("Projection requires version, scope and max-records from 1 to 10000");
        if(projection.enrichers()!=null) {
            var names=new LinkedHashSet<String>();
            for(String name:projection.enrichers()) if(!enrichers.containsKey(name) || !names.add(name))
                throw new IllegalArgumentException("Unknown or duplicate record enricher");
        }
        checkRules(projection.inputContract());
        if(projection.skipWhenEmpty()!=null && !projection.skipWhenEmpty().equals("$")) JsonPaths.check(projection.skipWhenEmpty());
        checkNode(template(projection),0,new int[]{0},"$");
    }

    public void validateEnrichers(Profile profile) {
        if(profile.projection().enrichers()!=null)
            for(String name:profile.projection().enrichers()) enrichers.get(name).validate(profile);
    }

    public SourceRecord enrich(String runId, SourceRecord row, Profile profile, Mode mode) {
        validator.validateFields(row.payload(),profile.projection().inputContract());
        try {
            String saved=store.get(runId,row.number());
            if(saved!=null) return new SourceRecord(row.number(),json.readTree(saved));
            var enriched=row;
            if(profile.projection().enrichers()!=null) for(String name:profile.projection().enrichers()) {
                enriched=enrichers.get(name).enrich(enriched,profile,mode);
                if(enriched==null || enriched.number()!=row.number() || !enriched.payload().isObject())
                    throw new IllegalArgumentException("Enricher must preserve row identity and return an object");
            }
            store.save(runId,row.number(),json.writeValueAsString(enriched.payload()));
            return enriched;
        } catch(java.io.IOException e) {throw new IllegalStateException("Cannot persist enriched record",e);}
    }

    public List<Request> project(List<SourceRecord> rows, Projection projection) {
        if(rows.size()>projection.maxRecords()) throw new RecordFailure("Projection record limit exceeded");
        var requests=new ArrayList<Request>();
        if(projection.scope()==Projection.Scope.FILE) projectOne(rows,projection,requests);
        else for(var row:rows) projectOne(List.of(row),projection,requests);
        return requests;
    }

    private void projectOne(List<SourceRecord> rows, Projection projection, List<Request> requests) {
        if(rows.isEmpty()) return;
        var used=new LinkedHashSet<Long>();
        JsonNode body=render(template(projection),rows,used,"$");
        if(!body.isObject() && !body.isArray()) throw new RecordFailure("Request root must be object or array");
        if(projection.skipWhenEmpty()!=null) {
            var value=projection.skipWhenEmpty().equals("$")?body:JsonPaths.get(body,projection.skipWhenEmpty());
            if(value.isMissingNode() || value.isNull() || (value.isContainerNode() && value.isEmpty())) return;
        }
        if(used.isEmpty()) rows.forEach(row->used.add(row.number()));
        requests.add(new Request(body,List.copyOf(used)));
    }

    private JsonNode render(Projection.Node node, List<SourceRecord> rows, Set<Long> used, String path) {
        try {
            JsonNode result;
            if(node.source()!=null) {
                var mapping=new Profile.ScalarMapping(node.source(),node.required(),node.blankPolicy(),
                        node.defaultValue(),node.transforms(),node.values());
                result=null;
                for(var row:rows) {
                    var value=values.resolve(row.payload(),mapping);
                    if(value.isMissingNode() && node.defaultJson()!=null) value=literal(node.defaultJson());
                    if(result!=null && !result.equals(value)) throw new RecordFailure(
                            "Conflicting grouped values at " + path + " for source records " + rows.stream().map(SourceRecord::number).toList());
                    result=value;
                    used.add(row.number());
                }
                if(result==null) result=MissingNode.getInstance();
            } else if(node.literalJson()!=null) result=literal(node.literalJson());
            else if(node.object()!=null) {
                var object=json.createObjectNode();
                for(var entry:node.object().entrySet()) {
                    var value=render(entry.getValue(),rows,used,path+"."+entry.getKey());
                    if(!value.isMissingNode()) object.set(entry.getKey(),value.deepCopy());
                }
                result=object;
            } else {
                var array=json.createArrayNode();
                for(var collection:node.array()) {
                    var selected=rows.stream().filter(row->matches(row.payload(),collection.where())).toList();
                    var groups=new LinkedHashMap<List<JsonNode>,List<SourceRecord>>();
                    for(var row:selected) {
                        var key=new ArrayList<JsonNode>();
                        if(collection.groupBy()==null || collection.groupBy().isEmpty()) key.add(json.getNodeFactory().numberNode(row.number()));
                        else for(String field:collection.groupBy()) {
                            var value=JsonPaths.get(row.payload(),field);
                            if(!present(value) || value.isContainerNode()) throw new RecordFailure("Missing or invalid grouping field " + field + " at record " + row.number());
                            key.add(value);
                        }
                        groups.computeIfAbsent(key,k->new ArrayList<>()).add(row);
                    }
                    for(var group:groups.values()) {
                        var value=render(collection.item(),group,used,path+"[]");
                        if(!value.isMissingNode()) array.add(value.deepCopy());
                    }
                }
                result=array;
            }
            validator.validateFields(result,node.checks());
            return result;
        } catch(RecordFailure e) {throw e;}
        catch(RuntimeException e) {throw new RecordFailure("Projection " + path + " failed (" + e.getClass().getSimpleName() + ")");}
    }

    private boolean matches(JsonNode row, Projection.Where where) {
        if(where==null) return true;
        if(where.anyPresent()!=null) return where.anyPresent().stream().anyMatch(field->present(JsonPaths.get(row,field)));
        var value=JsonPaths.get(row,where.source());
        return where.present()!=null ? present(value)==where.present()
                : !value.isMissingNode() && !value.isNull() && value.asText().equals(where.equalsValue());
    }
    private static boolean present(JsonNode value) {
        return value!=null && !value.isMissingNode() && !value.isNull()
                && (value.isContainerNode()?!value.isEmpty():!value.isTextual() || !value.asText().isBlank());
    }
    private JsonNode literal(String text) {
        try(var parser=json.getFactory().createParser(text)) {
            parser.enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode value=json.readTree(parser);
            if(value==null || parser.nextToken()!=null) throw new IllegalArgumentException("One JSON literal required");
            return value;
        } catch(java.io.IOException e) {throw new IllegalArgumentException("Invalid JSON literal");}
    }
    // Untyped Spring maps represent indexed YAML collections as maps of numeric keys.
    // Normalize only DSL list positions, preserving numeric/array-named output properties.
    private Projection.Node template(Projection projection) {
        try {
            JsonNode tree=json.valueToTree(projection.root());
            normalizeNode(tree);
            return json.treeToValue(tree,Projection.Node.class);
        } catch(Exception e) { throw new IllegalArgumentException("Invalid projection tree properties"); }
    }
    private void normalizeNode(JsonNode tree) {
        if(!(tree instanceof com.fasterxml.jackson.databind.node.ObjectNode object)) return;
        for(String field:List.of("transforms","checks")) if(object.has(field)) object.set(field,indexedList(object.get(field)));
        if(object.path("object").isObject()) object.get("object").elements().forEachRemaining(this::normalizeNode);
        if(object.has("array")) {
            var collections=indexedList(object.get("array"));object.set("array",collections);
            for(var collection:collections) {
                if(!(collection instanceof com.fasterxml.jackson.databind.node.ObjectNode c)) throw new IllegalArgumentException("Invalid collection");
                if(c.has("group-by")) c.set("group-by",indexedList(c.get("group-by")));
                if(c.path("where").isObject() && c.get("where").has("any-present")) {
                    var where=(com.fasterxml.jackson.databind.node.ObjectNode)c.get("where");
                    where.set("any-present",indexedList(where.get("any-present")));
                }
                normalizeNode(c.get("item"));
            }
        }
    }
    private JsonNode indexedList(JsonNode value) {
        if(value.isArray()) return value;
        if(!value.isObject()) throw new IllegalArgumentException("Expected a list");
        var sorted=new java.util.TreeMap<Integer,JsonNode>();
        value.fields().forEachRemaining(entry->{
            String key=entry.getKey().replace("[","").replace("]","");
            if(!key.matches("0|[1-9][0-9]*")) throw new IllegalArgumentException("Expected list indexes");
            if(sorted.put(Integer.parseInt(key),entry.getValue())!=null) throw new IllegalArgumentException("Duplicate index");
        });
        var array=json.createArrayNode();
        for(var entry:sorted.entrySet()) {
            if(entry.getKey()!=array.size()) throw new IllegalArgumentException("Non-contiguous list indexes");
            array.add(entry.getValue());
        }
        return array;
    }
    private void checkNode(Projection.Node node, int depth, int[] count, String path) {
        if(node==null || depth>32 || ++count[0]>1024) throw new IllegalArgumentException("Projection tree exceeds limits or has no node at " + path);
        int kinds=(node.source()!=null?1:0)+(node.literalJson()!=null?1:0)+(node.object()!=null?1:0)+(node.array()!=null?1:0);
        if(kinds!=1) throw new IllegalArgumentException("Projection node needs exactly one of source, literal-json, object or array");
        if(node.source()!=null) JsonPaths.check(node.source());
        else if(node.required() || node.defaultValue()!=null || node.defaultJson()!=null || node.blankPolicy()!=null || node.transforms()!=null || node.values()!=null)
            throw new IllegalArgumentException("Scalar mapping options require source");
        if(node.defaultJson()!=null) {
            if(node.defaultValue()!=null || node.required()) throw new IllegalArgumentException("default-json requires an optional source without default-value");
            literal(node.defaultJson());
        }
        if(node.transforms()!=null) node.transforms().forEach(transforms::require);
        if(node.literalJson()!=null) literal(node.literalJson());
        checkRules(node.checks());
        if(node.object()!=null) node.object().forEach((name,child)->{
            if(name.isBlank()) throw new IllegalArgumentException("Empty JSON property name");
            checkNode(child,depth+1,count,path+"."+name);
        });
        if(node.array()!=null) for(var collection:node.array()) {
            if(collection.groupBy()!=null) collection.groupBy().forEach(JsonPaths::check);
            var where=collection.where();
            if(where!=null) {
                if(where.anyPresent()!=null) {
                    if(where.anyPresent().isEmpty() || where.source()!=null || where.present()!=null || where.equalsValue()!=null)
                        throw new IllegalArgumentException("any-present cannot be combined with other conditions");
                    where.anyPresent().forEach(JsonPaths::check);
                } else {
                    JsonPaths.check(where.source());
                    if((where.present()!=null)==(where.equalsValue()!=null)) throw new IllegalArgumentException("Choose present or equals-value");
                }
            }
            checkNode(collection.item(),depth+1,count,path+"[]");
        }
    }
    private void checkRules(List<Profile.ContractField> rules) {
        if(rules!=null) for(var rule:rules) {
            JsonPaths.check(rule.path());
            if(rule.type()==null) throw new IllegalArgumentException("Contract type required");
        }
    }
}
