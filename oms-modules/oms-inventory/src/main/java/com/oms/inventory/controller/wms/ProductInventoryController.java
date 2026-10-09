package com.oms.inventory.controller.wms;

import com.oms.inventory.service.InventoryCompany;
import com.oms.inventory.service.impl.ProductInventoryQueryService;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.core.web.page.TableDataInfo;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;

@RestController
@RequestMapping("/productInventory")
public class ProductInventoryController {
    @Resource private ProductInventoryQueryService queries;
    @RequiresPermissions("wmsInventory:inventory:list")
    @GetMapping
    public TableDataInfo list(@RequestParam(required=false) String skuSn,@RequestParam(defaultValue="false") boolean onlyStock,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="20") int pageSize) {
        return queries.list(InventoryCompany.current(),skuSn,onlyStock,pageNum,pageSize);
    }
    @RequiresPermissions("wmsInventory:inventory:list")
    @GetMapping("/{id}")
    public AjaxResult detail(@PathVariable long id){return AjaxResult.success(queries.detail(InventoryCompany.current(),id));}
    @RequiresPermissions("wmsInventory:inventory:list")
    @GetMapping("/{id}/warehouses")
    public TableDataInfo warehouses(@PathVariable long id,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="20") int pageSize){return queries.warehouses(InventoryCompany.current(),id,pageNum,pageSize);}
    @RequiresPermissions("wmsInventory:inventory:list")
    @GetMapping("/{id}/reservations")
    public TableDataInfo reservations(@PathVariable long id,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="20") int pageSize){return queries.reservations(InventoryCompany.current(),id,pageNum,pageSize);}
    @RequiresPermissions("wmsInventory:inventory:list")
    @GetMapping("/{id}/history")
    public TableDataInfo history(@PathVariable long id,@RequestParam(defaultValue="") String operation,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="20") int pageSize){return queries.history(InventoryCompany.current(),id,operation,pageNum,pageSize);}
    @ExceptionHandler(IllegalArgumentException.class)
    public AjaxResult invalid(IllegalArgumentException error){return AjaxResult.error(error.getMessage());}
}
