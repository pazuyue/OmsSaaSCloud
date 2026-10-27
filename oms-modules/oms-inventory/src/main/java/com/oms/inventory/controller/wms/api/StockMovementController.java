package com.oms.inventory.controller.wms.api;
import com.oms.inventory.service.impl.StockMovementService;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.security.annotation.InnerAuth;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.util.*;
@RestController @RequestMapping("/stockMovement/internal") public class StockMovementController {
 @Resource StockMovementService service;
 private String company(String c){if(c==null||!c.matches("[A-Za-z0-9_-]{1,64}"))throw new IllegalArgumentException("公司无效");return c.toUpperCase(Locale.ROOT);}
 @InnerAuth @PostMapping("/{action}") public R<Map<String,Object>> execute(@RequestParam("company_code") String c,@PathVariable String action,@RequestBody Map<String,Object>cmd){return R.ok(action.equals("checkSearch")?service.checkSearch(company(c),cmd):action.equals("discover")?service.discover(company(c),cmd):action.equals("snapshot")?service.snapshot(company(c),cmd):service.execute(company(c),action,cmd));}
 @InnerAuth @GetMapping("/report") public R<Map<String,Object>> report(@RequestParam("company_code") String c,@RequestParam String sn){return R.ok(service.report(company(c),sn));}
 @ExceptionHandler(IllegalArgumentException.class) public R<Object> invalid(IllegalArgumentException e){return R.fail(e.getMessage());}
 @ExceptionHandler(org.springframework.dao.DataAccessException.class) public R<Object> database(org.springframework.dao.DataAccessException e){org.slf4j.LoggerFactory.getLogger(getClass()).error("Stock movement rolled back type={}",e.getClass().getSimpleName());return R.fail("库存事务未完成并已回滚，请沿用原事件重试");}
}
