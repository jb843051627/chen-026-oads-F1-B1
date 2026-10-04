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

    private static TOadsSpot spot(String post) {
        TOadsSpot spot = new TOadsSpot();
        spot.setSpotNo("SP1");
        spot.setAreaId(0);
        spot.setAreaNo("DU00");
        spot.setSetNo(post);
        return spot;
    }

    private static TOadsAreaBook area(String no, int status) {
        TOadsAreaBook a = new TOadsAreaBook();
        a.setId(0L);
        a.setAreaNo(no);
        a.setStatus(status);
        return a;
    }

    @BeforeEach
    public void baseStubs() {
        lenient().when(oadsRestDayMapper.selectList(any())).thenReturn(Collections.emptyList());
        lenient().when(oadsNoticeItemMapper.updateById(any())).thenReturn(1);
        // 默认挂着的属地段册在册（与点位入册口子同一判据）
        lenient().when(oadsAreaBookMapper.selectOne(any())).thenReturn(area("DU00", 0));
    }

    @Test
    public void noDueRowsAnswersNoDueAtAnyHour() throws Exception {
        when(oadsNoticeItemMapper.selectList(any())).thenReturn(Collections.emptyList());
        // 白天、夜里都照跑：没有执行窗口
        assertEquals("本轮无到期", service.runOnce(at("2026-10-05 14:00:00")).getMessage());
        assertEquals("本轮无到期", service.runOnce(at("2026-10-05 03:00:00")).getMessage());
        verify(oadsNoticeItemMapper, never()).updateById(any());
    }

    @Test
    public void nullMomentRunsAsNowInsteadOfSilentSkip() {
        when(oadsNoticeItemMapper.selectList(any())).thenReturn(Collections.emptyList());
        OadsNoticeRunResult r = service.runOnce(null);
        assertEquals("本轮无到期", r.getMessage(), "没给执行时刻按此刻算，不许一行不办");
        assertNotNull(r.getRoundNo());
    }

    @Test
    public void dueRowIsDispatchedInDaytimeWithBothMarks() throws Exception {
        TOadsNoticeItem due = row(1L, "2026-10-04 09:00:00", "0");
        TOadsNoticeItem future = row(2L, "2026-11-01 09:00:00", "0");
        when(oadsNoticeItemMapper.selectList(any())).thenReturn(listOf(due, future));
        when(oadsSpotMapper.selectOne(any())).thenReturn(spot("POST-A"));
        when(oadsNoticeItemMapper.selectCount(any())).thenReturn(1, 0, 1);

        Date runAt = at("2026-10-05 14:00:00"); // 大白天按下，照样办
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
        when(oadsSpotMapper.selectOne(any())).thenReturn(spot("POST-A"));
        when(oadsNoticeItemMapper.selectCount(any())).thenReturn(1, 0, 0);

        OadsNoticeRunResult r = service.runOnce(at("2026-10-05 03:00:00"));
        assertEquals(1, r.getDispatchedCount(), "到期时刻正压在执行这一瞬，也算本轮要办的");
    }

    @Test
    public void advanceDaysSkipRestDays() throws Exception {
        // 截止 10-12 15:00，可提前 3 日；10-09/10-10/10-11 休办 -> 最后时刻 10-06 15:00
        TOadsNoticeItem due = row(1L, "2026-10-12 15:00:00", "3");
        when(oadsNoticeItemMapper.selectList(any())).thenReturn(listOf(due));
        when(oadsSpotMapper.selectOne(any())).thenReturn(spot("POST-A"));
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
        when(oadsSpotMapper.selectOne(any())).thenReturn(spot("POST-A"));
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
    public void missingAmountMarkedFailWithoutCrashingJob() throws Exception {
        TOadsNoticeItem noAmount = row(1L, "2026-10-04 09:00:00", null); // amount 缺值
        TOadsNoticeItem good = row(2L, "2026-10-04 09:00:00", "0");
        when(oadsNoticeItemMapper.selectList(any())).thenReturn(listOf(noAmount, good));
        when(oadsSpotMapper.selectOne(any())).thenReturn(spot("POST-A"));
        when(oadsNoticeItemMapper.selectCount(any())).thenReturn(1, 1, 0);

        OadsNoticeRunResult r = service.runOnce(at("2026-10-05 03:00:00"));

        ArgumentCaptor<TOadsNoticeItem> saved = ArgumentCaptor.forClass(TOadsNoticeItem.class);
        verify(oadsNoticeItemMapper, times(2)).updateById(saved.capture());
        assertEquals(Integer.valueOf(2), saved.getAllValues().get(0).getStatus(), "缺值行记派不出");
        assertTrue(saved.getAllValues().get(0).getFailReason().contains("amount"));
        assertEquals(Integer.valueOf(1), saved.getAllValues().get(1).getStatus(), "整轮没被缺值拖崩");
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
        verify(oadsAreaBookMapper, never()).selectOne(any());
    }

    @Test
    public void archivedAreaBookBlocksDispatchHereToo() throws Exception {
        TOadsNoticeItem due = row(1L, "2026-10-04 09:00:00", "0");
        when(oadsNoticeItemMapper.selectList(any())).thenReturn(listOf(due));
        TOadsSpot sp = spot("POST-A");
        sp.setAreaId(1);
        sp.setAreaNo("DU01");
        when(oadsSpotMapper.selectOne(any())).thenReturn(sp);
        // 点位入册口子已挡住在册段册之外的；催办口子也必须按同一判据拦下
        when(oadsAreaBookMapper.selectOne(any())).thenReturn(area("DU01", 1));
        when(oadsNoticeItemMapper.selectCount(any())).thenReturn(0, 1, 0);

        OadsNoticeRunResult r = service.runOnce(at("2026-10-05 03:00:00"));

        ArgumentCaptor<TOadsNoticeItem> saved = ArgumentCaptor.forClass(TOadsNoticeItem.class);
        verify(oadsNoticeItemMapper).updateById(saved.capture());
        assertEquals(Integer.valueOf(2), saved.getValue().getStatus());
        assertEquals("段册核验", saved.getValue().getFailStep(), "段册已撤出：这一边同样拦下");
        assertTrue(saved.getValue().getFailReason().contains("DU01"));
        assertEquals(1, r.getFailedCount());
    }

    @Test
    public void areaBookMissingTreatedAsArchived() throws Exception {
        TOadsNoticeItem due = row(1L, "2026-10-04 09:00:00", "0");
        when(oadsNoticeItemMapper.selectList(any())).thenReturn(listOf(due));
        when(oadsSpotMapper.selectOne(any())).thenReturn(spot("POST-A"));
        when(oadsAreaBookMapper.selectOne(any())).thenReturn(null);
        when(oadsNoticeItemMapper.selectCount(any())).thenReturn(0, 1, 0);

        service.runOnce(at("2026-10-05 03:00:00"));

        ArgumentCaptor<TOadsNoticeItem> saved = ArgumentCaptor.forClass(TOadsNoticeItem.class);
        verify(oadsNoticeItemMapper).updateById(saved.capture());
        assertEquals("段册核验", saved.getValue().getFailStep(), "底册查无按已撤出处理");
    }

    @Test
    public void fullQuotaBlocksDispatch() throws Exception {
        TOadsNoticeItem due = row(1L, "2026-10-04 09:00:00", "0");
        when(oadsNoticeItemMapper.selectList(any()))
                .thenReturn(listOf(due))                       // 挑行
                .thenReturn(listOf(sentRow("POST-A"), sentRow("POST-A"))); // 定额已派件数
        when(oadsSpotMapper.selectOne(any())).thenReturn(spot("POST-A"));
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
        when(oadsSpotMapper.selectOne(any())).thenReturn(spot("POST-A"));
        when(oadsNoticeItemMapper.selectCount(any())).thenReturn(1, 0, 0);

        Date runAt = at("2026-10-05 03:00:00");
        service.runOnce(runAt);

        ArgumentCaptor<TOadsNoticeItem> saved = ArgumentCaptor.forClass(TOadsNoticeItem.class);
        verify(oadsNoticeItemMapper).updateById(saved.capture());
        assertEquals(runAt, saved.getValue().getSentAt(), "两边记号补齐");
        assertEquals("POST-A", saved.getValue().getSentPost());
    }

    @Test
    public void candidateQueryCarriesStatusAndMarksCondition() throws Exception {
        // 库按带状态的候选条件回空：已派发齐全/已派不出的行不会再被捞回来
        when(oadsNoticeItemMapper.selectList(any())).thenReturn(Collections.emptyList());

        service.runOnce(at("2026-10-05 03:00:00"));

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<TOadsNoticeItem>> cap =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(oadsNoticeItemMapper).selectList(cap.capture());
        String sql = cap.getValue().getSqlSegment();
        assertTrue(sql.contains("status"), "筛选必须带上状态");
        assertTrue(sql.contains("sent_at") && sql.contains("sent_post"), "半拉子按两边记号认");
    }

    @Test
    public void pendingCountUsesSameStatusCaliberAsCandidates() throws Exception {
        TOadsNoticeItem due = row(1L, "2026-10-04 09:00:00", "0");
        when(oadsNoticeItemMapper.selectList(any())).thenReturn(listOf(due));
        when(oadsSpotMapper.selectOne(any())).thenReturn(spot("POST-A"));
        when(oadsNoticeItemMapper.selectCount(any())).thenReturn(5, 0, 5);

        service.runOnce(at("2026-10-05 03:00:00"));

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<TOadsNoticeItem>> cap =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(oadsNoticeItemMapper, times(3)).selectCount(cap.capture());
        // 收尾第三个数（尚未派发）与挑行走同一套状态口径
        String pendingSql = cap.getAllValues().get(2).getSqlSegment();
        assertTrue(pendingSql.contains("status"));
        assertTrue(pendingSql.contains("sent_at"));
    }

    @Test
    public void oneRowThrowingDoesNotKillWholeRound() throws Exception {
        TOadsNoticeItem bad = row(1L, "2026-10-04 09:00:00", "0");
        TOadsNoticeItem good = row(2L, "2026-10-04 10:00:00", "0");
        when(oadsNoticeItemMapper.selectList(any())).thenReturn(listOf(bad, good));
        // 头一行查点位时库抖了，第二行照查照办
        when(oadsSpotMapper.selectOne(any()))
                .thenThrow(new RuntimeException("库抖了一下"))
                .thenReturn(spot("POST-A"));
        when(oadsNoticeItemMapper.selectCount(any())).thenReturn(1, 0, 1);

        OadsNoticeRunResult r = service.runOnce(at("2026-10-05 03:00:00"));

        ArgumentCaptor<TOadsNoticeItem> saved = ArgumentCaptor.forClass(TOadsNoticeItem.class);
        verify(oadsNoticeItemMapper, times(1)).updateById(saved.capture());
        assertEquals(Long.valueOf(2L), saved.getValue().getId(), "剩下的行照常办完");
        assertEquals(Integer.valueOf(1), saved.getValue().getStatus());
        assertEquals(1, r.getDispatchedCount(), "单行异常只留到下一轮，整轮不崩");
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
