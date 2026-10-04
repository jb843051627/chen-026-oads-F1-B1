package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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
import com.fc.v2.model.auto.TSysOperLog;
import com.fc.v2.model.custom.OadsNoticeRunResult;
import com.fc.v2.service.ITOadsNoticeItemService;
import com.fc.v2.service.ITSysOperLogService;
import com.fc.v2.util.OadsWorkdayUtil;

/**
 * 届期催办与巡查条目 Service业务层处理（scheduling-job 形状：周期执行）
 *
 * 夜里替人翻《到期催办与现场巡查清单》，一行一事，落到谁名下归谁去做。
 * 读时刻只用 {@link OadsWorkdayUtil} 这一把尺，挑行与回写两边同一种解析。
 *
 * 不设执行窗口：定时调度与执行键两个口子都汇到 {@link #runOnce(Date)} 这一处，
 * 什么时辰触发都照办；候选行、待办计数、收尾三个数也只认同一段筛选口径。
 *
 * @author fuce
 * @date 2026-10-04
 */
@Service
public class TOadsNoticeItemServiceImpl implements ITOadsNoticeItemService {

    private static final Logger log = LoggerFactory.getLogger(TOadsNoticeItemServiceImpl.class);

    /** 审计留痕用的操作人：两个口子都汇到这一处，不区分来路 */
    private static final String AUDIT_OPER = "system";
    /** 审计留痕用的方法名：日后回查，口径出处就是这一处 */
    private static final String AUDIT_METHOD = "TOadsNoticeItemServiceImpl.runOnce";

    /** 一轮最多处理行数：挑中超过五百行只办前五百，其余挪到下一轮 */
    private static final int MAX_ROWS = 500;
    /** 翻清单的页大小：分页翻，不许一口气读穿整张清单 */
    private static final int PAGE_SIZE = 200;
    /** 扫描上界（日）：截止时刻超出此刻这许多日的行必未到期，扫到即收 */
    private static final int SCAN_BOUND_DAYS = OadsWorkdayUtil.MAX_ADVANCE_DAYS + 4;

    /** 搁着（未启动） */
    private static final int STATUS_WAIT = 0;
    /** 已派发 */
    private static final int STATUS_DONE = 1;
    /** 派不出 */
    private static final int STATUS_FAIL = 2;

    @javax.annotation.Resource
    private TOadsNoticeItemMapper oadsNoticeItemMapper;
    @javax.annotation.Resource
    private TOadsSpotMapper oadsSpotMapper;
    @javax.annotation.Resource
    private TOadsRestDayMapper oadsRestDayMapper;
    @javax.annotation.Resource
    private TOadsPostQuotaMapper oadsPostQuotaMapper;
    @javax.annotation.Resource
    private TOadsAreaBookMapper oadsAreaBookMapper;
    @javax.annotation.Resource
    private ITSysOperLogService operLogService;

    @Override
    public TOadsNoticeItem selectTOadsNoticeItemById(Long id) {
        return this.oadsNoticeItemMapper.selectById(id);
    }

    @Override
    public synchronized OadsNoticeRunResult runOnce(Date at) {
        // 不设执行窗口：什么时辰触发都照办；空时刻按此刻办
        if (at == null) {
            at = new Date();
        }
        String roundNo = "R" + OadsWorkdayUtil.ts(at).replaceAll("[-: ]", "");
        Set<LocalDate> restDays = loadRestDays();

        // 二三四、挑行：到期的（含可提前几日往满里凑够的）与站不住的，挑满五百行即收
        List<TOadsNoticeItem> picked = pickRows(at, restDays);
        if (picked.isEmpty()) {
            // 一行也没挑中：平常日子，回一句本轮无到期即收工——这也是一轮，照留原始记录
            OadsNoticeRunResult none = OadsNoticeRunResult.noDue(roundNo);
            audit(roundNo, none.getMessage(), null);
            return none;
        }

        // 五、并成一拨：同一属地段册加同一门牌段为一拨；
        // 一拨从头到尾办完才许起下一拨，同拨的两行不拆进两拨
        Map<String, List<TOadsNoticeItem>> batches = new LinkedHashMap<String, List<TOadsNoticeItem>>();
        for (TOadsNoticeItem row : picked) {
            String key = nvl(row.getAreaNo()) + "" + nvl(row.getDoorSeg());
            List<TOadsNoticeItem> batch = batches.get(key);
            if (batch == null) {
                batch = new ArrayList<TOadsNoticeItem>();
                batches.put(key, batch);
            }
            batch.add(row);
        }
        for (List<TOadsNoticeItem> batch : batches.values()) {
            for (TOadsNoticeItem row : batch) {
                try {
                    processRow(row, at, roundNo, restDays);
                } catch (Exception e) {
                    // 单行出岔子只记这一行，留到下一轮重办；不许一条臭鱼搞崩整轮
                    log.warn("届期催办与巡查：行[{}]本轮没办成，原样留到下一轮", row.getId(), e);
                }
            }
        }

        // 九、收尾交三个数：不许临时凑，回清单逐行数出；筛选口径与候选行同一个来源
        long dispatched = countDispatched(roundNo);
        long failed = countFailed(roundNo);
        long pending = countPending();
        OadsNoticeRunResult result = OadsNoticeRunResult.of(roundNo, pending, dispatched, failed);
        // 审计留痕：轮次、三个数、哪一段卡住都能从这一条起头回查
        audit(roundNo, result.getMessage(), null);
        return result;
    }

    /**
     * 挑行：分页翻清单（按截止时刻先后、同一时刻再按代号由小到大），
     * 站不住的行与到期的行都挑中，挑满五百行即收，其余挪到下一轮。
     * 上一轮已经派出去的行（两边记号齐全）不在候选里，不当成新事再办一遍。
     */
    private List<TOadsNoticeItem> pickRows(Date at, Set<LocalDate> restDays) {
        List<TOadsNoticeItem> picked = new ArrayList<TOadsNoticeItem>();
        LocalDateTime bound = OadsWorkdayUtil.toLocal(at).plusDays(SCAN_BOUND_DAYS);
        int offset = 0;
        while (picked.size() < MAX_ROWS) {
            List<TOadsNoticeItem> page = oadsNoticeItemMapper.selectList(candidateWrapper()
                    .last("LIMIT " + offset + ", " + PAGE_SIZE));
            if (page == null || page.isEmpty()) {
                break;
            }
            boolean beyond = false;
            for (TOadsNoticeItem row : page) {
                if (invalidField(row) != null) {
                    picked.add(row);
                } else {
                    LocalDateTime due = OadsWorkdayUtil.toLocal(row.getDueAt());
                    if (due.isAfter(bound)) {
                        // 再往后翻全是未到期的，不必再读
                        beyond = true;
                        break;
                    }
                    if (isDue(row, at, restDays)) {
                        picked.add(row);
                    }
                }
                if (picked.size() >= MAX_ROWS) {
                    break;
                }
            }
            if (beyond || page.size() < PAGE_SIZE) {
                break;
            }
            offset += PAGE_SIZE;
        }
        return picked;
    }

    /**
     * 候选行口径：未删的行里，只挑还没办完的——搁着的，加上只办了一半的
     * （情形挪到了已派发、派发记号却没补齐，仍算没办完）。
     * 已经办齐的（两边记号齐全）与记成派不出的都不再捞回来。
     */
    private QueryWrapper<TOadsNoticeItem> candidateWrapper() {
        QueryWrapper<TOadsNoticeItem> qw = new QueryWrapper<TOadsNoticeItem>();
        applyPendingScope(qw);
        qw.orderByAsc("due_at").orderByAsc("item_no").orderByAsc("id");
        return qw;
    }

    /**
     * "还算待办"的唯一口径：未删 且 （搁着/情形空 或 只办了一半）。
     * 候选筛选与"尚未派发"计数都走这一段，两边不许各说各的。
     */
    private void applyPendingScope(QueryWrapper<TOadsNoticeItem> qw) {
        qw.and(w -> w.eq("del_flag", 0).or().isNull("del_flag"));
        qw.and(w -> w.eq("status", STATUS_WAIT).or().isNull("status")
                .or(h -> h.eq("status", STATUS_DONE)
                        .and(m -> m.isNull("sent_at").or().isNull("sent_post"))));
    }

    /** 二四、什么算到期：最后时刻早于本次执行、或正压在这一瞬，都算本轮要办的 */
    private boolean isDue(TOadsNoticeItem row, Date at, Set<LocalDate> restDays) {
        int days = OadsWorkdayUtil.advanceDays(row.getAmount());
        LocalDateTime trigger = OadsWorkdayUtil.trigger(row.getDueAt(), days, restDays);
        return !trigger.isAfter(OadsWorkdayUtil.toLocal(at));
    }

    /**
     * 办一行。办成要留下两边记号：情形从未启动挪到已派发，补上派发那一刻与
     * 接手的岗位——三处落在同一次回写里，少任何一处这行就算没办完。
     */
    private void processRow(TOadsNoticeItem row, Date at, String roundNo, Set<LocalDate> restDays) {
        // 七、单行站不住：当场记成派不出，写明毛病出在哪一格
        String bad = invalidField(row);
        if (bad != null) {
            markFail(row, roundNo, "入行校验", bad);
            return;
        }
        // 日子还没走到的原样搁着，让它自己到期
        if (!isDue(row, at, restDays)) {
            return;
        }
        // 属地段册核验：所挂属地段册必须仍在册；已撤出归档的段册不许再派。
        // 与点位底册新入册时的段册拦截是同一道关，两口子谁都不能漏。
        if (isBlank(row.getAreaNo())) {
            markFail(row, roundNo, "属地段册核验", "属地段册代号(area_no)没填");
            return;
        }
        TOadsAreaBook areaBook = oadsAreaBookMapper.selectOne(new QueryWrapper<TOadsAreaBook>()
                .eq("area_no", row.getAreaNo()).eq("del_flag", 0));
        if (areaBook == null) {
            markFail(row, roundNo, "属地段册核验",
                    "属地段册[" + row.getAreaNo() + "]没在属地段册底册上登记");
            return;
        }
        if (areaBook.getStatus() != null && areaBook.getStatus() != 0) {
            markFail(row, roundNo, "属地段册核验",
                    "属地段册[" + row.getAreaNo() + "]已撤出归档，不许再派");
            return;
        }
        // 八、两种天生办不成之一：点位已从名册上注销
        if (isBlank(row.getSpotNo())) {
            markFail(row, roundNo, "点位核验", "所对点位码(spot_no)没填");
            return;
        }
        TOadsSpot spot = oadsSpotMapper.selectOne(new QueryWrapper<TOadsSpot>()
                .eq("spot_no", row.getSpotNo()).eq("del_flag", 0));
        if (spot == null) {
            markFail(row, roundNo, "点位核验", "点位[" + row.getSpotNo() + "]已从名册上注销");
            return;
        }
        // 八、两种天生办不成之二：联系栏空着好几季没人补
        if (isBlank(row.getContact())) {
            Date touched = row.getUpdateTime() != null ? row.getUpdateTime() : row.getCreateTime();
            if (touched != null && !OadsWorkdayUtil.toLocal(touched)
                    .plusMonths(OadsWorkdayUtil.CONTACT_STALE_MONTHS)
                    .isAfter(OadsWorkdayUtil.toLocal(at))) {
                markFail(row, roundNo, "联系核验", "联系栏(contact)空着好几季没人补");
                return;
            }
            // 空着还没到季数：原样搁着，等人来补
            return;
        }
        // 落到谁名下：点位底册上写定的承办岗
        String post = spot.getSetNo();
        if (isBlank(post)) {
            markFail(row, roundNo, "岗位落定", "点位底册未登记承办岗(set_no)");
            return;
        }
        // 六、定额：本季已装满的当场记派不出，卡住的那一步连同原因一并写上，
        // 日后照着这个原因还能把这一批重排一次
        if (quotaFull(post, at)) {
            markFail(row, roundNo, "定额核验",
                    "岗位[" + post + "]" + OadsWorkdayUtil.quarterCode(at) + "季定额已装满");
            return;
        }
        // 八、两边记号一次落齐
        row.setStatus(STATUS_DONE);
        row.setSentAt(at);
        row.setSentPost(post);
        row.setRoundNo(roundNo);
        oadsNoticeItemMapper.updateById(row);
    }

    /** 记成派不出：情形挪到发不成，卡住的那一步连同原因一并写在这一行上 */
    private void markFail(TOadsNoticeItem row, String roundNo, String step, String reason) {
        row.setStatus(STATUS_FAIL);
        row.setFailStep(step);
        row.setFailReason(reason);
        row.setRoundNo(roundNo);
        oadsNoticeItemMapper.updateById(row);
        // 卡住的这一步与口径当场留痕，日后回查不用再猜
        audit(roundNo, "行[" + row.getId() + "]记成派不出：" + step + "：" + reason, reason);
    }

    /** 七、单行站不住的情形：返回毛病出在哪一格，站得住返回 null */
    private String invalidField(TOadsNoticeItem row) {
        if (isBlank(row.getItemNo())) {
            return "代号(item_no)压根没填";
        }
        if (row.getDueAt() == null) {
            return "截止时刻(due_at)那一格是空的";
        }
        BigDecimal amount = row.getAmount();
        if (amount == null) {
            // 缺值只让这一行站不住，不许拖垮整轮作业
            return "可提前几日(amount)那一格是空的";
        }
        if (amount.signum() < 0) {
            return "可提前几日(amount)是负数";
        }
        if (amount.compareTo(BigDecimal.valueOf(OadsWorkdayUtil.MAX_ADVANCE_DAYS)) > 0) {
            return "可提前几日(amount)大得没有边界";
        }
        return null;
    }

    /** 六、定额核验：这个岗位本季的派发件数是否已装满（没下定额则不设限） */
    private boolean quotaFull(String post, Date at) {
        String quarter = OadsWorkdayUtil.quarterCode(at);
        TOadsPostQuota quota = oadsPostQuotaMapper.selectOne(new QueryWrapper<TOadsPostQuota>()
                .eq("post_no", post).eq("quarter_code", quarter).eq("del_flag", 0));
        if (quota == null || quota.getQuotaTotal() == null
                || (quota.getStatus() != null && quota.getStatus() != 0)) {
            return false;
        }
        // 本季已派件数：只数两边记号齐全的已派发行，逐行数出
        List<TOadsNoticeItem> sent = oadsNoticeItemMapper.selectList(new QueryWrapper<TOadsNoticeItem>()
                .eq("status", STATUS_DONE).eq("sent_post", post).isNotNull("sent_at")
                .and(w -> w.eq("del_flag", 0).or().isNull("del_flag")));
        long used = 0;
        for (TOadsNoticeItem s : sent) {
            if (s.getSentAt() != null && quarter.equals(OadsWorkdayUtil.quarterCode(s.getSentAt()))) {
                used++;
            }
        }
        return used >= quota.getQuotaTotal();
    }

    /** 休办日历：法定节假日与市里下文停办的日子，只认这一张表 */
    private Set<LocalDate> loadRestDays() {
        Set<LocalDate> days = new HashSet<LocalDate>();
        List<TOadsRestDay> list = oadsRestDayMapper.selectList(
                new QueryWrapper<TOadsRestDay>().eq("del_flag", 0));
        if (list != null) {
            for (TOadsRestDay r : list) {
                if (r.getRestDay() != null) {
                    days.add(OadsWorkdayUtil.toLocal(r.getRestDay()).toLocalDate());
                }
            }
        }
        return days;
    }

    /** 本轮派发几行：两边记号齐全才算办成 */
    private long countDispatched(String roundNo) {
        return count(new QueryWrapper<TOadsNoticeItem>()
                .eq("status", STATUS_DONE).eq("round_no", roundNo)
                .isNotNull("sent_at").isNotNull("sent_post")
                .and(w -> w.eq("del_flag", 0).or().isNull("del_flag")));
    }

    /** 记成派不出几行 */
    private long countFailed(String roundNo) {
        return count(new QueryWrapper<TOadsNoticeItem>()
                .eq("status", STATUS_FAIL).eq("round_no", roundNo)
                .and(w -> w.eq("del_flag", 0).or().isNull("del_flag")));
    }

    /** 尚未派发的还有几行：与候选行同一个筛选口径 */
    private long countPending() {
        QueryWrapper<TOadsNoticeItem> qw = new QueryWrapper<TOadsNoticeItem>();
        applyPendingScope(qw);
        return count(qw);
    }

    private long count(QueryWrapper<TOadsNoticeItem> qw) {
        Integer c = oadsNoticeItemMapper.selectCount(qw);
        return c == null ? 0L : c.longValue();
    }

    /**
     * 审计留痕：定时调度与执行键两个口子的每一轮都从这一处落同一张操作日志，
     * 出了事故照轮次码就能翻出当时的原话；留痕本身出岔子只记日志，不回灌作业。
     */
    private void audit(String roundNo, String message, String errorMsg) {
        try {
            TSysOperLog operLog = new TSysOperLog();
            operLog.setTitle("届期催办与巡查");
            operLog.setMethod(AUDIT_METHOD);
            operLog.setOperName(AUDIT_OPER);
            // 轮次码与本轮原话同行，日后照轮次回查就是这一次的口径
            operLog.setOperParam("轮次[" + roundNo + "] " + message);
            operLog.setErrorMsg(errorMsg);
            operLog.setOperTime(new Date());
            operLogService.insertTSysOperLog(operLog);
        } catch (Exception e) {
            log.warn("届期催办与巡查：轮次[{}]审计留痕没落成，不影响本轮", roundNo, e);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }
}
