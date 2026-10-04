import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fc.v2.mapper.auto.TOadsAreaBookMapper;
import com.fc.v2.mapper.auto.TOadsSpotMapper;
import com.fc.v2.model.auto.TOadsAreaBook;
import com.fc.v2.model.auto.TOadsSpot;
import com.fc.v2.service.impl.TOadsSpotServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 点位底册：轧差口径、段册拦截两个口子一致、逻辑删除、列表尊重条件。
 */
@ExtendWith(MockitoExtension.class)
public class TOadsSpotServiceImplTest {

    @Mock
    private TOadsSpotMapper oadsSpotMapper;
    @Mock
    private TOadsAreaBookMapper oadsAreaBookMapper;
    @InjectMocks
    private TOadsSpotServiceImpl service;

    @BeforeEach
    public void wireBaseMapper() {
        // ServiceImpl 的 baseMapper 是父类字段，@InjectMocks 不按名认，这里手工接上
        ReflectionTestUtils.setField(service, "baseMapper", oadsSpotMapper);
    }

    private TOadsAreaBook book(int id, int status) {
        TOadsAreaBook b = new TOadsAreaBook();
        b.setId((long) id);
        b.setAreaNo("DU00");
        b.setStatus(status);
        return b;
    }

    @Test
    public void lackIsNeedMinusGotAndStatusConsistent() {
        when(oadsAreaBookMapper.selectOne(any())).thenReturn(book(0, 0));
        when(oadsSpotMapper.selectCount(any())).thenReturn(0);
        when(oadsSpotMapper.insert(any())).thenReturn(1);

        TOadsSpot lacking = new TOadsSpot();
        lacking.setSpotNo("SP1");
        lacking.setAreaId(0);
        lacking.setNeedNum(10);
        lacking.setGotNum(4);
        service.insertTOadsSpot(lacking);
        assertEquals(6, lacking.getLackNum(), "还差几处=应设-已核验，不是两者相加");
        assertEquals(2, lacking.getStatus(), "落地了还欠着：缺项");

        TOadsSpot full = new TOadsSpot();
        full.setSpotNo("SP2");
        full.setAreaId(0);
        full.setNeedNum(5);
        full.setGotNum(5);
        service.insertTOadsSpot(full);
        assertEquals(0, full.getLackNum());
        assertEquals(1, full.getStatus(), "轧平：已核齐");

        TOadsSpot fresh = new TOadsSpot();
        fresh.setSpotNo("SP3");
        fresh.setAreaId(0);
        fresh.setNeedNum(5);
        fresh.setGotNum(0);
        service.insertTOadsSpot(fresh);
        assertEquals(0, fresh.getStatus(), "一处没落地：新入册");
    }

    @Test
    public void archivedBookRejectsInsertAndUpdateBothWays() {
        when(oadsAreaBookMapper.selectOne(any())).thenReturn(book(1, 1));
        TOadsSpot s = new TOadsSpot();
        s.setSpotNo("SP1");
        s.setAreaId(1);
        assertEquals(0, service.insertTOadsSpot(s), "已撤出归档的段册挂不上新点位");
        verify(oadsSpotMapper, never()).insert(any());

        TOadsSpot old = new TOadsSpot();
        old.setId(9L);
        old.setAreaId(0);
        old.setDelFlag(0);
        when(oadsSpotMapper.selectById(9L)).thenReturn(old);
        TOadsSpot move = new TOadsSpot();
        move.setId(9L);
        move.setAreaId(1);
        assertEquals(0, service.updateTOadsSpot(move), "改挂到已撤段册：与新入册同一道关拦下");
        verify(oadsSpotMapper, never()).update(any(), any(Wrapper.class));
    }

    @Test
    public void updateRecomputesLackWithSameRule() {
        when(oadsSpotMapper.selectById(9L)).thenReturn(existingSpot(0, 10, 4));
        when(oadsAreaBookMapper.selectOne(any())).thenReturn(book(0, 0));
        when(oadsSpotMapper.update(any(), any(Wrapper.class))).thenReturn(1);

        TOadsSpot patch = new TOadsSpot();
        patch.setId(9L);
        patch.setAreaId(0);
        patch.setGotNum(10);
        int n = service.updateTOadsSpot(patch);
        assertEquals(1, n);
        ArgumentCaptor<TOadsSpot> saved = ArgumentCaptor.forClass(TOadsSpot.class);
        verify(oadsSpotMapper).update(saved.capture(), any(Wrapper.class));
        assertEquals(0, saved.getValue().getLackNum(), "修改口子与新增同一种轧法");
        assertEquals(1, saved.getValue().getStatus());
    }

    @Test
    public void deleteIsLogical() {
        when(oadsSpotMapper.update(any(), any(Wrapper.class))).thenReturn(1);
        service.deleteTOadsSpotById(7L);
        verify(oadsSpotMapper, never()).deleteById(anyLong());
        verify(oadsSpotMapper).update(isNull(), any(Wrapper.class));
    }

    @Test
    public void listRespectsCallerWrapper() {
        List<TOadsSpot> data = Collections.singletonList(new TOadsSpot());
        when(oadsSpotMapper.selectList(any())).thenReturn(data);
        List<TOadsSpot> got = service.selectTOadsSpotList(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>());
        assertSame(data, got, "不偷塞 status=0、不偷分页");
    }

    private TOadsSpot existingSpot(int areaId, int need, int got) {
        TOadsSpot s = new TOadsSpot();
        s.setId(9L);
        s.setSpotNo("SP9");
        s.setAreaId(areaId);
        s.setNeedNum(need);
        s.setGotNum(got);
        s.setDelFlag(0);
        return s;
    }
}
