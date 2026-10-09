package com.oms.inventory.controller.rule;

import com.oms.inventory.service.InventoryCompany;
import com.oms.inventory.service.impl.rule.*;
import com.oms.inventory.model.dto.AllocationDraft;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.core.web.page.TableDataInfo;
import com.ruoyi.common.security.annotation.*;
import com.ruoyi.common.security.utils.SecurityUtils;
import com.ruoyi.common.log.annotation.Log;
import com.ruoyi.common.log.enums.BusinessType;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.util.*;

@RestController
@RequestMapping("/allocation")
public class DailyAllocationController {
    @Resource private DailyAllocationService daily;
    @Resource private DailyAllocationScanner scanner;
    @Resource private AllocationWorkspaceService workspace;
    @Resource private AllocationCatalog catalog;
    @InnerAuth
    @PostMapping("/internal/daily-scan")
    public R<Map<String,Object>> scan(@RequestParam("company_code") String company){return R.ok(scanner.poll(company));}

    @RequiresPermissions("ruleStock:info:edit")
    @PostMapping("/{id}/daily/{action}")
    @Log(title="日常分货调度",businessType=BusinessType.UPDATE)
    @SuppressWarnings("unchecked")
    public AjaxResult command(@PathVariable long id,@PathVariable String action,@RequestParam int revision) {
        String company=InventoryCompany.current();
        if("ENABLE".equals(action)) {
            Map<String,Object> detail=workspace.detail(company,id);AllocationDraft draft=new AllocationDraft();draft.setStores((List<String>)detail.get("stores"));
            for(Map<String,Object> row:(List<Map<String,Object>>)detail.get("channels")){AllocationDraft.Channel c=new AllocationDraft.Channel();c.setChannelId(((Number)row.get("channelId")).intValue());draft.getChannels().add(c);}catalog.validate(draft);
        }
        daily.command(company,id,revision,action,SecurityUtils.getUsername());return AjaxResult.success(workspace.detail(company,id));
    }
    @RequiresPermissions("ruleStock:info:list")
    @GetMapping("/{id}/daily-runs")
    public TableDataInfo runs(@PathVariable long id,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="20") int pageSize){return daily.runs(InventoryCompany.current(),id,pageNum,pageSize);}
    @RequiresPermissions("ruleStock:info:list")
    @GetMapping("/{id}/daily-runs/{runId}")
    public AjaxResult run(@PathVariable long id,@PathVariable long runId){return AjaxResult.success(daily.run(InventoryCompany.current(),id,runId));}
    @RequiresPermissions("ruleStock:info:list")
    @GetMapping("/{id}/daily-runs/{runId}/items")
    public TableDataInfo items(@PathVariable long id,@PathVariable long runId,@RequestParam(defaultValue="") String status,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="20") int pageSize){return daily.items(InventoryCompany.current(),id,runId,status,pageNum,pageSize);}
}
