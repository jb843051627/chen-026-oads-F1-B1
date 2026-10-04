package com.fc.v2.common.quartz.task;

import java.util.Date;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fc.v2.model.custom.OadsNoticeRunResult;
import com.fc.v2.service.ITOadsNoticeItemService;

/**
 * 届期催办与巡查：定时调度口子。
 * 调度中心把调用目标配成 oadsNoticeTask.runNightly() 即可。
 *
 * 不设执行窗口：本口子与执行键口子（OadsNoticeItemController）是同一个动作的
 * 两个入口，都只委托 {@link ITOadsNoticeItemService#runOnce(Date)} 这一处，
 * 什么时刻触发都按同一口径处理，不许两边各写一套。
 *
 * @author fuce
 * @date 2026-10-04
 */
@Component("oadsNoticeTask")
public class OadsNoticeTask {

    @Autowired
    private ITOadsNoticeItemService oadsNoticeItemService;

    /**
     * 翻一遍《到期催办与现场巡查清单》，跑一轮。
     *
     * @return 本轮的交代（三个数只讲本次这一轮）
     */
    public OadsNoticeRunResult runNightly() {
        return oadsNoticeItemService.runOnce(new Date());
    }
}
