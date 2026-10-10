package com.oms.supplychain.controller.warehouse;

import com.oms.supplychain.service.warehouse.impl.PurchaseWorkspaceService;
import com.oms.supplychain.service.warehouse.WarehouseCompany;
import com.oms.supplychain.model.entity.warehouse.NoTicketExcel;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.core.web.page.TableDataInfo;
import com.ruoyi.common.core.utils.poi.ExcelUtil;
import com.ruoyi.common.security.auth.AuthUtil;
import com.ruoyi.common.security.utils.SecurityUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import javax.annotation.Resource;
import javax.servlet.http.HttpServletResponse;
import java.util.*;

@RestController
@RequestMapping("/purchaseWorkspace")
public class PurchaseWorkspaceController {
    @Resource private PurchaseWorkspaceService service;
    private String company(){return WarehouseCompany.current();}
    private String user(){return SecurityUtils.getUsername();}
    private void permission(String kind,String action){String prefix;switch(kind){case "supplier":prefix="warehouse:supplier:";break;case "purchase":case "receipt":prefix="warehouse:poInfo:";break;case "ticket":case "line":prefix="warehouse:tickets:";break;default:throw new IllegalArgumentException("未知业务类型");}AuthUtil.checkPermi(prefix+action);}
    @GetMapping("/options") public AjaxResult options(){AuthUtil.checkPermiOr("warehouse:supplier:list","warehouse:poInfo:list","warehouse:tickets:list");return AjaxResult.success(service.options(company()));}
    @GetMapping("/{kind}/list") public TableDataInfo list(@PathVariable String kind,@RequestParam Map<String,Object> q,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="10") int pageSize){permission(kind,"list");return service.page(company(),kind,q,pageNum,pageSize);}
    @GetMapping("/{kind}/{id}") public AjaxResult detail(@PathVariable String kind,@PathVariable long id){permission(kind,"query");return AjaxResult.success(service.detail(company(),kind,id));}
    @PostMapping("/supplier") public AjaxResult supplier(@RequestBody Map<String,Object> body){permission("supplier",body.get("id")==null?"add":"edit");WarehouseCompany.check((String)body.get("companyCode"));return AjaxResult.success(service.saveSupplier(company(),body,user()));}
    @DeleteMapping("/supplier/{id}") public AjaxResult delete(@PathVariable long id){permission("supplier","remove");service.deleteSupplier(company(),id,user());return AjaxResult.success();}
    @PostMapping("/purchase") public AjaxResult purchase(@RequestBody Map<String,Object> body){permission("purchase",body.get("id")==null?"add":"edit");WarehouseCompany.check((String)body.get("companyCode"));return AjaxResult.success(service.savePurchase(company(),body,user()));}
    @PostMapping("/purchase/{id}/approve") public AjaxResult approve(@PathVariable long id){permission("purchase","approve");service.approve(company(),id,user());return AjaxResult.success();}
    @PostMapping("/purchase/{id}/receipt") public AjaxResult receipt(@PathVariable long id,@RequestBody Map<String,Object> body){permission("purchase","receive");return AjaxResult.success(service.createReceipt(company(),id,body,user()));}
    @PostMapping("/receipt/{id}/approve") public AjaxResult approveReceipt(@PathVariable long id){permission("purchase","approve");return AjaxResult.success(service.approveReceipt(company(),id,user()));}
    @PostMapping("/ticket/{id}/completeVirtual") public AjaxResult completeVirtual(@PathVariable long id){permission("ticket","retry");return AjaxResult.success(service.completeVirtual(company(),id,user()));}
    @PostMapping("/ticket/{id}/receive") public AjaxResult receive(@PathVariable long id,@RequestBody Map<String,Object> body){permission("purchase","receive");service.receive(company(),id,body,user());return AjaxResult.success();}
    @PostMapping("/ticket/{id}/post") public AjaxResult post(@PathVariable long id){permission("ticket","retry");return AjaxResult.success(service.postInventory(company(),id,user()));}
    @PostMapping("/{kind}/{id}/cancel") public AjaxResult cancel(@PathVariable String kind,@PathVariable long id,@RequestBody Map<String,String> body){permission(kind,"remove");service.cancel(company(),kind,id,body.get("reason"),user());return AjaxResult.success();}
    @PostMapping("/purchase/{id}/close") public AjaxResult close(@PathVariable long id,@RequestBody Map<String,String> body){permission("purchase","close");service.closePurchase(company(),id,body.get("reason"),user());return AjaxResult.success();}
    @PostMapping("/importTemplate") public void template(HttpServletResponse response){AuthUtil.checkPermiOr("warehouse:poInfo:add","warehouse:poInfo:edit");new ExcelUtil<>(NoTicketExcel.class).importTemplateExcel(response,"采购商品模板");}
    @PostMapping("/importPreview") public AjaxResult importPreview(@RequestParam MultipartFile file) throws Exception {AuthUtil.checkPermiOr("warehouse:poInfo:add","warehouse:poInfo:edit");if(file.getSize()>5*1024*1024)throw new IllegalArgumentException("文件不能超过 5MB");List<NoTicketExcel> imported=new ExcelUtil<>(NoTicketExcel.class).importExcel(file.getInputStream());List<Map<String,Object>> lines=new ArrayList<>();for(NoTicketExcel l:imported){Map<String,Object> row=new HashMap<>();row.put("skuSn",l.getSkuSn());row.put("purchasePrice",l.getPurchasePrice());row.put("quantity",l.getNumberExpected());lines.add(row);}return AjaxResult.success(service.validateLines(company(),lines));}
    @PostMapping("/{kind}/export") public void export(@PathVariable String kind,@RequestParam Map<String,Object> q,HttpServletResponse response) throws Exception {permission(kind,"export");List<Map<String,Object>> data=service.exportRows(company(),kind,q);String[] columns;
        if(kind.equals("supplier"))columns=new String[]{"supplierSn:供应商编码","supplierName:供应商简称","companyName:公司全称","status:状态","contactUser:联系人","contactTel:联系电话","contactProvince:省","contactCity:市","contactArea:区","contactAddress:地址","openCount:未完成采购数"};
        else if(kind.equals("purchase"))columns=new String[]{"poSn:采购单号","poName:采购名称","supplierName:供应商","stateLabel:单据状态","receiptCount:关联到货单数","editBlockReason:编辑限制","ownerName:货主","realWarehouseName:实仓","warehouseName:虚仓","numberExpected:计划数量","numberActually:已入库数量","remainingQuantity:待入库数量","moneyExpected:计划金额","moneyActually:实际金额","expectedDate:预计到货日期","closeReason:关闭原因"};
        else if(kind.equals("line"))columns=new String[]{"sn:出入库单号","originalSn:采购单号","relationSn:到货单号","goodsName:商品","skuSn:SKU","batchCode:批次","ticketType:出入库类型","warehouseName:虚仓","numberExpected:计划数量","numberActually:实际数量","numberZp:正品实收","numberCp:次品实收","numberDifActually:差异数量","purchasePrice:单价","inventoryIsHandle:入账结果","errorInfo:失败原因"};
        else columns=new String[]{"sn:出入库单号","originalSn:来源单号","relationSn:关联单号","ticketType:出入库类型","ownerName:货主","realWarehouseName:实仓","warehouseName:虚仓","statusTicket:执行状态","inventoryStatus:入账状态","numberExpected:计划数量","numberActually:实际数量","modifyTime:更新时间"};
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");response.setHeader("Content-Disposition","attachment; filename=purchase-export.xlsx");try(XSSFWorkbook book=new XSSFWorkbook()){Sheet sheet=book.createSheet("数据");Row header=sheet.createRow(0);for(int i=0;i<columns.length;i++){header.createCell(i).setCellValue(columns[i].split(":")[1]);sheet.setColumnWidth(i,22*256);}int index=1;for(Map<String,Object> item:data){Row row=sheet.createRow(index++);for(int i=0;i<columns.length;i++){String key=columns[i].split(":")[0];Object value=item.get(key);Cell cell=row.createCell(i);String label=label(key,value,item);if(label!=null)cell.setCellValue(label);else if(value instanceof Number)cell.setCellValue(((Number)value).doubleValue());else cell.setCellValue(value==null?"":value.toString());}}book.write(response.getOutputStream());}}
    private String label(String key,Object value,Map<String,Object> row){if(value==null)return null;String[] labels=null;int n;try{n=Integer.parseInt(value.toString());}catch(Exception e){return null;}if(key.equals("status"))return n==2?"启用":"停用";if(key.equals("ticketType"))return n==1?"入库":"出库";if(key.equals("poState")){if(n==-1)return "已作废";labels=new String[]{"","草稿","待收货","收货中","已完成","已关闭"};}if(key.equals("statusTicket"))labels=new String[]{"待确认","待执行","执行完成","执行失败","待作废","已作废","作废失败"};if(key.equals("inventoryStatus")||key.equals("inventoryIsHandle")){labels=new String[]{"待入账","处理中","入账成功","入账异常"};}return labels!=null&&n>=0&&n<labels.length?labels[n]:null;}
    @ExceptionHandler(IllegalArgumentException.class) public AjaxResult invalid(IllegalArgumentException error){return AjaxResult.error(error.getMessage());}
    @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class) public AjaxResult duplicate(){return AjaxResult.error("编码、名称或单据关联已存在，请刷新后重试");}
}
