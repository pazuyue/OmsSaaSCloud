package com.oms.inventory.controller.rule;

import com.oms.inventory.model.dto.AllocationRuleDto;
import com.oms.inventory.service.rule.IRuleStockChannelInfoService;
import com.ruoyi.common.core.web.controller.BaseController;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.log.annotation.Log;
import com.ruoyi.common.log.enums.BusinessType;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

@Slf4j
@RestController
@RequestMapping("/ruleStockHandle")
public class RuleStockInfoHandleController extends BaseController {

    @Resource
    private IRuleStockChannelInfoService ruleStockChannelInfoService;
    /**
     * 获取分货单基础信息详细信息
     */
    @RequiresPermissions("ruleStock:info:edit")
    @Log(title = "分货单设置", businessType = BusinessType.UPDATE)
    @PostMapping(value = "/setRule")
    public AjaxResult setRule(@RequestBody AllocationRuleDto dto,@RequestParam(value = "company_code",required = false) String companyCode)
    {
        log.info("分货单设置：{}",dto);
        dto.setCompanyCode(companyCode);
        Boolean aBoolean = ruleStockChannelInfoService.setRule(dto);
        if (!aBoolean){
            return error("保存失败");
        }
        return success();
    }
}
