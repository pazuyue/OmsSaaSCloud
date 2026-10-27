package com.oms.supplychain.controller.warehouse;
import com.oms.supplychain.service.wms.PurchaseReturns;
import com.oms.supplychain.service.warehouse.WarehouseCompany;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.security.auth.AuthUtil;
import com.ruoyi.common.security.utils.SecurityUtils;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.util.*;
@RestController @RequestMapping("/purchaseReturns") public class PurchaseReturnController {
 @Resource PurchaseReturns service;
 @Resource com.oms.supplychain.service.wms.PurchaseReturnFulfillment fulfillment;
 @Resource com.oms.supplychain.service.wms.PurchaseReturnParcels parcels;
 @Resource com.oms.supplychain.service.wms.WmsCarrierBridge carrierDirectory;
 @Resource com.oms.supplychain.service.wms.WmsStore db;
 @Resource com.oms.supplychain.service.wms.PurchaseReturnReclaims reclaims;
 private void permission(String action){AuthUtil.checkPermi("warehouse:purchaseReturn:"+action);}
 @GetMapping public AjaxResult list(@RequestParam Map<String,String> q){permission("query");return AjaxResult.success(service.list(WarehouseCompany.current(),q));}
 @GetMapping("/sources") public AjaxResult sources(@RequestParam long purchaseId,@RequestParam(defaultValue="") String ticketSn){permission("query");return AjaxResult.success(service.sources(WarehouseCompany.current(),purchaseId,ticketSn));}
 @GetMapping("/purchases") public AjaxResult purchases(@RequestParam(defaultValue="") String keyword){permission("query");String c=WarehouseCompany.current(),term=keyword.trim();com.oms.supplychain.service.wms.WmsStore.require(term.length()<=100,"采购筛选过长");String pattern=term.replace("!","!!").replace("%","!%").replace("_","!_")+"%";return AjaxResult.success(db.jdbc.queryForList("SELECT p.id,p.po_sn,p.po_name,s.supplier_name,p.wms_simulation_code store_code FROM po_info p JOIN supplier_info s ON s.company_key=p.company_key AND s.supplier_sn=p.supplier_sn WHERE p.company_key=? AND p.po_state IN(2,3,4,5) AND (p.po_sn LIKE ? ESCAPE '!' OR p.po_name LIKE ? ESCAPE '!') ORDER BY p.id DESC LIMIT 20",c,pattern,pattern));}
 @GetMapping("/carriers") public AjaxResult carriers(@RequestParam long purchaseId,@RequestParam(defaultValue="") String province,@RequestParam(defaultValue="OUTBOUND") String flow,@RequestParam(defaultValue="") String keyword){permission("query");return AjaxResult.success(carrierDirectory.options(WarehouseCompany.current(),purchaseId,flow,province,keyword));}
 @GetMapping("/{id}") public AjaxResult detail(@PathVariable long id){permission("query");return AjaxResult.success(service.detail(WarehouseCompany.current(),id));}
 @PostMapping public AjaxResult create(@RequestBody Map<String,Object> cmd){permission("create");return AjaxResult.success(service.create(WarehouseCompany.current(),cmd,SecurityUtils.getUsername()));}
 @PostMapping("/{id}/approve") public AjaxResult approve(@PathVariable long id,@RequestBody Map<String,Object> cmd){permission("approve");return AjaxResult.success(service.approve(WarehouseCompany.current(),id,cmd,SecurityUtils.getUsername()));}
 @PostMapping("/{id}/cancel") public AjaxResult cancel(@PathVariable long id,@RequestBody Map<String,Object> cmd){permission("cancel");return AjaxResult.success(service.cancel(WarehouseCompany.current(),id,cmd,SecurityUtils.getUsername()));}
 @PostMapping("/{id}/retry") public AjaxResult retry(@PathVariable long id,@RequestBody Map<String,Object> cmd){permission("retry");service.retry(WarehouseCompany.current(),id,cmd,SecurityUtils.getUsername());return AjaxResult.success();}
 @PostMapping("/{id}/query") public AjaxResult query(@PathVariable long id){permission("queryWarehouse");return AjaxResult.success(service.query(WarehouseCompany.current(),id));}
 @PostMapping("/{id}/funds") public AjaxResult funds(@PathVariable long id,@RequestBody Map<String,Object> cmd){permission("funds");return AjaxResult.success(service.funds(WarehouseCompany.current(),id,cmd,SecurityUtils.getUsername()));}
 @PostMapping("/{id}/continue") public AjaxResult continueRemaining(@PathVariable long id,@RequestBody Map<String,Object> cmd){permission("continue");return AjaxResult.success(service.continueRemaining(WarehouseCompany.current(),id,cmd,SecurityUtils.getUsername()));}
 @PostMapping("/{id}/supplier-receipt") public AjaxResult supplierReceipt(@PathVariable long id,@RequestBody Map<String,Object> cmd){permission("supplierReceipt");return AjaxResult.success(service.supplierReceipt(WarehouseCompany.current(),id,cmd,SecurityUtils.getUsername()));}
 @PostMapping("/{id}/reclaims") public AjaxResult reclaim(@PathVariable long id,@RequestBody Map<String,Object> cmd){permission("reclaimCreate");return AjaxResult.success(reclaims.create(WarehouseCompany.current(),id,cmd,SecurityUtils.getUsername()));}
 @GetMapping("/reclaims/{id}") public AjaxResult reclaimDetail(@PathVariable long id){permission("query");return AjaxResult.success(reclaims.detail(WarehouseCompany.current(),id));}
 @PostMapping("/reclaims/{id}/{action}") public AjaxResult reclaimAction(@PathVariable long id,@PathVariable String action,@RequestBody Map<String,Object> cmd){permission(action.equals("approve")?"reclaimApprove":action.equals("query")?"queryWarehouse":"reclaimRetry");return AjaxResult.success(action.equals("approve")?reclaims.approve(WarehouseCompany.current(),id,cmd,SecurityUtils.getUsername()):reclaims.action(WarehouseCompany.current(),id,action,cmd,SecurityUtils.getUsername()));}
 @PostMapping("/{id}/executions/{execution}/query") public AjaxResult executionQuery(@PathVariable long id,@PathVariable long execution){permission("queryWarehouse");fulfillment.query(WarehouseCompany.current(),id,execution);return AjaxResult.success(service.detail(WarehouseCompany.current(),id));}
 @GetMapping("/{id}/restore/{request}/check") public AjaxResult restoreCheck(@PathVariable long id,@PathVariable long request){permission("query");return AjaxResult.success(fulfillment.restoreCheck(WarehouseCompany.current(),id,request));}
 @PostMapping("/{id}/restore/{request}/approve") public AjaxResult restore(@PathVariable long id,@PathVariable long request,@RequestBody Map<String,Object>cmd){permission("restoreApprove");fulfillment.restoreApprove(WarehouseCompany.current(),id,request,cmd,SecurityUtils.getUsername());return AjaxResult.success(service.detail(WarehouseCompany.current(),id));}
 @PostMapping("/{id}/parcels/supplement") public AjaxResult supplement(@PathVariable long id,@RequestBody Map<String,Object>cmd){permission("parcel");parcels.supplement(WarehouseCompany.current(),id,cmd,SecurityUtils.getUsername());return AjaxResult.success(service.detail(WarehouseCompany.current(),id));}
 @PostMapping("/{id}/parcels/allocate") public AjaxResult allocate(@PathVariable long id,@RequestBody Map<String,Object>cmd){permission("parcel");parcels.allocate(WarehouseCompany.current(),id,cmd,SecurityUtils.getUsername());return AjaxResult.success(service.detail(WarehouseCompany.current(),id));}
 @PostMapping("/{id}/supplier-bind") public AjaxResult bindSupplier(@PathVariable long id,@RequestBody Map<String,Object>cmd){permission("supplierReceipt");parcels.bindSupplier(WarehouseCompany.current(),id,cmd,SecurityUtils.getUsername());return AjaxResult.success(service.detail(WarehouseCompany.current(),id));}
 @ExceptionHandler(com.oms.supplychain.service.wms.PurchaseReturnRestoreCheck.Blocked.class) public AjaxResult restoreBlocked(com.oms.supplychain.service.wms.PurchaseReturnRestoreCheck.Blocked e){return AjaxResult.error(e.getMessage(),e.getCheck());}
 @ExceptionHandler(IllegalArgumentException.class) public AjaxResult invalid(IllegalArgumentException e){return AjaxResult.error(e.getMessage());}
 @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class) public AjaxResult duplicate(){return AjaxResult.error("创建请求已经存在，请按原单号刷新核对");}
}
