package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TOadsCheckRowMapper;
import com.fc.v2.model.auto.TOadsCheckRow;
import com.fc.v2.service.ITOadsCheckRowService;

/**
 * 集中更换核验行 Service业务层处理（batch-process 形状：整批提交）
 *
 * 一批一次过：合法行收下、非法行退回并保留原报行号；空批返回 0；
 * 超过单批上限整批不入库返回 -1；同一批次重复提交不重复入库。
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TOadsCheckRowServiceImpl implements ITOadsCheckRowService {

    /** 单批上限：超过整批退回，一行都不入库 */
    private static final int MAX_ROWS = 500;
    /** 行落地情形：已收下 */
    private static final int STATUS_OK = 1;
    /** 行落地情形：已退回 */
    private static final int STATUS_FAIL = 2;

    @javax.annotation.Resource
    private TOadsCheckRowMapper oadsCheckRowMapper;

    @Override
    public TOadsCheckRow selectTOadsCheckRowById(Long id) {
        return this.oadsCheckRowMapper.selectById(id);
    }

    @Override
    public int submitBatch(String batchNo, List<TOadsCheckRow> rows) {
        // 空批次：没什么可办的，回 0 收工，不许碰第一行把作业碰崩
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        // 超上限：整批不入库
        if (rows.size() > MAX_ROWS) {
            return -1;
        }
        String no = batchNo != null && !batchNo.trim().isEmpty()
                ? batchNo.trim()
                : rows.get(0).getBatchNo();
        if (no == null || no.trim().isEmpty()) {
            return -1;
        }
        // 同一批次重复提交：原样回已有成功行数，绝不重复入库
        Integer existed = this.oadsCheckRowMapper.selectCount(new QueryWrapper<TOadsCheckRow>()
                .eq("batch_no", no).eq("del_flag", 0));
        if (existed != null && existed > 0) {
            Integer accepted = this.oadsCheckRowMapper.selectCount(new QueryWrapper<TOadsCheckRow>()
                    .eq("batch_no", no).eq("status", STATUS_OK).eq("del_flag", 0));
            return accepted == null ? 0 : accepted.intValue();
        }

        int ok = 0;
        int line = 0;
        for (TOadsCheckRow r : rows) {
            line++;
            r.setBatchNo(no);
            r.setDelFlag(0);
            // 保留原报行号：报送时没编号才按过批次序补，日后照着行号对得上原报
            if (r.getRowNo() == null) {
                r.setRowNo(line);
            }
            if (invalid(r)) {
                r.setStatus(STATUS_FAIL);
            } else {
                r.setStatus(STATUS_OK);
                ok++;
            }
            this.oadsCheckRowMapper.insert(r);
        }
        return ok;
    }

    @Override
    public List<TOadsCheckRow> listErrors(String batchNo) {
        // 按原报行号升序，照着退回明细逐行改
        return this.oadsCheckRowMapper.selectList(new QueryWrapper<TOadsCheckRow>()
                .eq("batch_no", batchNo).eq("status", STATUS_FAIL)
                .eq("del_flag", 0).orderByAsc("row_no"));
    }

    /** 行站不住：没对上点位码、没填处数、处数不是正数，三选一即退回 */
    private boolean invalid(TOadsCheckRow r) {
        return r.getItemCode() == null || r.getItemCode().trim().isEmpty()
                || r.getQty() == null
                || r.getQty().compareTo(BigDecimal.ZERO) <= 0;
    }
}
