import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TOadsAreaBookMapper;
import com.fc.v2.mapper.auto.TOadsNoticeItemMapper;
import com.fc.v2.mapper.auto.TOadsPostQuotaMapper;
import com.fc.v2.mapper.auto.TOadsRestDayMapper;
import com.fc.v2.mapper.auto.TOadsSpotMapper;
import com.fc.v2.model.auto.TOadsAreaBook;
import com.fc.v2.model.auto.TOadsNoticeItem;
import com.fc.v2.model.auto.TOadsPostQuota;
import com.fc.v2.model.auto.TOadsRestDay;
import com.fc.v2.model.auto.TOadsSpot;
import com.fc.v2.model.custom.OadsNoticeRunResult;
import com.fc.v2.service.ITSysOperLogService;
import com.fc.v2.service.impl.TOadsNoticeItemServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 届期催办与巡查：一轮夜跑的规矩（mapper 全Mock，不落库）。
 */
@ExtendWith(MockitoExtension.class)
public class TOadsNoticeItemServiceImplTest {

    @Mock
    private TOadsNoticeItemMapper oadsNoticeItemMapper;
    @Mock
    private TOadsSpotMapper oadsSpotMapper;
    @Mock
    private TOadsRestDayMapper oadsRestDayMapper;
    @Mock
    private TOadsPostQuotaMapper oadsPostQuotaMapper;
    @Mock
    private TOadsAreaBookMapper oadsAreaBookMapper;
    @Mock
    private ITSysOperLogService operLogService;

    @InjectMocks
    private TOadsNoticeItemServiceImpl service;

    private static Date at(String s) throws Exception {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(s);
    }

    private static TOadsNoticeItem row(long id, String dueAt, String amount) throws Exception {
        TOadsNoticeItem r = new TOadsNoticeItem();
        r.setId(id);
        r.setItemNo("IT" + id);
        r.setDueAt(dueAt == null ? null : at(dueAt));
        r.setAmount(amount == null ? null : new BigDecimal(amount));
        r.setSpotNo("SP" + id);
        r.setContact("联系人" + id);
        r.setAreaNo("DU00");
        r.setDoorSeg("100-199");
        r.setStatus(0);
        r.setDelFlag(0);
        return r;
    }

    @BeforeEach
    public void baseStubs() {
        lenient().when(oadsRestDayMapper.selectList(any())).thenReturn(Collections.emptyList());
        lenient().when(oadsNoticeItemMapper.updateById(any())).thenReturn(1);
        TOadsAreaBook inBook = new TOadsAreaBook();
        inBook.setAreaNo("DU00");
        inBook.setStatus(0);
        lenient().when(oadsAreaBookMapper.selectOne(any())).thenReturn(inBook);
    }

    @Test
    public void runsAnytimeWithoutWindow() throws Exception {
        // 不设执行窗口：白天、深夜按下都照办；没有候选行时都回同一句"本轮无到期"
        when(oadsNoticeItemMapper.selectList(any())).thenReturn(Collections.emptyList());
        OadsNoticeRunResult daytime = service.runOnce(at("2026-10-05 14:00:00"));
        OadsNoticeRunResult night = service.runOnce(at("2026-10-05 23:30:00"));
        assertEquals("本轮无到期", daytime.getMessage());
        assertEquals("本轮无到期", night.getMessage());
        assertFalse(daytime.isChanged());
        verify(oadsNoticeItemMapper, never()).updateById(any());
        // 两个口子汇一处：每一轮都留一条审计
        verify(operLogService, times(2)).insertTSysOperLog(any());
    }

    @Test
    public void noDueRowsAnswersNoDue() throws Exception {
        when(oadsNoticeItemMapper.selectList(any())).thenReturn(Collections.emptyList());
        OadsNoticeRunResult r = service.runOnce(at("2026-10-05 03:00:00"));
        assertEquals("本轮无到期", r.getMessage());
        assertFalse(r.isChanged());
        verify(oadsNoticeItemMapper, never()).updateById(any());
    }

    @Test
    public void dueRowIsDispatchedWithBothMarks() throws Exception {
        TOadsNoticeItem due = row(1L, "2026-10-04 09:00:00", "0");
        TOadsNoticeItem future = row(2L, "2026-11-01 09:00:00", "0");
        when(oadsNoticeItemMapper.selectList(any())).thenReturn(listOf(due, future));
        TOadsSpot spot = new TOadsSpot();
        spot.setSpotNo("SP1");
        spot.setSetNo("POST-A");
        when(oadsSpotMapper.selectOne(any())).thenReturn(spot);
        when(oadsNoticeItemMapper.selectCount(any())).thenReturn(1, 0, 1);

        Date runAt = at("2026-10-05 03:00:00");
        OadsNoticeRunResult r = service.runOnce(runAt);

        ArgumentCaptor<TOadsNoticeItem> saved = ArgumentCaptor.forClass(TOadsNoticeItem.class);
        verify(oadsNoticeItemMapper, times(1)).updateById(saved.capture());
        TOadsNoticeItem done = saved.getValue();
        assertEquals(due.getId(), done.getId(), "未到期的行不许动");
        assertEquals(Integer.valueOf(1), done.getStatus(), "情形挪到已派发");
        assertEquals(runAt, done.getSentAt(), "补上派发那一刻");
        assertEquals("POST-A", done.getSentPost(), "补上接手的岗位");
        assertEquals(1, r.getDispatchedCount());
        assertEquals(0, r.getFailedCount());
        assertEquals(1, r.getPendingCount());
        assertEquals("尚未派发1行，本轮派发1行，记成派不出0行", r.getMessage());
    }

    @Test
    public void exactlyOnTheMomentCountsAsDue() throws Exception {
        TOadsNoticeItem due = row(1L, "2026-10-05 03:00:00", "0");
        when(oadsNoticeItemMapper.selectList(any())).thenReturn(listOf(due));
        TOadsSpot spot = new TOadsSpot();
        spot.setSetNo("POST-A");
        when(oadsSpotMapper.selectOne(any())).thenReturn(spot);
        when(oadsNoticeItemMapper.selectCount(any())).thenReturn(1, 0, 0);

        OadsNoticeRunResult r = service.runOnce(at("2026-10-05 03:00:00"));
        assertEquals(1, r.getDispatchedCount(), "正压在这一瞬也算本轮要办的");
    }

    @Test
    public void advanceDaysSkipRestDays() throws Exception {
        // 截止 10-12 15:00，可提前 3 日；10-09/10-10/10-11 休办 -> 最后时刻 10-06 15:00
        TOadsNoticeItem due = row(1L, "2026-10-12 15:00:00", "3");
        when(oadsNoticeItemMapper.selectList(any())).thenReturn(listOf(due));
        TOadsSpot spot = new TOadsSpot();
        spot.setSetNo("POST-A");
        when(oadsSpotMapper.selectOne(any())).thenReturn(spot);
        when(oadsNoticeItemMapper.selectCount(any())).thenReturn(1, 0, 0);

        TOadsRestDay rest = new TOadsRestDay();
        rest.setRestDay(at("2026-10-09 00:00:00"));
        TOadsRestDay rest2 = new TOadsRestDay();
        rest2.setRestDay(at("2026-10-10 00:00:00"));
        TOadsRestDay rest3 = new TOadsRestDay();
        rest3.setRestDay(at("2026-10-11 00:00:00"));
        when(oadsRestDayMapper.selectList(any())).thenReturn(listOf(rest, rest2, rest3));

        OadsNoticeRunResult early = service.runOnce(at("2026-10-05 03:00:00"));
        assertEquals("本轮无到期", early.getMessage(), "凑不够就不点这一行");
        verify(oadsNoticeItemMapper, never()).updateById(any());

        OadsNoticeRunResult onTime = service.runOnce(at("2026-10-07 03:00:00"));
        assertEquals(1, onTime.getDispatchedCount(), "往满里凑够了就点这一行");
    }

    @Test
    public void invalidRowMarkedFailAndRoundContinues() throws Exception {
        TOadsNoticeItem bad = row(1L, null, "0");          // 截止时刻那一格是空的
        bad.setItemNo(null);                                // 代号也没填
        TOadsNoticeItem good = row(2L, "2026-10-04 09:00:00", "0");
        when(oadsNoticeItemMapper.selectList(any())).thenReturn(listOf(bad, good));
        TOadsSpot spot = new TOadsSpot();
        spot.setSetNo("POST-A");
        when(oadsSpotMapper.selectOne(any())).thenReturn(spot);
        when(oadsNoticeItemMapper.selectCount(any())).thenReturn(1, 1, 0);

        OadsNoticeRunResult r = service.runOnce(at("2026-10-05 03:00:00"));

        ArgumentCaptor<TOadsNoticeItem> saved = ArgumentCaptor.forClass(TOadsNoticeItem.class);
        verify(oadsNoticeItemMapper, times(2)).updateById(saved.capture());
        TOadsNoticeItem failed = saved.getAllValues().get(0);
        assertEquals(Integer.valueOf(2), failed.getStatus(), "站不住的行记成派不出");
        assertEquals("入行校验", failed.getFailStep());
        assertTrue(failed.getFailReason().contains("item_no"), "写明毛病出在哪一格");
        assertEquals(Integer.valueOf(1), saved.getAllValues().get(1).getStatus(), "剩下的行照旧办完");
        assertEquals(1, r.getFailedCount());
        assertEquals(1, r.getDispatchedCount());
    }

    @Test
    public void deregisteredSpotIsUndeliverable() throws Exception {
        TOadsNoticeItem due = row(1L, "2026-10-04 09:00:00", "0");
        when(oadsNoticeItemMapper.selectList(any())).thenReturn(listOf(due));
        when(oadsSpotMapper.selectOne(any())).thenReturn(null);
        when(oadsNoticeItemMapper.selectCount(any())).thenReturn(0, 1, 0);

        OadsNoticeRunResult r = service.runOnce(at("2026-10-05 03:00:00"));

        ArgumentCaptor<TOadsNoticeItem> saved = ArgumentCaptor.forClass(TOadsNoticeItem.class);
        verify(oadsNoticeItemMapper).updateById(saved.capture());
        assertEquals(Integer.valueOf(2), saved.getValue().getStatus());
        assertEquals("点位核验", saved.getValue().getFailStep());
        assertEquals(1, r.getFailedCount(), "点位已从名册上注销：直接记派不出");
    }

    @Test
    public void fullQuotaBlocksDispatch() throws Exception {
        TOadsNoticeItem due = row(1L, "2026-10-04 09:00:00", "0");
        when(oadsNoticeItemMapper.selectList(any()))
                .thenReturn(listOf(due))                       // 挑行
                .thenReturn(listOf(sentRow("POST-A"), sentRow("POST-A"))); // 定额已派件数
        TOadsSpot spot = new TOadsSpot();
        spot.setSetNo("POST-A");
        when(oadsSpotMapper.selectOne(any())).thenReturn(spot);
        TOadsPostQuota quota = new TOadsPostQuota();
        quota.setPostNo("POST-A");
        quota.setQuarterCode("2026Q4");
        quota.setQuotaTotal(2);
        quota.setStatus(0);
        when(oadsPostQuotaMapper.selectOne(any())).thenReturn(quota);
        when(oadsNoticeItemMapper.selectCount(any())).thenReturn(0, 1, 0);

        OadsNoticeRunResult r = service.runOnce(at("2026-10-05 03:00:00"));

        ArgumentCaptor<TOadsNoticeItem> saved = ArgumentCaptor.forClass(TOadsNoticeItem.class);
        verify(oadsNoticeItemMapper).updateById(saved.capture());
        assertEquals(Integer.valueOf(2), saved.getValue().getStatus());
        assertEquals("定额核验", saved.getValue().getFailStep(), "卡住的那一步写在这一行上");
        assertTrue(saved.getValue().getFailReason().contains("POST-A"));
        assertEquals(1, r.getFailedCount());
    }

    @Test
    public void halfDoneRowIsRepairedNotCountedAsNew() throws Exception {
        TOadsNoticeItem half = row(1L, "2026-10-04 09:00:00", "0");
        half.setStatus(1);                                    // 情形挪了
        half.setSentAt(null);                                 // 派发那一刻没落 -> 仍算没办完
        when(oadsNoticeItemMapper.selectList(any())).thenReturn(listOf(half));
        TOadsSpot spot = new TOadsSpot();
        spot.setSetNo("POST-A");
        when(oadsSpotMapper.selectOne(any())).thenReturn(spot);
        when(oadsNoticeItemMapper.selectCount(any())).thenReturn(1, 0, 0);

        Date runAt = at("2026-10-05 03:00:00");
        service.runOnce(runAt);

        ArgumentCaptor<TOadsNoticeItem> saved = ArgumentCaptor.forClass(TOadsNoticeItem.class);
        verify(oadsNoticeItemMapper).updateById(saved.capture());
        assertEquals(runAt, saved.getValue().getSentAt(), "两边记号补齐");
        assertEquals("POST-A", saved.getValue().getSentPost());
    }

    @Test
    public void nullAmountRowFailsAloneAndRoundContinues() throws Exception {
        // amount 缺值的那一行只记自己派不出，不许把整个作业搞崩
        TOadsNoticeItem blankAmount = row(1L, "2026-10-04 09:00:00", null);
        TOadsNoticeItem good = row(2L, "2026-10-04 09:00:00", "0");
        when(oadsNoticeItemMapper.selectList(any())).thenReturn(listOf(blankAmount, good));
        TOadsSpot spot = new TOadsSpot();
        spot.setSetNo("POST-A");
        when(oadsSpotMapper.selectOne(any())).thenReturn(spot);
        when(oadsNoticeItemMapper.selectCount(any())).thenReturn(1, 1, 0);

        OadsNoticeRunResult r = service.runOnce(at("2026-10-05 03:00:00"));

        ArgumentCaptor<TOadsNoticeItem> saved = ArgumentCaptor.forClass(TOadsNoticeItem.class);
        verify(oadsNoticeItemMapper, times(2)).updateById(saved.capture());
        assertEquals(Integer.valueOf(2), saved.getAllValues().get(0).getStatus());
        assertTrue(saved.getAllValues().get(0).getFailReason().contains("amount"));
        assertEquals(Integer.valueOf(1), saved.getAllValues().get(1).getStatus(), "缺值行之后照常办完");
        assertEquals(1, r.getFailedCount());
        assertEquals(1, r.getDispatchedCount());
    }

    @Test
    public void archivedAreaBookBlocksDispatchBothGates() throws Exception {
        // 属地段册已撤出归档：这一道口子拦下，且与点位入册那道口子同一口径
        TOadsNoticeItem due = row(1L, "2026-10-04 09:00:00", "0");
        when(oadsNoticeItemMapper.selectList(any())).thenReturn(listOf(due));
        TOadsAreaBook archived = new TOadsAreaBook();
        archived.setAreaNo("DU00");
        archived.setStatus(1);
        when(oadsAreaBookMapper.selectOne(any())).thenReturn(archived);
        when(oadsNoticeItemMapper.selectCount(any())).thenReturn(0, 1, 0);

        OadsNoticeRunResult r = service.runOnce(at("2026-10-05 03:00:00"));

        ArgumentCaptor<TOadsNoticeItem> saved = ArgumentCaptor.forClass(TOadsNoticeItem.class);
        verify(oadsNoticeItemMapper).updateById(saved.capture());
        assertEquals(Integer.valueOf(2), saved.getValue().getStatus());
        assertEquals("属地段册核验", saved.getValue().getFailStep());
        verify(oadsSpotMapper, never()).selectOne(any());
        assertEquals(1, r.getFailedCount());
    }

    @Test
    public void candidateFilterCarriesStatus() throws Exception {
        when(oadsNoticeItemMapper.selectList(any())).thenReturn(Collections.emptyList());
        service.runOnce(at("2026-10-05 03:00:00"));

        ArgumentCaptor<QueryWrapper<TOadsNoticeItem>> qw = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(oadsNoticeItemMapper).selectList(qw.capture());
        String sql = qw.getValue().getSqlSegment();
        assertTrue(sql.contains("status"), "筛选条件必须带上状态，已办过的不许再捞回来");
        assertTrue(sql.contains("sent_at") && sql.contains("sent_post"), "半拉子按两边记号认");
    }

    private static TOadsNoticeItem sentRow(String post) throws Exception {
        TOadsNoticeItem r = row(99L, "2026-10-01 09:00:00", "0");
        r.setStatus(1);
        r.setSentPost(post);
        r.setSentAt(at("2026-10-02 03:00:00"));
        return r;
    }

    private static <T> List<T> listOf(T... rows) {
        List<T> list = new ArrayList<T>();
        Collections.addAll(list, rows);
        return list;
    }
}
