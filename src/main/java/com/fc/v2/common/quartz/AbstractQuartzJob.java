package com.fc.v2.common.quartz;

import cn.hutool.core.exceptions.ExceptionUtil;

import com.fc.v2.common.spring.SpringUtils;
import com.fc.v2.model.auto.TSysQuartzJob;
import com.fc.v2.model.auto.TSysQuartzJobLog;
import com.fc.v2.service.ITSysQuartzJobLogService;
import com.fc.v2.util.StringUtils;

import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import java.util.Date;

/**
 * @CLASSNAME AbstractQuartzJob
 * @Description
 * @Auther Jan  橙寂
 * @DATE 2019/9/5 0005 15:05
 */

public abstract class AbstractQuartzJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(AbstractQuartzJob.class);

    /**
     * 线程本地变量
     */
    private static ThreadLocal<Date> threadLocal = new ThreadLocal<>();

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException
    {
    	TSysQuartzJob sysJob = new TSysQuartzJob();
        BeanUtils.copyProperties(context.getMergedJobDataMap().get(ScheduleConstants.TASK_PROPERTIES),sysJob);
        Object result = null;
        try
        {
            before(context, sysJob);
            if (sysJob != null)
            {
                result = doExecute(context, sysJob);
            }
            after(context, sysJob, null, result);
        }
        catch (Exception e)
        {
            log.error("任务执行异常  - ：", e);
            after(context, sysJob, e, result);
        }
    }

    /**
     * 执行前
     *
     * @param context 工作执行上下文对象
     * @param sysJob 系统计划任务
     */
    protected void before(JobExecutionContext context, TSysQuartzJob sysJob)
    {
        threadLocal.set(new Date());
    }

    /**
     * 执行后
     *
     * @param context 工作执行上下文对象
     * @param sysJob 系统计划任务
     * @param e 执行异常（正常为 null）
     * @param result 目标方法的返回值，其原话拼进执行信息，与页面/审计同一出处
     */
    protected void after(JobExecutionContext context, TSysQuartzJob sysJob, Exception e, Object result)
    {
        Date startTime = threadLocal.get();
        threadLocal.remove();

        final TSysQuartzJobLog sysJobLog = new TSysQuartzJobLog();
        sysJobLog.setJobName(sysJob.getJobName());
        sysJobLog.setJobGroup(sysJob.getJobGroup());
        sysJobLog.setInvokeTarget(sysJob.getInvokeTarget());
        sysJobLog.setStartTime(startTime);
        sysJobLog.setEndTime(new Date());
        long runMs = sysJobLog.getEndTime().getTime() - sysJobLog.getStartTime().getTime();
        String resultMsg = result == null ? "" : StringUtils.trimToEmpty(String.valueOf(result));
        sysJobLog.setJobMessage(sysJob.getJobName() + " 总共耗时：" + runMs + "毫秒"
                + (resultMsg.isEmpty() ? "" : "；交代：" + resultMsg));
        if (e != null)
        {
            sysJobLog.setStatus(ScheduleConstants.FAIL_STATUS);
            String errorMsg = StringUtils.substring(ExceptionUtil.getMessage(e), 0, 2000);
            sysJobLog.setExceptionInfo(errorMsg);
        }
        else
        {
            sysJobLog.setStatus(ScheduleConstants.SUCCESS_STATUS);
        }
        //  这里获取service然后插入库中
        SpringUtils.getBean(ITSysQuartzJobLogService.class).insertTSysQuartzJobLog(sysJobLog);
    }

    /**
     * 子类去实现
     * @param jobExecutionContext
     * @param sysJob
     * @return 目标方法的返回值（没有则 null），原样交给执行日志
     */
    protected abstract Object doExecute(JobExecutionContext jobExecutionContext, TSysQuartzJob sysJob) throws Exception;
}
