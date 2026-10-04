package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TOadsRuleLineMapper;
import com.fc.v2.model.auto.TOadsRuleLine;
import com.fc.v2.service.ITOadsRuleLineService;
import com.fc.v2.util.OadsWorkdayUtil;

/**
 * 牌面安全归层判据线 Service业务层处理（rule-eval 形状）
 *
 * 时刻、在位、删没删只有一种说法：在 at 时刻可用 = 没删 且 情形在位(0)
 * 且 at 落在 [起用, 交班) 内（两头没填视为不限）。
 * 定位单条、列出可用、能不能用、可用条数四个口子都从 {@link #listAvailable(Date)}
 * 这一处取数，审计回查不用再猜是按哪一段口径算的。
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TOadsRuleLineServiceImpl implements ITOadsRuleLineService {

    /** 判据线情形：在位 */
    private static final int LINE_ON = 0;

    /** 优先级降序；并列按 ruleCode 降序（确定性） */
    private static final Comparator<TOadsRuleLine> PRIORITY_THEN_CODE_DESC = new Comparator<TOadsRuleLine>() {
        @Override
        public int compare(TOadsRuleLine a, TOadsRuleLine b) {
            int pa = a.getPriority() == null ? 0 : a.getPriority();
            int pb = b.getPriority() == null ? 0 : b.getPriority();
            if (pa != pb) {
                return pb - pa;
            }
            String ca = a.getRuleCode() == null ? "" : a.getRuleCode();
            String cb = b.getRuleCode() == null ? "" : b.getRuleCode();
            return cb.compareTo(ca);
        }
    };

    @javax.annotation.Resource
    private TOadsRuleLineMapper oadsRuleLineMapper;

    @Override
    public TOadsRuleLine selectTOadsRuleLineById(Long id) {
        return this.oadsRuleLineMapper.selectById(id);
    }

    @Override
    public List<TOadsRuleLine> listAvailable(Date at) {
        if (at == null) {
            return new java.util.ArrayList<TOadsRuleLine>();
        }
        // 没删、在位的线先捞出来，生效区间在内存里按统一墙钟判（与夜跑同一把尺）
        List<TOadsRuleLine> all = this.oadsRuleLineMapper.selectList(
                new QueryWrapper<TOadsRuleLine>().eq("del_flag", 0));
        List<TOadsRuleLine> avail = new java.util.ArrayList<TOadsRuleLine>();
        for (TOadsRuleLine r : all) {
            if (r.getStatus() != null && r.getStatus() != LINE_ON) {
                continue;
            }
            if (effectiveAt(r, at)) {
                avail.add(r);
            }
        }
        Collections.sort(avail, PRIORITY_THEN_CODE_DESC);
        return avail;
    }

    @Override
    public int evaluate(String ruleCode, BigDecimal input, Date at) {
        if (ruleCode == null || ruleCode.trim().isEmpty() || input == null || at == null) {
            return 0;
        }
        // 与列表/可用性同一来源：从 at 时刻可用线里按代号找
        TOadsRuleLine rule = null;
        for (TOadsRuleLine r : listAvailable(at)) {
            if (ruleCode.equals(r.getRuleCode())) {
                rule = r;
                break;
            }
        }
        if (rule == null) {
            return 0;
        }
        return levelOf(rule, input);
    }

    @Override
    public int evaluateTop(BigDecimal input, Date at) {
        if (input == null || at == null) {
            return 0;
        }
        if (input.compareTo(BigDecimal.ZERO) < 0
                || input.compareTo(new BigDecimal("100")) > 0) {
            return 0;
        }
        List<TOadsRuleLine> avail = listAvailable(at);
        if (avail.isEmpty()) {
            return 0;
        }
        return levelOf(avail.get(0), input);
    }

    @Override
    public boolean usable(Long id, Date at) {
        if (id == null || at == null) {
            return false;
        }
        // 旁路复用与定位/列表同一口径，不许另查一张"差不多"的集
        for (TOadsRuleLine r : listAvailable(at)) {
            if (id.equals(r.getId())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int countAvailable(Date at) {
        if (at == null) {
            return 0;
        }
        return listAvailable(at).size();
    }

    /**
     * 是否落在生效区间：[eff_start, eff_end)，起用没填视为自古有之，
     * 交班那一一日不含（与 schema 注释一致）。
     */
    private boolean effectiveAt(TOadsRuleLine r, Date at) {
        if (r.getEffStart() != null
                && OadsWorkdayUtil.toLocal(at).isBefore(OadsWorkdayUtil.toLocal(r.getEffStart()))) {
            return false;
        }
        if (r.getEffEnd() != null
                && !OadsWorkdayUtil.toLocal(at).isBefore(OadsWorkdayUtil.toLocal(r.getEffEnd()))) {
            return false;
        }
        return true;
    }

    /**
     * 分档：等于上限取高一档——正压着起算/够线/封顶限值的，归到再高一档；
     * 某档限值没登，那一档不设界。
     */
    private int levelOf(TOadsRuleLine rule, BigDecimal input) {
        if (rule.getTh1Max() != null && input.compareTo(rule.getTh1Max()) < 0) {
            return 1;
        }
        if (rule.getTh2Max() != null && input.compareTo(rule.getTh2Max()) < 0) {
            return 2;
        }
        if (rule.getTh3Max() != null && input.compareTo(rule.getTh3Max()) < 0) {
            return 3;
        }
        return 4;
    }
}
