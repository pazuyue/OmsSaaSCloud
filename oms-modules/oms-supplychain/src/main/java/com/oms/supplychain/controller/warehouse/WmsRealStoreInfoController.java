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

@RestController @RequestMapping("/realStore")
public class WmsRealStoreInfoController {
    @Resource private WarehouseWorkspaceService workspace;
    @RequiresPermissions("warehouse:WmsRealStoreInfo:list") @GetMapping("/list")
    public TableDataInfo list(@RequestParam Map<String,Object> query,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="10") int pageSize){return workspace.page(WarehouseCompany.current(),"realStore",query,pageNum,pageSize);}
    @RequiresPermissions("warehouse:WmsRealStoreInfo:query") @GetMapping("/{id}")
    public AjaxResult detail(@PathVariable long id){return AjaxResult.success(workspace.detail(WarehouseCompany.current(),"realStore",id));}
    @RequiresPermissions("warehouse:WmsRealStoreInfo:add") @Log(title="实体仓库",businessType=BusinessType.INSERT) @PostMapping
    public AjaxResult add(@RequestBody Map<String,Object> row){row.remove("id");return AjaxResult.success(workspace.save(WarehouseCompany.current(),"realStore",row));}
    @RequiresPermissions("warehouse:WmsRealStoreInfo:edit") @Log(title="实体仓库",businessType=BusinessType.UPDATE) @PutMapping
    public AjaxResult edit(@RequestBody Map<String,Object> row){if(row.get("id")==null || Long.parseLong(row.get("id").toString())<=0)throw new IllegalArgumentException("缺少资料编号");return AjaxResult.success(workspace.save(WarehouseCompany.current(),"realStore",row));}
    @RequiresPermissions("warehouse:WmsRealStoreInfo:remove") @Log(title="实体仓库",businessType=BusinessType.DELETE) @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids){workspace.delete(WarehouseCompany.current(),"realStore",Arrays.asList(ids));return AjaxResult.success();}
    @RequiresPermissions("warehouse:WmsRealStoreInfo:export") @Log(title="实体仓库",businessType=BusinessType.EXPORT) @PostMapping("/export")
    public void export(HttpServletResponse response,@RequestParam Map<String,Object> query){List<WarehouseExportRow> rows=workspace.list(WarehouseCompany.current(),"realStore",query).stream().map(r->workspace.convert(r,WarehouseExportRow.class)).collect(Collectors.toList());ExcelUtil<WarehouseExportRow> excel=new ExcelUtil<>(WarehouseExportRow.class);excel.hideColumn("inboundMode","outboundMode","ownerCode","ownerName","isEnable","warehouseCount","wmsSimulationCode","wmsSimulationName","effectiveEnabled");excel.exportExcel(response,rows,"实体仓库");}
    @RequiresPermissions(value={"warehouse:WmsRealStoreInfo:list","warehouse:owner:add","warehouse:owner:edit"},logical=Logical.OR)
    @GetMapping("/listAll") public AjaxResult choices(){return AjaxResult.success(workspace.list(WarehouseCompany.current(),"realStore",WarehouseWorkspaceService.map("status",2)));}
}
