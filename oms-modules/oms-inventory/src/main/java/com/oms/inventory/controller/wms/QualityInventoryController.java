package com.oms.inventory.controller.wms;
import com.oms.inventory.service.impl.QualityInventoryService;
import com.oms.inventory.service.InventoryCompany;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import com.ruoyi.common.security.utils.SecurityUtils;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.util.*;
@RestController @RequestMapping("/qualityInventory") public class QualityInventoryController {
 @Resource QualityInventoryService service;
 @RequiresPermissions("inventory:quality:query") @GetMapping public AjaxResult list(@RequestParam Map<String,String> q){return AjaxResult.success(service.list(InventoryCompany.current(),q));}
 @RequiresPermissions("inventory:quality:query") @GetMapping("/batches") public AjaxResult batches(@RequestParam String sku,@RequestParam(defaultValue="") String store){return AjaxResult.success(service.batches(InventoryCompany.current(),sku,store));}
 @RequiresPermissions("inventory:quality:query") @GetMapping("/{id}") public AjaxResult detail(@PathVariable long id){return AjaxResult.success(service.detail(InventoryCompany.current(),id));}
 @RequiresPermissions("inventory:quality:create") @PostMapping public AjaxResult create(@RequestBody Map<String,Object> q){return AjaxResult.success(service.create(InventoryCompany.current(),q,SecurityUtils.getUsername()));}
 @RequiresPermissions("inventory:quality:review") @PostMapping("/{id}/review") public AjaxResult review(@PathVariable long id,@RequestBody Map<String,Object> q){return AjaxResult.success(service.review(InventoryCompany.current(),id,q,SecurityUtils.getUsername()));}
 @ExceptionHandler(IllegalArgumentException.class) public AjaxResult invalid(IllegalArgumentException e){return AjaxResult.error(e.getMessage());}
}
