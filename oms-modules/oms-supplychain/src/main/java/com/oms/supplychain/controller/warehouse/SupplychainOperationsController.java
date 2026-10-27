package com.oms.supplychain.controller.warehouse;
import com.oms.supplychain.service.wms.*;
import com.oms.supplychain.service.warehouse.WarehouseCompany;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.security.auth.AuthUtil;
import com.ruoyi.common.security.utils.SecurityUtils;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.util.*;
@RestController @RequestMapping("/purchaseWorkspace") public class SupplychainOperationsController {
    @Resource SupplychainOperations operations;
    @Resource PurchaseReceiptDifferences differences;
    @GetMapping("/operations") public AjaxResult operations(@RequestParam Map<String,String> query){AuthUtil.checkPermi("warehouse:operations:list");return AjaxResult.success(operations.query(WarehouseCompany.current(),query));}
    @GetMapping("/differences") public AjaxResult differences(@RequestParam String kind,@RequestParam long id){AuthUtil.checkPermi("warehouse:poInfo:differenceQuery");return AjaxResult.success(differences.list(WarehouseCompany.current(),kind,id));}
    @GetMapping("/differences/{id}") public AjaxResult detail(@PathVariable long id){AuthUtil.checkPermi("warehouse:poInfo:differenceQuery");return AjaxResult.success(differences.detail(WarehouseCompany.current(),id));}
    @PostMapping("/differences/{id}") public AjaxResult decide(@PathVariable long id,@RequestBody Map<String,Object> body){AuthUtil.checkPermi("warehouse:poInfo:differenceReview");if("REARRANGE".equals(body.get("decision")))AuthUtil.checkPermi("warehouse:poInfo:receive");return AjaxResult.success(differences.decide(WarehouseCompany.current(),id,body,SecurityUtils.getUsername()));}
    @ExceptionHandler(IllegalArgumentException.class) public AjaxResult invalid(IllegalArgumentException error){return AjaxResult.error(error.getMessage());}
}
