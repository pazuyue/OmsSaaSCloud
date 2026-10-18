package com.oms.supplychain.controller;
import com.oms.supplychain.service.wms.ExchangeReturnService;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.security.annotation.InnerAuth;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.util.*;
@RestController @RequestMapping("/exchangeReturns/internal") public class ExchangeReturnController {
    @Resource private ExchangeReturnService returns;
    @InnerAuth @PostMapping("/{exchange}") public R<Map<String,Object>> prepare(@RequestParam("company_code") String company,@PathVariable long exchange,@RequestBody Map<String,Object> body){return R.ok(returns.prepare(company,exchange,body));}
    @InnerAuth @GetMapping("/{exchange}") public R<Map<String,Object>> detail(@RequestParam("company_code") String company,@PathVariable long exchange){return R.ok(returns.detail(company,exchange));}
    @InnerAuth @PostMapping("/{exchange}/{action}") public R<Map<String,Object>> action(@RequestParam("company_code") String company,@PathVariable long exchange,@PathVariable String action){return R.ok(returns.action(company,exchange,action));}
    @InnerAuth @PostMapping("/{exchange}/documents/{document}/{action}") public R<Map<String,Object>> document(@RequestParam("company_code") String company,@PathVariable long exchange,@PathVariable long document,@PathVariable String action,@RequestBody Map<String,Object> body){return R.ok(returns.documentAction(company,exchange,document,action,body));}
}
