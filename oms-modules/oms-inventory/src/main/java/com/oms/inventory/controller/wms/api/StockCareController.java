package com.oms.inventory.controller.wms.api;
import com.oms.inventory.service.impl.StockCareService;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.security.annotation.InnerAuth;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.util.*;
@RestController @RequestMapping("/stockCare/internal") public class StockCareController {
 @Resource StockCareService service;
 private String company(String c){if(c==null||!c.matches("[A-Za-z0-9_-]{1,64}"))throw new IllegalArgumentException("公司无效");return c.toUpperCase(Locale.ROOT);}
 @InnerAuth @PostMapping("/{action}") public R<Map<String,Object>> execute(@RequestParam("company_code") String c,@PathVariable String action,@RequestBody Map<String,Object> cmd){return R.ok(service.execute(company(c),action,cmd));}
 @InnerAuth @GetMapping("/report") public R<Map<String,Object>> report(@RequestParam("company_code") String c,@RequestParam String sn){return R.ok(service.report(company(c),sn));}
 @InnerAuth @PostMapping("/balances") public R<List<Map<String,Object>>> balances(@RequestParam("company_code") String c,@RequestBody List<Map<String,Object>> keys){return R.ok(service.balances(company(c),keys));}
 @ExceptionHandler(IllegalArgumentException.class) public R<Object> invalid(IllegalArgumentException e){return R.fail(e.getMessage());}
 @ExceptionHandler(org.springframework.dao.DataAccessException.class) public R<Object> database(org.springframework.dao.DataAccessException e){org.slf4j.LoggerFactory.getLogger(getClass()).error("Stock care transaction rolled back type={}",e.getClass().getSimpleName());return R.fail("库存写入未完成，已回滚。请按原单重试并查看服务日志");}
}
