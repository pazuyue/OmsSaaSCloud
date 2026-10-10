package com.oms.supplychain.controller.warehouse;

import com.oms.supplychain.service.warehouse.WarehouseCompany;
import com.oms.supplychain.service.warehouse.impl.WarehouseWorkspaceService;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.security.annotation.*;
import com.ruoyi.common.log.annotation.Log;
import com.ruoyi.common.log.enums.BusinessType;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.util.*;

@RestController @RequestMapping("/ownerWarehouse")
public class OwnerWarehouseController {
    @Resource private WarehouseWorkspaceService workspace;
    @RequiresPermissions(value={"warehouse:owner:list","warehouse:owner:query","warehouse:owner:edit","warehouse:WmsRealStoreInfo:query","warehouse:simulationStoreInfo:list","warehouse:simulationStoreInfo:add","warehouse:simulationStoreInfo:edit"},logical=Logical.OR)
    @GetMapping("/list") public AjaxResult list(@RequestParam Map<String,Object> query){return AjaxResult.success(workspace.list(WarehouseCompany.current(),"ownerWarehouse",query));}
    @RequiresPermissions(value={"warehouse:owner:list","warehouse:owner:add","warehouse:owner:edit","warehouse:WmsRealStoreInfo:list","warehouse:WmsRealStoreInfo:query","warehouse:simulationStoreInfo:list","warehouse:simulationStoreInfo:add","warehouse:simulationStoreInfo:edit"},logical=Logical.OR)
    @GetMapping("/options") public AjaxResult options(){return AjaxResult.success(workspace.options(WarehouseCompany.current()));}
    @RequiresPermissions("warehouse:owner:edit") @Log(title="货主仓库关联",businessType=BusinessType.INSERT)
    @PostMapping public AjaxResult add(@RequestBody Map<String,Object> row){row.remove("id");return AjaxResult.success(workspace.save(WarehouseCompany.current(),"ownerWarehouse",row));}
    @RequiresPermissions("warehouse:owner:edit") @Log(title="货主仓库关联",businessType=BusinessType.UPDATE)
    @PutMapping public AjaxResult edit(@RequestBody Map<String,Object> row){if(row.get("id")==null || Long.parseLong(row.get("id").toString())<=0)throw new IllegalArgumentException("缺少关联编号");return AjaxResult.success(workspace.save(WarehouseCompany.current(),"ownerWarehouse",row));}
    @RequiresPermissions("warehouse:owner:edit") @Log(title="货主仓库关联",businessType=BusinessType.DELETE)
    @DeleteMapping("/{id}") public AjaxResult remove(@PathVariable Long id){workspace.delete(WarehouseCompany.current(),"ownerWarehouse",Collections.singletonList(id));return AjaxResult.success();}
}
