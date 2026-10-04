import com.fc.v2.mapper.auto.TOadsGovFlowMapper;
import com.fc.v2.model.auto.TOadsGovFlow;
import com.fc.v2.service.impl.TOadsGovFlowServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 点位监管办件流转单：推进/回退/改内容/删除四个口子同一终态口径。
 */
@ExtendWith(MockitoExtension.class)
public class TOadsGovFlowServiceImplTest {

    @Mock
    private TOadsGovFlowMapper oadsGovFlowMapper;
    @InjectMocks
    private TOadsGovFlowServiceImpl service;

    private TOadsGovFlow bill(int stage, Integer status) {
        TOadsGovFlow r = new TOadsGovFlow();
        r.setId(1L);
        r.setStage(stage);
        r.setStatus(status);
        r.setDelFlag(0);
        return r;
    }

    @Test
    public void advanceGoesOneStageAtATime() {
        when(oadsGovFlowMapper.selectById(1L)).thenReturn(bill(0, 1));
        TOadsGovFlow r = service.advance(1L, "申报->核准");
        assertEquals(1, r.getStage());
        assertEquals(1, r.getStatus(), "还没到验收关，仍在办");
    }

    @Test
    public void advanceToLastStageSealsBill() {
        when(oadsGovFlowMapper.selectById(1L)).thenReturn(bill(2, 1));
        TOadsGovFlow r = service.advance(1L, "设置->验收");
        assertEquals(3, r.getStage());
        assertEquals(2, r.getStatus(), "走到验收关即封住，处理完立刻判得出");
    }

    @Test
    public void sealedBillRejectsEveryGate() {
        when(oadsGovFlowMapper.selectById(1L)).thenReturn(bill(3, 2));
        assertNull(service.advance(1L, "x"));
        assertNull(service.rollback(1L, "x"), "封住的不许回退");
        assertFalse(service.updateContent(1L, "x"), "封住的不许改内容");
        assertFalse(service.remove(1L), "封住的不许删除");
        verify(oadsGovFlowMapper, never()).updateById(any());
    }

    @Test
    public void rollbackGoesOneStageDownAndHeadStageRejects() {
        when(oadsGovFlowMapper.selectById(1L)).thenReturn(bill(1, 1));
        assertEquals(0, service.rollback(1L, "x").getStage());
        when(oadsGovFlowMapper.selectById(1L)).thenReturn(bill(0, 1));
        assertNull(service.rollback(1L, "x"), "头一关无处可退");
    }

    @Test
    public void removeIsLogicalDelete() {
        when(oadsGovFlowMapper.selectById(1L)).thenReturn(bill(1, 1));
        when(oadsGovFlowMapper.updateById(any())).thenReturn(1);
        assertTrue(service.remove(1L));
        ArgumentCaptor<TOadsGovFlow> saved = ArgumentCaptor.forClass(TOadsGovFlow.class);
        verify(oadsGovFlowMapper).updateById(saved.capture());
        assertEquals(1, saved.getValue().getDelFlag(), "原始单据留着，只逻辑删除");
        verify(oadsGovFlowMapper, never()).deleteById(anyLong());
    }
}
