package com.fc.v2.service.impl;

import org.springframework.stereotype.Service;

import com.fc.v2.mapper.auto.TOadsPermitBillMapper;
import com.fc.v2.model.auto.TOadsPermitBill;
import com.fc.v2.service.ITOadsPermitBillService;

/**
 * 设置许可核准单 Service业务层处理（approval-chain 形状：多阶段签批）
 *
 * 关次 0..2（窗口受理/区局核准/市局备案），本关算齐办法：
 * sign_mode 0一名即过、1两关须同判；到票按 sign_count 数，need_count 缺着时
 * 按 sign_mode 兜。三关都点齐才置"已点齐"；否决当场置"已压回"；
 * 已点齐的终态任何口子都不许再动。
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TOadsPermitBillServiceImpl implements ITOadsPermitBillService {

    /** 末关：市局备案。这一关点齐即办结 */
    private static final int MAX_NODE = 2;
    private static final int MODE_OR = 0;
    private static final int MODE_AND = 1;
    private static final int STATUS_RUNNING = 0;
    private static final int STATUS_PASS = 1;
    private static final int STATUS_VETO = 2;

    @javax.annotation.Resource
    private TOadsPermitBillMapper oadsPermitBillMapper;

    @Override
    public TOadsPermitBill selectTOadsPermitBillById(Long id) {
        return this.oadsPermitBillMapper.selectById(id);
    }

    @Override
    public TOadsPermitBill approve(Long id, String approver, String comment) {
        TOadsPermitBill r = this.oadsPermitBillMapper.selectById(id);
        if (r == null || approver == null || approver.trim().isEmpty()) {
            return null;
        }
        // 已点齐：任何口子都不许再签，兼容老单子也照这个口径
        if (r.getStatus() != null && r.getStatus() == STATUS_PASS) {
            return null;
        }
        int need = needVotes(r);
        int signed = r.getSignCount() == null ? 0 : r.getSignCount().intValue();
        if (signed >= need) {
            // 本关票数已齐，重复签批不许再造一票
            return null;
        }
        signed++;
        r.setSignCount(signed);
        if (signed < need) {
            // 本关票还没点齐：原样留在本关接着签
            r.setStatus(STATUS_RUNNING);
            this.oadsPermitBillMapper.updateById(r);
            return r;
        }
        // 本关点齐：票清掉进下一关；末关点齐即办结
        int node = r.getNodeNo() == null ? 0 : r.getNodeNo().intValue();
        r.setSignCount(0);
        if (node >= MAX_NODE) {
            r.setStatus(STATUS_PASS);
        } else {
            r.setNodeNo(node + 1);
            r.setStatus(STATUS_RUNNING);
        }
        this.oadsPermitBillMapper.updateById(r);
        return r;
    }

    @Override
    public TOadsPermitBill reject(Long id, String approver, String comment) {
        TOadsPermitBill r = this.oadsPermitBillMapper.selectById(id);
        if (r == null || approver == null || approver.trim().isEmpty()) {
            return null;
        }
        // 已点齐的不许再压；否决必须当场把情形挪到已压回，不能压完还看着在签
        if (r.getStatus() != null && r.getStatus() == STATUS_PASS) {
            return null;
        }
        r.setStatus(STATUS_VETO);
        this.oadsPermitBillMapper.updateById(r);
        return r;
    }

    @Override
    public TOadsPermitBill rollback(Long id, String comment) {
        TOadsPermitBill r = this.oadsPermitBillMapper.selectById(id);
        if (r == null) {
            return null;
        }
        // 已点齐办结的不许退回；头一关无处可退
        if ((r.getStatus() != null && r.getStatus() == STATUS_PASS)
                || r.getNodeNo() == null || r.getNodeNo() <= 0) {
            return null;
        }
        // 退回上一环节重签：关次退一格、票清空、情形回到在签（被压回的也照此复活）
        r.setNodeNo(r.getNodeNo() - 1);
        r.setSignCount(0);
        r.setStatus(STATUS_RUNNING);
        this.oadsPermitBillMapper.updateById(r);
        return r;
    }

    /** 本关应到几票：登了 need_count 按登记的；没登按算齐办法兜（一名即过 1，两关同判 2） */
    private int needVotes(TOadsPermitBill r) {
        if (r.getNeedCount() != null && r.getNeedCount().intValue() > 0) {
            return r.getNeedCount().intValue();
        }
        return r.getSignMode() != null && r.getSignMode() == MODE_AND ? 2 : 1;
    }
}
