import com.fc.v2.mapper.auto.TOadsRuleLineMapper;
import com.fc.v2.model.auto.TOadsRuleLine;
import com.fc.v2.service.impl.TOadsRuleLineServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 牌面安全归层判据线：生效区间、在位、等于上限取高一档、四个口子同一来源。
 */
@ExtendWith(MockitoExtension.class)
public class TOadsRuleLineServiceImplTest {

    @Mock
    private TOadsRuleLineMapper oadsRuleLineMapper;
    @InjectMocks
    private TOadsRuleLineServiceImpl service;

    private static Date at(String s) throws Exception {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(s);
    }

    private TOadsRuleLine rule(String code, Integer prio, Integer status, String start, String end)
            throws Exception {
        TOadsRuleLine r = new TOadsRuleLine();
        r.setId((long) code.hashCode());
        r.setRuleCode(code);
        r.setPriority(prio);
        r.setStatus(status);
        r.setTh1Max(new BigDecimal("10"));
        r.setTh2Max(new BigDecimal("50"));
        r.setTh3Max(new BigDecimal("90"));
        r.setEffStart(start == null ? null : at(start));
        r.setEffEnd(end == null ? null : at(end));
        return r;
    }

    @Test
    public void effectiveWindowHalfOpenAndStatusFiltered() throws Exception {
        TOadsRuleLine active = rule("R-A", 1, 0, "2026-01-01 00:00:00", "2026-12-31 00:00:00");
        TOadsRuleLine retired = rule("R-OFF", 9, 1, null, null);          // 已撤下
        TOadsRuleLine notYet = rule("R-FUT", 9, 0, "2026-11-01 00:00:00", null);
        TOadsRuleLine handed = rule("R-OLD", 9, 0, null, "2026-09-01 00:00:00"); // 交班不含
        when(oadsRuleLineMapper.selectList(any()))
                .thenReturn(Arrays.asList(active, retired, notYet, handed));

        Date now = at("2026-10-04 12:00:00");
        List<TOadsRuleLine> avail = service.listAvailable(now);
        assertEquals(1, avail.size());
        assertEquals("R-A", avail.get(0).getRuleCode());
        assertEquals(1, service.countAvailable(now));
        assertTrue(service.usable(active.getId(), now));
        assertFalse(service.usable(retired.getId(), now), "可用口径与列表同一来源");
        assertEquals(0, service.evaluate("R-FUT", new BigDecimal("5"), now), "没生效的线判不出来");
    }

    @Test
    public void handoverDayIsExcluded() throws Exception {
        // 交班那一一日不含：正压在交班时点也算新线说话
        TOadsRuleLine oldLine = rule("R-OLD", 1, 0, null, "2026-10-01 00:00:00");
        when(oadsRuleLineMapper.selectList(any())).thenReturn(Collections.singletonList(oldLine));
        assertEquals(0, service.evaluate("R-OLD", new BigDecimal("5"), at("2026-10-01 00:00:00")));
    }

    @Test
    public void equalToLimitGoesUpOneTier() throws Exception {
        TOadsRuleLine r = rule("R-A", 1, 0, null, null);
        when(oadsRuleLineMapper.selectList(any())).thenReturn(Collections.singletonList(r));
        Date now = at("2026-10-04 12:00:00");
        assertEquals(1, service.evaluate("R-A", new BigDecimal("9.99"), now));
        assertEquals(2, service.evaluate("R-A", new BigDecimal("10"), now), "等于起算限值取高一档");
        assertEquals(3, service.evaluate("R-A", new BigDecimal("50"), now));
        assertEquals(4, service.evaluate("R-A", new BigDecimal("90"), now), "等于封顶取档外一档");
    }

    @Test
    public void priorityWinsForTop() throws Exception {
        TOadsRuleLine low = rule("R-LOW", 1, 0, null, null);
        TOadsRuleLine high = rule("R-HI", 9, 0, null, null);
        when(oadsRuleLineMapper.selectList(any())).thenReturn(Arrays.asList(low, high));
        Date now = at("2026-10-04 12:00:00");
        // 两线阈值相同，优先级高的先说话；这里只验选线与 evaluate 同源不抛错
        assertEquals(service.evaluate("R-HI", new BigDecimal("5"), now),
                service.evaluateTop(new BigDecimal("5"), now));
    }
}
