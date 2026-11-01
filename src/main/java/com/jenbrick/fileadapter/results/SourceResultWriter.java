package com.jenbrick.fileadapter.results;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jenbrick.fileadapter.config.*;
import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import javax.xml.namespace.QName;
import javax.xml.stream.*;
import org.apache.commons.csv.CSVFormat;
import org.springframework.stereotype.Component;

/** Format adapter for customer results. Preserve source values, not output/enriched consent fields. */
@Component
public class SourceResultWriter {
    public record Row(ResultStore.Source source,String code,String remarks) {}
    private final ObjectMapper json;
    public SourceResultWriter(ObjectMapper json){this.json=json;}
    public void write(Path target,Profile.Layout layout,ErrorPolicy policy,List<Row> rows) throws IOException {
        try(var out=new OutputStreamWriter(Files.newOutputStream(target),Charset.forName(layout.charset()).newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT))) {
            switch(layout.format()) {
                case DELIMITED -> delimited(out,layout,policy,rows);
                case FIXED_WIDTH -> fixed(out,layout,policy,rows);
                case JSON -> json(out,layout,policy,rows);
                case XML -> xml(out,layout,policy,rows);
            }
        }
    }
    private ObjectNode record(Row row,ErrorPolicy p)throws IOException {
        var data=(ObjectNode)json.readTree(row.source().payload());
        if(data.has(p.codeField()) || data.has(p.remarksField())) throw new IOException("Result field collides with original source field");
        data.put(p.codeField(),row.code());data.put(p.remarksField(),row.remarks());return data;
    }
    private void delimited(Writer out,Profile.Layout layout,ErrorPolicy p,List<Row> rows)throws IOException {
        var headers=new ArrayList<String>();
        if(!rows.isEmpty()) json.readTree(rows.get(0).source().payload()).fieldNames().forEachRemaining(headers::add);
        else if(layout.columns()!=null) headers.addAll(layout.columns());
        if(headers.contains(p.codeField()) || headers.contains(p.remarksField()))throw new IOException("Result field collision");
        headers.add(p.codeField());headers.add(p.remarksField());
        var format=CSVFormat.RFC4180.builder().setDelimiter(layout.delimiter()).get();
        try(var printer=new org.apache.commons.csv.CSVPrinter(out,format)) {
            if(layout.headerMode()==Profile.HeaderMode.FIRST_RECORD)printer.printRecord(headers);
            for(var row:rows) {
                var data=record(row,p);var values=new ArrayList<String>();
                for(String key:headers) values.add(data.path(key).asText());
                printer.printRecord(values);
            }
        }
    }
    private void fixed(Writer out,Profile.Layout layout,ErrorPolicy p,List<Row> rows)throws IOException {
        int width=LayoutValidator.recordLength(layout.fixedWidth());
        for(var row:rows) {
            String raw=row.source().rawText();
            if(raw==null || raw.codePointCount(0,raw.length())!=width) throw new IOException("Original fixed-width record unavailable");
            out.write(raw);out.write(pad(row.code(),p.codeWidth()));out.write(pad(row.remarks(),p.remarksWidth()));out.write("\n");
        }
    }
    private String pad(String value,int width)throws IOException {
        int length=value.codePointCount(0,value.length());
        if(length>width || value.contains("\n") || value.contains("\r"))throw new IOException("Result exceeds fixed-width field");
        return value+" ".repeat(width-length);
    }
    private void json(Writer out,Profile.Layout layout,ErrorPolicy p,List<Row> rows)throws IOException {
        switch(layout.json().mode()) {
            case ARRAY -> {var array=json.createArrayNode();for(var row:rows)array.add(record(row,p));out.write(json.writeValueAsString(array));out.write("\n");}
            case LINES -> {for(var row:rows){out.write(json.writeValueAsString(record(row,p)));out.write("\n");}}
            case OBJECT -> {
                if(rows.size()!=1)throw new IOException("JSON OBJECT results require exactly one selected source record");
                out.write(json.writeValueAsString(record(rows.get(0),p)));out.write("\n");
            }
        }
    }
    private void xml(Writer out,Profile.Layout layout,ErrorPolicy p,List<Row> rows)throws IOException {
        try {
            var factory=XMLOutputFactory.newDefaultFactory();factory.setProperty(XMLOutputFactory.IS_REPAIRING_NAMESPACES,true);
            var writer=factory.createXMLStreamWriter(out);writer.writeStartDocument(layout.charset(),"1.0");
            var path=XmlNames.recordPath(layout.xml());
            if(path.size()==1 && rows.size()!=1)throw new IOException("Single-root XML record cannot represent several selected records");
            for(int i=0;i<path.size()-1;i++)start(writer,path.get(i));
            for(var row:rows)element(writer,path.get(path.size()-1),record(row,p),layout.xml());
            for(int i=0;i<path.size()-1;i++)writer.writeEndElement();
            writer.writeEndDocument();writer.flush();
        } catch(XMLStreamException e){throw new IOException("Cannot write XML results",e);}
    }
    private void start(XMLStreamWriter w,QName name)throws XMLStreamException {
        if(name.getNamespaceURI().isEmpty()) {
            String inherited=w.getNamespaceContext().getNamespaceURI("");
            w.writeStartElement("",name.getLocalPart(),"");
            if(inherited!=null && !inherited.isEmpty())w.writeDefaultNamespace("");
        }
        else w.writeStartElement("",name.getLocalPart(),name.getNamespaceURI());
    }
    private QName name(String key,Profile.Xml options)throws IOException {
        if(key.startsWith("{")) {int end=key.indexOf('}');if(end<1)throw new IOException("Invalid XML key");return new QName(key.substring(1,end),key.substring(end+1));}
        int colon=key.indexOf(':');if(colon<0)return new QName(key);
        String prefix=key.substring(0,colon);String uri=prefix.equals("xml")?javax.xml.XMLConstants.XML_NS_URI:options.namespaces()==null?null:options.namespaces().get(prefix);
        if(uri==null)throw new IOException("Unknown XML namespace key");return new QName(uri,key.substring(colon+1),prefix);
    }
    private void element(XMLStreamWriter w,QName name,JsonNode value,Profile.Xml options)throws XMLStreamException,IOException {
        if(value.isArray()){for(var child:value)element(w,name,child,options);return;}
        start(w,name);
        if(value.isNull()){w.writeAttribute("xsi",javax.xml.XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI,"nil","true");}
        else if(value.isObject()) {
            var fields=value.fields();
            while(fields.hasNext()){var f=fields.next();if(f.getKey().startsWith("@")) {
                var attr=name(f.getKey().substring(1),options);
                if(attr.getNamespaceURI().isEmpty())w.writeAttribute(attr.getLocalPart(),f.getValue().asText());
                else w.writeAttribute(attr.getPrefix(),attr.getNamespaceURI(),attr.getLocalPart(),f.getValue().asText());
            }}
            fields=value.fields();while(fields.hasNext()){var f=fields.next();if(f.getKey().equals("#text"))w.writeCharacters(f.getValue().asText());else if(!f.getKey().startsWith("@"))element(w,name(f.getKey(),options),f.getValue(),options);}
        } else w.writeCharacters(value.asText());
        w.writeEndElement();
    }
}
