package com.oms.inventory.controller.wms;

import com.oms.inventory.service.InventoryCompany;
import com.oms.inventory.service.impl.ChannelInventoryQueryService;
import com.oms.inventory.service.impl.rule.AllocationCatalog;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.core.web.page.TableDataInfo;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.util.*;

@RestController
@RequestMapping("/channelInventory")
public class ChannelInventoryController {
    @Resource private ChannelInventoryQueryService queries;
    @Resource private AllocationCatalog catalog;
    @RequiresPermissions("channelInventory:inventory:list")
    @GetMapping
    public TableDataInfo list(@RequestParam(required=false) Long channelId,@RequestParam(required=false) String skuSn,@RequestParam(defaultValue="false") boolean onlyStock,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="20") int pageSize){return queries.list(InventoryCompany.current(),channelId,skuSn,onlyStock,pageNum,pageSize);}
    @RequiresPermissions("channelInventory:inventory:list")
    @GetMapping("/channels")
    public AjaxResult channels(@RequestParam(defaultValue="") String keyword,@RequestParam(required=false) List<Integer> ids){
        InventoryCompany.current();if(keyword.length()>100 || (ids!=null && (ids.size()>100 || ids.stream().anyMatch(id->id==null || id<=0))))throw new IllegalArgumentException("渠道查询条件无效");
        return AjaxResult.success(catalog.lookupChannels(ids==null?Collections.emptyList():ids,keyword));
    }
    @RequiresPermissions("channelInventory:inventory:list")
    @GetMapping("/{id}") public AjaxResult detail(@PathVariable long id){return AjaxResult.success(queries.detail(InventoryCompany.current(),id));}
    @RequiresPermissions("channelInventory:inventory:list")
    @GetMapping("/{id}/sources") public TableDataInfo sources(@PathVariable long id,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="20") int pageSize){return queries.sources(InventoryCompany.current(),id,pageNum,pageSize);}
    @RequiresPermissions("channelInventory:inventory:list")
    @GetMapping("/{id}/orders") public TableDataInfo orders(@PathVariable long id,@RequestParam(defaultValue="false") boolean onlyOccupied,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="20") int pageSize){return queries.orders(InventoryCompany.current(),id,onlyOccupied,pageNum,pageSize);}
    @RequiresPermissions("channelInventory:inventory:list")
    @GetMapping("/{id}/events") public TableDataInfo events(@PathVariable long id,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="20") int pageSize){return queries.events(InventoryCompany.current(),id,pageNum,pageSize);}
    @RequiresPermissions("channelInventory:inventory:list")
    @GetMapping("/{id}/executions") public TableDataInfo executions(@PathVariable long id,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="20") int pageSize){return queries.executions(InventoryCompany.current(),id,pageNum,pageSize);}
    @ExceptionHandler(IllegalArgumentException.class) public AjaxResult invalid(IllegalArgumentException error){return AjaxResult.error(error.getMessage());}
}
