package com.jenbrick.fileadapter.projection;

import com.jenbrick.fileadapter.source.RecordFailure;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Function;

/**
 * Gather all rows for each contract, including rows that are not adjacent, and pack complete
 * contracts into bounded requests.
 */
public final class RequestBatcher {
    private RequestBatcher() {}

    public static <T,K,M> List<List<T>> partition(List<T> rows, Function<T,K> groupKey,
            Function<T,M> memberKey, int maxGroups, int maxRecords, Integer maxMembers) {
        if(maxGroups<1 || maxRecords<1 || (memberKey==null)!=(maxMembers==null)
                || (maxMembers!=null && maxMembers<1))
            throw new IllegalArgumentException("Invalid request batching limits");
        // Collect all rows for each contract first. File order does not have to keep a contract
        // together.
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
            // A contract over its limit is rejected rather than split into requests with incomplete
            // member or payer data.
            if(group.size()>maxRecords) throw new RecordFailure("Contract/group record limit exceeded");
            if(memberKey!=null) {
                // Count distinct members. Repeated payer or coverage rows are still the same member.
                var members=new LinkedHashSet<M>();
                for(var row:group) {
                    M member=memberKey.apply(row);
                    if(member==null) throw new RecordFailure("Missing request member key");
                    members.add(member);
                }
                if(members.size()>maxMembers) throw new RecordFailure("Contract/group member limit exceeded");
            }
            pending.addAll(group);
            // Flush after the configured number of complete contracts.
            if(++count==maxGroups) {
                result.add(List.copyOf(pending));
                pending=new ArrayList<>();count=0;
            }
        }
        // Keep the last partially filled batch; it still contains complete contracts.
        if(!pending.isEmpty()) result.add(List.copyOf(pending));
        return List.copyOf(result);
    }
}
