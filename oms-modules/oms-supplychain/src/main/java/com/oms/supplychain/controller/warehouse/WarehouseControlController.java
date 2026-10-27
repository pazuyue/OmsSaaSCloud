package com.oms.supplychain.controller.warehouse;
import com.oms.supplychain.service.wms.*;
import com.oms.supplychain.service.warehouse.WarehouseCompany;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.security.auth.AuthUtil;
import com.ruoyi.common.security.utils.SecurityUtils;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.util.*;
@RestController @RequestMapping("/warehouseControl") public class WarehouseControlController {
 @Resource WarehouseTransfers transfers;@Resource WarehouseReconciliation reconciliation;@Resource WarehouseControlScope scope;@Resource WarehouseFullReconciliation full;
 private String c(){return WarehouseCompany.current();}private String actor(){return SecurityUtils.getUsername();}
 private void permission(String kind,String action){AuthUtil.checkPermi("warehouse:"+kind+":"+action);}
 @GetMapping("/options") public AjaxResult options(){AuthUtil.checkPermiOr("warehouse:transfer:query","warehouse:reconciliation:query");return AjaxResult.success(scope.options(c()));}
 @PostMapping("/sources") public AjaxResult sources(@RequestBody Map<String,Object>cmd){permission("transfer","query");return AjaxResult.success(transfers.sources(c(),WmsStore.required(cmd,"store_code",64),scope.skus(cmd.get("skus"))));}
 @GetMapping("/transfers") public AjaxResult transfers(@RequestParam Map<String,String>q){permission("transfer","query");return AjaxResult.success(transfers.list(c(),q));}
 @GetMapping("/transfers/{id}") public AjaxResult transfer(@PathVariable long id){permission("transfer","query");return AjaxResult.success(transfers.detail(c(),id));}
 @PostMapping("/transfers") public AjaxResult create(@RequestBody Map<String,Object>cmd){permission("transfer","create");return AjaxResult.success(transfers.create(c(),cmd,actor()));}
 @PostMapping("/transfers/{id}/{action}") public AjaxResult transferAction(@PathVariable long id,@PathVariable String action,@RequestBody Map<String,Object>cmd){permission("transfer",action.equals("query")?"queryWarehouse":action.equals("approve")?"approve":action.equals("recover")?"recover":action.equals("stop")?"stop":"retry");return AjaxResult.success(action.equals("approve")?transfers.approve(c(),id,cmd,actor()):action.equals("recover")?transfers.recover(c(),id,cmd,actor()):transfers.action(c(),id,action,cmd,actor()));}
 @GetMapping("/reconciliations") public AjaxResult reconciliations(@RequestParam Map<String,String>q){permission("reconciliation","query");return AjaxResult.success(reconciliation.list(c(),q));}
 @GetMapping("/reconciliations/{id}") public AjaxResult reconciliation(@PathVariable long id){permission("reconciliation","query");return AjaxResult.success(reconciliation.detail(c(),id));}
 @PostMapping("/reconciliations") public AjaxResult reconcile(@RequestBody Map<String,Object>cmd){permission("reconciliation","create");return AjaxResult.success(reconciliation.create(c(),cmd,actor()));}
 @PostMapping("/reconciliations/{id}/retry") public AjaxResult retry(@PathVariable long id){permission("reconciliation","create");return AjaxResult.success(reconciliation.retry(c(),id));}
 @PostMapping("/reconciliations/{id}/adjustment") public AjaxResult adjustment(@PathVariable long id,@RequestBody Map<String,Object>cmd){permission("reconciliation","adjust");return AjaxResult.success(reconciliation.adjustment(c(),id,cmd,actor()));}
 @PostMapping("/adjustments/{id}/review") public AjaxResult review(@PathVariable long id,@RequestBody Map<String,Object>cmd){permission("reconciliation","review");return AjaxResult.success(reconciliation.review(c(),id,cmd,actor()));}
 @PostMapping("/adjustments/{id}/retry") public AjaxResult retryAdjustment(@PathVariable long id){permission("reconciliation","review");reconciliation.postAdjustment(c(),id);return AjaxResult.success();}
 @GetMapping("/reconciliation-goods") public AjaxResult reconciliationGoods(@RequestParam String store_code,@RequestParam String keyword){permission("reconciliation","create");return AjaxResult.success(full.search(c(),store_code,keyword));}
 @GetMapping("/reconciliation-scope") public AjaxResult reconciliationScope(@RequestParam String store_code){permission("reconciliation","create");return AjaxResult.success(full.preview(c(),store_code));}
 @GetMapping("/reconciliations/{id}/results") public AjaxResult results(@PathVariable long id,@RequestParam Map<String,String>q){permission("reconciliation","query");return AjaxResult.success(full.results(c(),id,q));}
 @PostMapping("/reconciliations/{id}/control/{action}") public AjaxResult control(@PathVariable long id,@PathVariable String action){permission("reconciliation","create");return AjaxResult.success(full.control(c(),id,action,actor()));}
 @PostMapping("/reconciliations/{id}/recheck") public AjaxResult recheck(@PathVariable long id,@RequestBody Map<String,Object>cmd){permission("reconciliation","create");return AjaxResult.success(full.recheck(c(),id,cmd,actor()));}
 @GetMapping("/reconciliations/{id}/export") public void export(@PathVariable long id,@RequestParam Map<String,String>q,javax.servlet.http.HttpServletResponse response)throws java.io.IOException{permission("reconciliation","query");response.setContentType("text/csv;charset=UTF-8");response.setHeader("Content-Disposition","attachment; filename=inventory-check-"+id+".csv");full.export(c(),id,q,new java.io.OutputStreamWriter(response.getOutputStream(),java.nio.charset.StandardCharsets.UTF_8));}
 @GetMapping("/inventory-reports") public AjaxResult reports(){permission("reconciliation","query");return AjaxResult.success(reconciliation.reports(c()));}
 @ExceptionHandler(IllegalArgumentException.class) public AjaxResult invalid(IllegalArgumentException e,javax.servlet.http.HttpServletResponse response){response.setContentType("application/json;charset=UTF-8");return AjaxResult.error(e.getMessage());}
 @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class) public AjaxResult duplicate(){return AjaxResult.error("该请求或快照已创建单据，请刷新原单核对");}
}
