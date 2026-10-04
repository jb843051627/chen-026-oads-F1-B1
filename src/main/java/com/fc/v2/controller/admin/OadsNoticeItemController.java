package com.fc.v2.controller.admin;

import java.util.Date;

import com.fc.v2.common.base.BaseController;
import com.fc.v2.common.domain.AjaxResult;
import com.fc.v2.common.log.Log;
import com.fc.v2.model.custom.OadsNoticeRunResult;
import com.fc.v2.service.ITOadsNoticeItemService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * 届期催办与巡查条目 Controller（scheduling-job 形状：只有执行键，无增删改查入口）
 *
 * 执行键与定时调度汇到同一处 service.runOnce，同一个动作只有一段算法；
 * 操作日志（@Log）与 service 内的轮次留痕出自同一次执行。
 *
 * @author fuce
 * @date 2026-10-04
 */
@Api(value = "届期催办与巡查条目")
@Controller
@RequestMapping("/OadsNoticeItemController")
public class OadsNoticeItemController extends BaseController {

    @Autowired
    private ITOadsNoticeItemService oadsNoticeItemService;

    /**
     * 执行键：任何人都可以按下，什么时辰按下都照办；
     * 与定时调度口子走同一段处理，应答原话就是本轮的交代。
     */
    @Log(title = "届期催办与巡查", action = "run")
    @ApiOperation(value = "执行一轮", notes = "与定时调度同一处理口径")
    @PostMapping("/run")
    @ResponseBody
    public AjaxResult run() {
        OadsNoticeRunResult result = oadsNoticeItemService.runOnce(new Date());
        return AjaxResult.successData(200, result).put("msg", result.getMessage());
    }
}
