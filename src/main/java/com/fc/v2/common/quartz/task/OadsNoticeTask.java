package com.fc.v2.common.quartz.task;

import java.util.Date;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fc.v2.model.custom.OadsNoticeRunResult;
import com.fc.v2.service.ITOadsNoticeItemService;

/**
 * 届期催办与巡查：定时调度口子。
 * 调度中心把调用目标配成 oadsNoticeTask.runNightly() 即可；
 * 不设执行窗口，什么时辰触发都照办。
 *
 * 本口子与页面上的执行键汇到同一处
 * （{@link ITOadsNoticeItemService#runOnce(Date)}），
 * 同一个动作只许有这一段算法；返回的交代经 Quartz 执行日志原样留痕。
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
     * @return 本轮的交代（三个数只讲本次这一轮；返回值由调度框架落进执行日志）
     */
    public OadsNoticeRunResult runNightly() {
        return oadsNoticeItemService.runOnce(new Date());
    }
}
