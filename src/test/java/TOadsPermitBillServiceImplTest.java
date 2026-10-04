import com.fc.v2.mapper.auto.TOadsPermitBillMapper;
import com.fc.v2.model.auto.TOadsPermitBill;
import com.fc.v2.service.impl.TOadsPermitBillServiceImpl;
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
 * 设置许可核准单：计票、关次推进、否决、退回与终态口径。
 */
@ExtendWith(MockitoExtension.class)
public class TOadsPermitBillServiceImplTest {

    @Mock
    private TOadsPermitBillMapper oadsPermitBillMapper;
    @InjectMocks
    private TOadsPermitBillServiceImpl service;

    private TOadsPermitBill bill(int node, int mode, Integer need, Integer signed, Integer status) {
        TOadsPermitBill r = new TOadsPermitBill();
        r.setId(1L);
        r.setNodeNo(node);
        r.setSignMode(mode);
        r.setNeedCount(need);
        r.setSignCount(signed);
        r.setStatus(status);
        return r;
    }

    @Test
    public void singleVotePassesNodeThenClearsAndAdvances() {
        // 一名即过：一票即齐，清票掉进下一关，情形回到在签
        when(oadsPermitBillMapper.selectById(1L)).thenReturn(bill(0, 0, null, 0, 0));
        when(oadsPermitBillMapper.updateById(any())).thenReturn(1);
        TOadsPermitBill r = service.approve(1L, "张", "同意");
        assertEquals(1, r.getNodeNo());
        assertEquals(0, r.getSignCount(), "票清空");
        assertEquals(0, r.getStatus(), "下一关还没签，在签");
    }

    @Test
    public void andModeNeedsTwoVotes() {
        // 两关须同判：第一票留在本关在签，第二票齐了才推进
        when(oadsPermitBillMapper.selectById(1L)).thenReturn(bill(0, 1, null, 0, 0));
        when(oadsPermitBillMapper.updateById(any())).thenReturn(1);
        TOadsPermitBill first = service.approve(1L, "张", "同意");
        assertEquals(0, first.getNodeNo(), "票没齐不许跳关");
        assertEquals(1, first.getSignCount());
        assertEquals(0, first.getStatus());

        when(oadsPermitBillMapper.selectById(1L)).thenReturn(bill(0, 1, null, 1, 0));
        TOadsPermitBill second = service.approve(1L, "李", "同意");
        assertEquals(1, second.getNodeNo());
        assertEquals(0, second.getSignCount());
    }

    @Test
    public void lastNodeApprovalSealsPass() {
        when(oadsPermitBillMapper.selectById(1L)).thenReturn(bill(2, 0, null, 0, 0));
        when(oadsPermitBillMapper.updateById(any())).thenReturn(1);
        TOadsPermitBill r = service.approve(1L, "张", "同意");
        assertEquals(2, r.getNodeNo());
        assertEquals(1, r.getStatus(), "末关点齐即已点齐");
    }

    @Test
    public void rejectSetsVetoAndTerminalBlocks() {
        when(oadsPermitBillMapper.selectById(1L)).thenReturn(bill(0, 0, null, 0, 0));
        when(oadsPermitBillMapper.updateById(any())).thenReturn(1);
        TOadsPermitBill r = service.reject(1L, "张", "不同意");
        assertEquals(2, r.getStatus(), "否决当场置已压回，不能压完还看着在签");

        when(oadsPermitBillMapper.selectById(1L)).thenReturn(bill(2, 0, null, 0, 1));
        assertNull(service.approve(1L, "李", "同意"), "已点齐的终态不许再签");
        assertNull(service.reject(1L, "李", "x"), "已点齐不许再压");
    }

    @Test
    public void rollbackResetsVotesAndRunning() {
        when(oadsPermitBillMapper.selectById(1L)).thenReturn(bill(1, 1, 2, 1, 2));
        when(oadsPermitBillMapper.updateById(any())).thenReturn(1);
        TOadsPermitBill r = service.rollback(1L, "退回");
        assertEquals(0, r.getNodeNo());
        assertEquals(0, r.getSignCount(), "退回清票");
        assertEquals(0, r.getStatus(), "被压回的照此复活为在签");
    }
}
