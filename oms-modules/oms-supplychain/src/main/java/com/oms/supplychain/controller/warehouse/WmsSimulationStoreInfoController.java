package com.oms.supplychain.controller.warehouse;
import com.oms.supplychain.service.warehouse.WarehouseCompany;
import com.oms.supplychain.service.warehouse.impl.WarehouseWorkspaceService;
import com.oms.supplychain.model.vo.warehouse.WarehouseExportRow;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.core.web.page.TableDataInfo;
import com.ruoyi.common.core.utils.poi.ExcelUtil;
import com.ruoyi.common.security.annotation.*;
import com.ruoyi.common.log.annotation.Log;
import com.ruoyi.common.log.enums.BusinessType;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import javax.servlet.http.HttpServletResponse;
import java.util.*;
import java.util.stream.Collectors;

@RestController @RequestMapping("/simulationStore")
public class WmsSimulationStoreInfoController {
    @Resource private WarehouseWorkspaceService workspace;
    @RequiresPermissions("warehouse:simulationStoreInfo:list") @GetMapping("/list")
    public TableDataInfo list(@RequestParam Map<String,Object> query,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="10") int pageSize){return workspace.page(WarehouseCompany.current(),"simulationStore",query,pageNum,pageSize);}
    @RequiresPermissions("warehouse:simulationStoreInfo:query") @GetMapping("/{id}")
    public AjaxResult detail(@PathVariable long id){return AjaxResult.success(workspace.detail(WarehouseCompany.current(),"simulationStore",id));}
    @RequiresPermissions("warehouse:simulationStoreInfo:add") @Log(title="虚拟仓库",businessType=BusinessType.INSERT) @PostMapping
    public AjaxResult add(@RequestBody Map<String,Object> row){row.remove("id");return AjaxResult.success(workspace.save(WarehouseCompany.current(),"simulationStore",row));}
    @RequiresPermissions("warehouse:simulationStoreInfo:edit") @Log(title="虚拟仓库",businessType=BusinessType.UPDATE) @PutMapping
    public AjaxResult edit(@RequestBody Map<String,Object> row){if(row.get("id")==null || Long.parseLong(row.get("id").toString())<=0)throw new IllegalArgumentException("缺少资料编号");return AjaxResult.success(workspace.save(WarehouseCompany.current(),"simulationStore",row));}
    @RequiresPermissions("warehouse:simulationStoreInfo:remove") @Log(title="虚拟仓库",businessType=BusinessType.DELETE) @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids){workspace.delete(WarehouseCompany.current(),"simulationStore",Arrays.asList(ids));return AjaxResult.success();}
    @RequiresPermissions("warehouse:simulationStoreInfo:export") @Log(title="虚拟仓库",businessType=BusinessType.EXPORT) @PostMapping("/export")
    public void export(HttpServletResponse response,@RequestParam Map<String,Object> query){List<WarehouseExportRow> rows=workspace.list(WarehouseCompany.current(),"simulationStore",query).stream().map(r->workspace.convert(r,WarehouseExportRow.class)).collect(Collectors.toList());ExcelUtil<WarehouseExportRow> excel=new ExcelUtil<>(WarehouseExportRow.class);excel.hideColumn("isEnable","warehouseCount","virtualCount","wmsType","director","mobilePhone","province","city","district","address","ownerCount");excel.exportExcel(response,rows,"虚拟仓库");}
    @RequiresPermissions("warehouse:simulationStoreInfo:listSimulationStore")
    @GetMapping("/listSimulationStore") public AjaxResult choices(){return AjaxResult.success(workspace.list(WarehouseCompany.current(),"simulationStore",WarehouseWorkspaceService.map("effectiveEnabled",1)));}
    @RequiresPermissions(value={"ruleStock:info:list","ruleStock:info:edit","ruleStock:info:add"},logical=Logical.OR)
    @GetMapping("/allocationLookup") public AjaxResult allocationLookup(@RequestParam(required=false) List<String> codes,@RequestParam(defaultValue="") String keyword){return lookup(codes,keyword,true);}
    @RequiresPermissions("wmsInventory:inventory:list")
    @GetMapping("/inventoryLookup") public AjaxResult inventoryLookup(@RequestParam(required=false) List<String> codes,@RequestParam(defaultValue="") String keyword){return lookup(codes,keyword,false);}
    private AjaxResult lookup(List<String> codes,String keyword,boolean active){
        if(keyword.length()>128 || (codes!=null && codes.size()>100))throw new IllegalArgumentException("查询条件过多");
        Map<String,Object> q=new HashMap<>();if(active)q.put("effectiveEnabled",1);
        List<Map<String,Object>> result=workspace.list(WarehouseCompany.current(),"simulationStore",q).stream()
            .filter(r->codes!=null && !codes.isEmpty()?codes.contains(r.get("wmsSimulationCode")):String.valueOf(r.get("wmsSimulationCode")).contains(keyword.trim()) || String.valueOf(r.get("wmsSimulationName")).contains(keyword.trim()))
            .limit(100).collect(Collectors.toList());return AjaxResult.success(result);
    }
}
