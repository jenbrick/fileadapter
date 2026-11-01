package com.jenbrick.fileadapter.results;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.*;
import com.jenbrick.fileadapter.delivery.UpsertClient.ServiceIssue;
import java.io.*;
import java.util.*;

/** Opt-in POC response contract. It is not an assumption about the company's real endpoint. */
public final class ScopedResponseReader {
    private ScopedResponseReader() {}
    public static List<ServiceIssue> read(InputStream input)throws IOException {
        var mapper=new ObjectMapper();mapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        mapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16).maxStringLength(4096).build());
        try(var parser=mapper.getFactory().createParser(new LimitedInputStream(input,2_000_000))) {
            JsonNode root=mapper.readTree(parser);
            if(root==null || parser.nextToken()!=null || !root.path("complete").isBoolean() || !root.path("complete").booleanValue()
                    || !root.path("results").isArray() || root.path("results").size()>10_000)
                throw new IOException("Incomplete scoped response");
            var issues=new ArrayList<ServiceIssue>();
            for(var item:root.path("results")) {
                try {
                    var severity=ErrorPolicy.Severity.valueOf(required(item,"severity"));
                    var scope=ErrorPolicy.Scope.valueOf(required(item,"scope"));
                    String code=required(item,"code");
                    if(!code.matches("[A-Z0-9_]{1,64}") || scope==ErrorPolicy.Scope.RECORD
                            || (severity==ErrorPolicy.Severity.HARD && scope!=ErrorPolicy.Scope.FILE)
                            || (severity==ErrorPolicy.Severity.MEDIUM && scope==ErrorPolicy.Scope.FILE))
                        throw new IllegalArgumentException();
                    String contract=scope==ErrorPolicy.Scope.FILE?null:required(item,"contractId");
                    String member=scope==ErrorPolicy.Scope.MEMBER?required(item,"memberGuid"):null;
                    issues.add(new ServiceIssue(severity,scope,contract,member,code));
                } catch(RuntimeException e){throw new IOException("Invalid scoped result fields");}
            }
            return List.copyOf(issues);
        }
    }
    private static String required(JsonNode item,String field) {
        var value=item.get(field);if(value==null || !value.isTextual() || value.asText().isBlank())throw new IllegalArgumentException();return value.asText();
    }
    private static class LimitedInputStream extends FilterInputStream {
        private int left;
        LimitedInputStream(InputStream in,int limit){super(in);left=limit;}
        private void consumed(int size)throws IOException {if(size>0 && (left-=size)<0)throw new IOException("Response exceeds limit");}
        @Override public int read()throws IOException {int b=super.read();if(b>=0)consumed(1);return b;}
        @Override public int read(byte[] b,int off,int len)throws IOException {int n=in.read(b,off,Math.min(len,Math.max(left+1,1)));consumed(n);return n;}
    }
}
