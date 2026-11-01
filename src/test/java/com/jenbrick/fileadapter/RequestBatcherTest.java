package com.jenbrick.fileadapter;

import com.jenbrick.fileadapter.projection.RequestBatcher;
import com.jenbrick.fileadapter.source.RecordFailure;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RequestBatcherTest {
    record Row(String contract,String member,int number) {}
    final List<Row> rows=List.of(new Row("A","1",1),new Row("B","3",2),
            new Row("A","2",3),new Row("C","4",4),new Row("A","2",5));

    @Test void gathersNonadjacentContractRowsAndPreservesFirstSeenOrder() {
        var result=RequestBatcher.partition(rows,Row::contract,Row::member,1,3,2);
        assertThat(result).hasSize(3);
        assertThat(result.get(0)).extracting(Row::number).containsExactly(1,3,5);
        assertThat(result.get(1)).extracting(Row::contract).containsExactly("B");
    }
    @Test void packsWholeContractsAndFlushesThePartialFinalBatch() {
        var result=RequestBatcher.partition(rows,Row::contract,Row::member,2,3,2);
        assertThat(result).hasSize(2);
        assertThat(result.get(0)).extracting(Row::number).containsExactly(1,3,5,2);
        assertThat(result.get(1)).extracting(Row::number).containsExactly(4);
    }
    @Test void rejectsOversizedContractsRatherThanSplittingOrDroppingMembers() {
        assertThatThrownBy(()->RequestBatcher.partition(rows,Row::contract,Row::member,2,2,2))
                .isInstanceOf(RecordFailure.class).hasMessageContaining("record limit");
        assertThatThrownBy(()->RequestBatcher.partition(rows,Row::contract,Row::member,2,3,1))
                .isInstanceOf(RecordFailure.class).hasMessageContaining("member limit");
    }
    @Test void countsDistinctMembersRatherThanCoverageRows() {
        assertThat(RequestBatcher.partition(rows,Row::contract,Row::member,3,3,2)).hasSize(1);
        assertThat(RequestBatcher.partition(List.<Row>of(),Row::contract,null,1,1,null)).isEmpty();
    }
    @Test void rejectsInvalidLimitsAndMissingKeysWithoutIncludingKeyValues() {
        assertThatThrownBy(()->RequestBatcher.partition(rows,Row::contract,null,0,3,null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->RequestBatcher.partition(rows,r->null,null,1,3,null))
                .hasMessage("Missing request grouping key");
    }
}
