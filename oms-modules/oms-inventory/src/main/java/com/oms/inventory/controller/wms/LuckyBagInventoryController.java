package com.oms.inventory.controller.wms;

import com.oms.inventory.service.InventoryCompany;
import com.oms.inventory.service.impl.LuckyBagInventory;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.security.annotation.*;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;

/** Details reached from existing product/channel inventory pages; no separate allocation workflow. */
@RestController @RequestMapping("/lucky-bags") public class LuckyBagInventoryController {
    @Resource LuckyBagInventory service;
    @Resource com.oms.inventory.service.impl.rule.AllocationCatalog catalog;
    @RequiresPermissions("ruleStock:info:edit") @PostMapping("/{sku}/release") public AjaxResult release(@PathVariable String sku,@RequestBody java.util.Map<String,Object> body){if(!Boolean.TRUE.equals(body.get("confirmed")))throw new IllegalArgumentException("请确认停止投放并回收保障");return AjaxResult.success(service.reclaim(InventoryCompany.current(),sku,com.oms.common.lucky.LuckyBagRules.number(body,"rule_id"),com.ruoyi.common.security.utils.SecurityUtils.getUsername()));}
    @RequiresPermissions(value={"wmsInventory:inventory:list","ruleStock:info:list"},logical=Logical.OR) @GetMapping("/stores") public AjaxResult stores(){InventoryCompany.current();return AjaxResult.success(catalog.lookup(false,""));}
    @RequiresPermissions(value={"wmsInventory:inventory:list","ruleStock:info:list"},logical=Logical.OR)
    @PostMapping("/trial") public AjaxResult trial(@RequestBody java.util.Map<String,Object> input){com.oms.inventory.model.dto.AllocationDraft draft=new com.oms.inventory.model.dto.AllocationDraft();java.util.List<String> stores=new java.util.ArrayList<>();if(input.get("stores") instanceof java.util.List)for(Object store:(java.util.List<?>)input.get("stores"))stores.add(String.valueOf(store));draft.setStores(stores);catalog.validate(draft);return AjaxResult.success(service.trial(InventoryCompany.current(),input));}
    @RequiresPermissions(value={"wmsInventory:inventory:list","ruleStock:info:list"},logical=Logical.OR)
    @PostMapping("/stock-check") public AjaxResult stockCheck(@RequestBody java.util.Map<String,Object> input){com.oms.inventory.model.dto.AllocationDraft draft=new com.oms.inventory.model.dto.AllocationDraft();java.util.List<String> stores=new java.util.ArrayList<>();if(input.get("stores") instanceof java.util.List)for(Object store:(java.util.List<?>)input.get("stores"))stores.add(String.valueOf(store));draft.setStores(stores);catalog.validate(draft);return AjaxResult.success(service.stockCheck(InventoryCompany.current(),input));}
    @RequiresPermissions(value={"wmsInventory:inventory:list","channelInventory:inventory:list","ruleStock:info:list"},logical=Logical.OR)
    @GetMapping("/{sku}") public AjaxResult detail(@PathVariable String sku,@RequestParam(defaultValue="0") long channelId){if(sku.isEmpty()||sku.length()>50)throw new IllegalArgumentException("福袋SKU无效");return AjaxResult.success(service.report(InventoryCompany.current(),sku,channelId));}
}
