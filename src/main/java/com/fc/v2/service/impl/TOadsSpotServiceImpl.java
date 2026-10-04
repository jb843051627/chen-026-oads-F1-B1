package com.fc.v2.service.impl;

import java.util.Arrays;
import java.util.Date;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fc.v2.common.support.ConvertUtil;
import com.fc.v2.mapper.auto.TOadsSpotMapper;
import com.fc.v2.mapper.auto.TOadsAreaBookMapper;
import com.fc.v2.model.auto.TOadsSpot;
import com.fc.v2.model.auto.TOadsAreaBook;
import com.fc.v2.service.ITOadsSpotService;
import com.fc.v2.util.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 点位底册Service业务层处理
 *
 * 在册与否只认 del_flag，册面情形（新入册/已核齐/缺项）是另一根线，
 * 列表、夜跑核验、入册三处对"册上有没有这条"同一口径。
 * 还差几处按 应设 - 已核验 轧出，新增与修改两个口子同一算法。
 *
 * @author fuce
 * @date 2026-09-12
 */
@Service
public class TOadsSpotServiceImpl extends ServiceImpl<TOadsSpotMapper, TOadsSpot> implements ITOadsSpotService {

    /** 册面情形：新入册 */
    private static final int STATUS_NEW = 0;
    /** 册面情形：已核齐 */
    private static final int STATUS_FULL = 1;
    /** 册面情形：缺项 */
    private static final int STATUS_LACK = 2;

    @Autowired
    private TOadsAreaBookMapper oadsAreaBookMapper;

    @Override
    public TOadsSpot selectTOadsSpotById(Long id) {
        return this.baseMapper.selectOne(new QueryWrapper<TOadsSpot>()
                .eq("id", id)
                .eq("del_flag", 0));
    }

    @Override
    public List<TOadsSpot> selectTOadsSpotList(Wrapper<TOadsSpot> queryWrapper) {
        // 尊重调用方给的条件（页面筛选什么就查什么），分页由 BaseController 统一 StartPage；
        // 这里不再偷塞 status=0 把已核齐、缺项的行藏掉，也不偷偷只给十行
        return this.baseMapper.selectList(queryWrapper);
    }

    @Override
    public int insertTOadsSpot(TOadsSpot record) {
        if (record == null) {
            return 0;
        }

        record.setCreateBy(record.getSpotNo());
        // 挂的属地段册必须在册（已撤出归档的属地段册不许再挂新点位），属地段册代号由底册回填
        TOadsAreaBook refArch = loadInBook(record.getAreaId(), record.getAreaNo());
        if (refArch == null) {
            return 0;
        }
        record.setAreaId(Integer.valueOf(refArch.getId().intValue()));
        record.setAreaNo(refArch.getAreaNo());
        if (StringUtils.isNotEmpty(record.getSpotNo())) {
            Integer dupCnt = this.baseMapper.selectCount(new QueryWrapper<TOadsSpot>()
                    .eq("spot_no", record.getSpotNo()).eq("del_flag", 0));
            if (dupCnt != null && dupCnt > 0) {
                return 0;
            }
        }
        recomputeLackAndStatus(record);

        record.setDelFlag(0);
        return this.baseMapper.insert(record);
    }

    @Override
    public int updateTOadsSpot(TOadsSpot record) {
        if (record == null || record.getId() == null) {
            return 0;
        }

        // 改挂段册与新入册是同一道关：目标段册已撤出归档的，这边同样拦下
        TOadsSpot old = this.baseMapper.selectById(record.getId());
        if (old == null || (old.getDelFlag() != null && old.getDelFlag() != 0)) {
            return 0;
        }
        Integer targetAreaId = record.getAreaId() != null ? record.getAreaId() : old.getAreaId();
        String targetAreaNo = StringUtils.isNotEmpty(record.getAreaNo())
                ? record.getAreaNo() : old.getAreaNo();
        TOadsAreaBook refArch = loadInBook(targetAreaId, targetAreaNo);
        if (refArch == null) {
            return 0;
        }
        record.setAreaId(Integer.valueOf(refArch.getId().intValue()));
        record.setAreaNo(refArch.getAreaNo());

        if (StringUtils.isNotEmpty(record.getSpotNo())) {
            Integer dupCnt = this.baseMapper.selectCount(new QueryWrapper<TOadsSpot>()
                    .eq("spot_no", record.getSpotNo()).ne("id", record.getId()).eq("del_flag", 0));
            if (dupCnt != null && dupCnt > 0) {
                return 0;
            }
        }
        // 处数有带新值就重轧，没带沿用老数：新增与修改两个口子同一种轧法
        if (record.getNeedNum() == null) {
            record.setNeedNum(old.getNeedNum());
        }
        if (record.getGotNum() == null) {
            record.setGotNum(old.getGotNum());
        }
        recomputeLackAndStatus(record);

        record.setUpdateTime(new Date());
        return this.baseMapper.update(record, new UpdateWrapper<TOadsSpot>()
                .eq("id", record.getId())
                .eq("del_flag", 0));
    }

    @Override
    public int deleteTOadsSpotByIds(String ids) {
        Long[] idArr = ConvertUtil.toLongArray(ids);
        // 逻辑删除：点位从册上撤名要留痕，原始记录不许物理抹掉
        return Arrays.stream(idArr).mapToInt(id -> this.baseMapper.update(null,
                new UpdateWrapper<TOadsSpot>()
                        .eq("id", id).eq("del_flag", 0)
                        .set("del_flag", 1).set("update_time", new Date()))).sum();
    }

    @Override
    public int deleteTOadsSpotById(Long id) {
        // 逻辑删除，与批量删除同一口径
        return this.baseMapper.update(null, new UpdateWrapper<TOadsSpot>()
                .eq("id", id).eq("del_flag", 0)
                .set("del_flag", 1).set("update_time", new Date()));
    }

    /**
     * 找在册的属地段册：优先按底册 id 对，id 没给就按属地段册代号对；
     * 查无此册、已删、已撤出归档一律视为挂不上。两个口子共用这一道关。
     */
    private TOadsAreaBook loadInBook(Integer areaId, String areaNo) {
        QueryWrapper<TOadsAreaBook> qw = new QueryWrapper<TOadsAreaBook>().eq("del_flag", 0);
        if (areaId != null) {
            qw.eq("id", areaId);
        } else if (StringUtils.isNotEmpty(areaNo)) {
            qw.eq("area_no", areaNo);
        } else {
            return null;
        }
        TOadsAreaBook book = this.oadsAreaBookMapper.selectOne(qw);
        if (book == null) {
            return null;
        }
        if (book.getStatus() != null && book.getStatus() != 0) {
            return null;
        }
        return book;
    }

    /**
     * 轧差与册面情形：还差几处 = 应设 - 已核验（轧出来，不手填，不为负）；
     * 已落地且轧平为已核齐，落地了还欠着为缺项，一处没落地为新入册。
     * 新增、修改两个口子同一段口径。
     */
    private void recomputeLackAndStatus(TOadsSpot record) {
        int need = record.getNeedNum() == null ? 0 : record.getNeedNum().intValue();
        int got = record.getGotNum() == null ? 0 : record.getGotNum().intValue();
        int lack = Math.max(0, need - got);
        record.setLackNum(lack);
        if (need > 0 && lack == 0) {
            record.setStatus(STATUS_FULL);
        } else if (got > 0) {
            record.setStatus(STATUS_LACK);
        } else {
            record.setStatus(STATUS_NEW);
        }
    }
}
