package com.jenbrick.fileadapter.projection;

import com.jenbrick.fileadapter.source.RecordFailure;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Function;

/** Complete groups in first-seen order, including nonadjacent rows. No business schema. */
public final class RequestBatcher {
    private RequestBatcher() {}

    public static <T,K,M> List<List<T>> partition(List<T> rows, Function<T,K> groupKey,
            Function<T,M> memberKey, int maxGroups, int maxRecords, Integer maxMembers) {
        if(maxGroups<1 || maxRecords<1 || (memberKey==null)!=(maxMembers==null)
                || (maxMembers!=null && maxMembers<1))
            throw new IllegalArgumentException("Invalid request batching limits");
        var groups=new LinkedHashMap<K,List<T>>();
        for(var row:rows) {
            K key=groupKey.apply(row);
            if(key==null) throw new RecordFailure("Missing request grouping key");
            groups.computeIfAbsent(key,k->new ArrayList<>()).add(row);
        }
        var result=new ArrayList<List<T>>();
        var pending=new ArrayList<T>();
        int count=0;
        for(var group:groups.values()) {
            if(group.size()>maxRecords) throw new RecordFailure("Contract/group record limit exceeded");
            if(memberKey!=null) {
                var members=new LinkedHashSet<M>();
                for(var row:group) {
                    M member=memberKey.apply(row);
                    if(member==null) throw new RecordFailure("Missing request member key");
                    members.add(member);
                }
                if(members.size()>maxMembers) throw new RecordFailure("Contract/group member limit exceeded");
            }
            pending.addAll(group);
            if(++count==maxGroups) {
                result.add(List.copyOf(pending));
                pending=new ArrayList<>();count=0;
            }
        }
        if(!pending.isEmpty()) result.add(List.copyOf(pending));
        return List.copyOf(result);
    }
}
