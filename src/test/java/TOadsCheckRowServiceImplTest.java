import com.fc.v2.mapper.auto.TOadsCheckRowMapper;
import com.fc.v2.model.auto.TOadsCheckRow;
import com.fc.v2.service.impl.TOadsCheckRowServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 集中更换核验行：空批、超上限、缺值行退回不拖垮整批、幂等重提。
 */
@ExtendWith(MockitoExtension.class)
public class TOadsCheckRowServiceImplTest {

    @Mock
    private TOadsCheckRowMapper oadsCheckRowMapper;
    @InjectMocks
    private TOadsCheckRowServiceImpl service;

    private TOadsCheckRow row(String code, String qty) {
        TOadsCheckRow r = new TOadsCheckRow();
        r.setItemCode(code);
        r.setQty(qty == null ? null : new BigDecimal(qty));
        return r;
    }

    @Test
    public void emptyBatchReturnsZeroAndTouchesNothing() {
        assertEquals(0, service.submitBatch("B1", new ArrayList<>()));
        assertEquals(0, service.submitBatch("B1", null));
        verify(oadsCheckRowMapper, never()).insert(any());
    }

    @Test
    public void overLimitReturnsMinusOneAndNothingInserted() {
        List<TOadsCheckRow> rows = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            rows.add(row("SP" + i, "1"));
        }
        assertEquals(-1, service.submitBatch("B1", rows));
        verify(oadsCheckRowMapper, never()).insert(any());
    }

    @Test
    public void missingValueRowRejectedButBatchStillAccepted() {
        when(oadsCheckRowMapper.selectCount(any())).thenReturn(0);
        when(oadsCheckRowMapper.insert(any())).thenReturn(1);
        List<TOadsCheckRow> rows = new ArrayList<>();
        rows.add(row(null, "2"));          // 缺值行
        rows.add(row("SP2", null));        // 缺值行
        rows.add(row("SP3", "3"));         // 合法行

        int ok = service.submitBatch("B1", rows);

        assertEquals(1, ok, "只回成功行数");
        ArgumentCaptor<TOadsCheckRow> saved = ArgumentCaptor.forClass(TOadsCheckRow.class);
        verify(oadsCheckRowMapper, times(3)).insert(saved.capture());
        assertEquals(2, saved.getAllValues().get(0).getStatus(), "缺值行记已退回");
        assertEquals(1, saved.getAllValues().get(2).getStatus(), "合法行收下");
        assertEquals(1, saved.getAllValues().get(0).getRowNo(), "原报行号保留/补序");
    }

    @Test
    public void duplicateBatchIsIdempotent() {
        when(oadsCheckRowMapper.selectCount(any())).thenReturn(3, 2);
        int ok = service.submitBatch("B1", java.util.Arrays.asList(row("SP1", "1")));
        assertEquals(2, ok, "重提不重复入库，回已有成功行数");
        verify(oadsCheckRowMapper, never()).insert(any());
    }
}
