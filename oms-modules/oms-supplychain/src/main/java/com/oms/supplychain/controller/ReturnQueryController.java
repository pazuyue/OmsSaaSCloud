package com.oms.supplychain.controller;
import com.oms.supplychain.service.wms.ReturnQueryWarehouse;
import com.oms.supplychain.service.wms.ReturnDocumentRead;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.security.annotation.InnerAuth;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.util.*;
@RestController @RequestMapping("/afterSaleReturns/internal/query") public class ReturnQueryController {
    @Resource ReturnQueryWarehouse query;
    @Resource ReturnDocumentRead documents;
    @InnerAuth @GetMapping("/document") public R<Map<String,Object>> document(@RequestParam("company_code") String c,@RequestParam String sn,@RequestParam String section,@RequestParam int page,@RequestParam int size){return R.ok(documents.read(c,sn,section,page,size));}
    @InnerAuth @GetMapping("/changes") public R<List<Map<String,Object>>> changes(@RequestParam("company_code") String c){return R.ok(query.changes(c));}
    @InnerAuth @PostMapping("/acknowledge") public R<Boolean> acknowledge(@RequestParam("company_code") String c,@RequestBody List<Map<String,Object>> rows){query.acknowledge(c,rows);return R.ok(true);}
    @InnerAuth @PostMapping("/snapshots") public R<List<Map<String,Object>>> snapshots(@RequestParam("company_code") String c,@RequestBody List<Map<String,Object>> sources){return R.ok(query.snapshots(c,sources));}
}
