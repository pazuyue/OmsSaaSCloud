package com.oms.supplychain.controller;
import com.oms.supplychain.service.wms.SharedReturnParcels;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.security.annotation.InnerAuth;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.util.*;
@RestController @RequestMapping("/afterSaleReturns/internal") public class SharedReturnController {
    @Resource SharedReturnParcels parcels;
    @InnerAuth @PostMapping("/{kind}/{id}") public R<Map<String,Object>> advance(@RequestParam("company_code") String c,@PathVariable String kind,@PathVariable long id,@RequestBody Map<String,Object> body){return R.ok(parcels.advance(c,kind,id,body));}
    @InnerAuth @GetMapping("/{kind}/{id}") public R<Map<String,Object>> report(@RequestParam("company_code") String c,@PathVariable String kind,@PathVariable long id){return R.ok(parcels.report(c,kind,id));}
    @InnerAuth @PostMapping("/{kind}/{id}/{action}") public R<Map<String,Object>> action(@RequestParam("company_code") String c,@PathVariable String kind,@PathVariable long id,@PathVariable String action,@RequestBody Map<String,Object> body){return R.ok(parcels.action(c,kind,id,action,body));}
}
