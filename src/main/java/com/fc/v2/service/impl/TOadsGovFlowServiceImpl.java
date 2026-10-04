package com.fc.v2.service.impl;

import java.util.Date;
import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TOadsGovFlowMapper;
import com.fc.v2.model.auto.TOadsGovFlow;
import com.fc.v2.service.ITOadsGovFlowService;

/**
 * 点位监管办件流转单 Service业务层处理（state-machine 形状：单据流转）
 *
 * 关次 0..3（申报/核准/设置/验收），单子情形 0未起 1在办 2已封住。
 * 推进、回退、改内容、删除四个口子共用同一套终态口径：已封住的单子一律不许再动。
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TOadsGovFlowServiceImpl implements ITOadsGovFlowService {

    /** 末关：验收。走到这一关单子即封住 */
    private static final int MAX_STAGE = 3;
    private static final int STATUS_NOT_STARTED = 0;
    private static final int STATUS_ACTIVE = 1;
    private static final int STATUS_TERMINAL = 2;

    @javax.annotation.Resource
    private TOadsGovFlowMapper oadsGovFlowMapper;

    @Override
    public TOadsGovFlow selectTOadsGovFlowById(Long id) {
        return this.oadsGovFlowMapper.selectById(id);
    }

    @Override
    public List<TOadsGovFlow> selectTOadsGovFlowList(QueryWrapper<TOadsGovFlow> queryWrapper) {
        return this.oadsGovFlowMapper.selectList(queryWrapper);
    }

    @Override
    public TOadsGovFlow advance(Long id, String remark) {
        TOadsGovFlow r = this.oadsGovFlowMapper.selectById(id);
        if (r == null) {
            return null;
        }
        // 已封住：推进、回退、改内容、删除四个口子同一口径，一律拦下
        if (r.getStatus() != null && r.getStatus() == STATUS_TERMINAL) {
            return null;
        }
        int st = r.getStage() == null ? 0 : r.getStage();
        if (st >= MAX_STAGE) {
            return null;
        }
        int next = st + 1;
        r.setStage(next);
        // 走到验收关即封住，停在半路则在办；处理完立刻可判得出情形
        r.setStatus(next >= MAX_STAGE ? STATUS_TERMINAL : STATUS_ACTIVE);
        r.setLastAction(remark);
        r.setUpdateTime(new Date());
        this.oadsGovFlowMapper.updateById(r);
        return r;
    }

    @Override
    public TOadsGovFlow rollback(Long id, String remark) {
        TOadsGovFlow r = this.oadsGovFlowMapper.selectById(id);
        if (r == null) {
            return null;
        }
        // 已封住的不许回退；还在申报头一关的也无处可退
        if ((r.getStatus() != null && r.getStatus() == STATUS_TERMINAL)
                || (r.getStage() == null || r.getStage() <= 0)) {
            return null;
        }
        r.setStage(r.getStage() - 1);
        r.setStatus(STATUS_ACTIVE);
        r.setLastAction(remark);
        r.setUpdateTime(new Date());
        this.oadsGovFlowMapper.updateById(r);
        return r;
    }

    @Override
    public boolean updateContent(Long id, String remark) {
        TOadsGovFlow r = this.oadsGovFlowMapper.selectById(id);
        if (r == null) {
            return false;
        }
        // 已办结（封住）不得再改
        if (r.getStatus() != null && r.getStatus() == STATUS_TERMINAL) {
            return false;
        }
        r.setContent(remark);
        r.setUpdateTime(new Date());
        return this.oadsGovFlowMapper.updateById(r) > 0;
    }

    @Override
    public boolean remove(Long id) {
        TOadsGovFlow r = this.oadsGovFlowMapper.selectById(id);
        if (r == null) {
            return false;
        }
        // 已办结（封住）不得删除
        if (r.getStatus() != null && r.getStatus() == STATUS_TERMINAL) {
            return false;
        }
        // 只做逻辑删除，原始单据留着日后回查
        r.setDelFlag(1);
        r.setUpdateTime(new Date());
        return this.oadsGovFlowMapper.updateById(r) > 0;
    }

}
