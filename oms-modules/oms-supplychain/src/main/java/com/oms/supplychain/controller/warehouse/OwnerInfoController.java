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

@RestController @RequestMapping("/owner")
public class OwnerInfoController {
    @Resource private WarehouseWorkspaceService workspace;
    @RequiresPermissions("warehouse:owner:list") @GetMapping("/list")
    public TableDataInfo list(@RequestParam Map<String,Object> query,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="10") int pageSize){return workspace.page(WarehouseCompany.current(),"owner",query,pageNum,pageSize);}
    @RequiresPermissions("warehouse:owner:query") @GetMapping("/{id}")
    public AjaxResult detail(@PathVariable long id){return AjaxResult.success(workspace.detail(WarehouseCompany.current(),"owner",id));}
    @RequiresPermissions("warehouse:owner:add") @Log(title="货主",businessType=BusinessType.INSERT) @PostMapping
    public AjaxResult add(@RequestBody Map<String,Object> row){row.remove("id");return AjaxResult.success(workspace.save(WarehouseCompany.current(),"owner",row));}
    @RequiresPermissions("warehouse:owner:edit") @Log(title="货主",businessType=BusinessType.UPDATE) @PutMapping
    public AjaxResult edit(@RequestBody Map<String,Object> row){if(row.get("id")==null || Long.parseLong(row.get("id").toString())<=0)throw new IllegalArgumentException("缺少资料编号");return AjaxResult.success(workspace.save(WarehouseCompany.current(),"owner",row));}
    @RequiresPermissions("warehouse:owner:remove") @Log(title="货主",businessType=BusinessType.DELETE) @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids){workspace.delete(WarehouseCompany.current(),"owner",Arrays.asList(ids));return AjaxResult.success();}
    @RequiresPermissions("warehouse:owner:export") @Log(title="货主",businessType=BusinessType.EXPORT) @PostMapping("/export")
    public void export(HttpServletResponse response,@RequestParam Map<String,Object> query){List<WarehouseExportRow> rows=workspace.list(WarehouseCompany.current(),"owner",query).stream().map(r->workspace.convert(r,WarehouseExportRow.class)).collect(Collectors.toList());ExcelUtil<WarehouseExportRow> excel=new ExcelUtil<>(WarehouseExportRow.class);excel.hideColumn("inboundMode","outboundMode","realStoreCode","wmsName","status","wmsType","director","mobilePhone","province","city","district","address","ownerCount","wmsSimulationCode","wmsSimulationName","effectiveEnabled");excel.exportExcel(response,rows,"货主");}
    @RequiresPermissions(value={"warehouse:owner:listOwner","warehouse:simulationStoreInfo:add","warehouse:simulationStoreInfo:edit"},logical=Logical.OR)
    @GetMapping("/listOwner") public AjaxResult choices(){return AjaxResult.success(workspace.list(WarehouseCompany.current(),"owner",WarehouseWorkspaceService.map("isEnable",2)));}
}
