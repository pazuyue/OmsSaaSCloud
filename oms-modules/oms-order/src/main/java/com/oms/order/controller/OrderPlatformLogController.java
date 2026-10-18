package com.oms.order.controller;
import com.oms.order.service.OrderPlatformLogWorkspace;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.security.annotation.InnerAuth;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.util.*;
@RestController @RequestMapping("/platformLogBusiness/internal") public class OrderPlatformLogController {
    @Resource OrderPlatformLogWorkspace workspace;
    @InnerAuth @PostMapping("/resolve") public R<List<Map<String,Object>>> resolve(@RequestParam("company_code") String company,@RequestBody Map<String,Object> input){return R.ok(workspace.resolve(company,input));}
    @InnerAuth @PostMapping("/states") public R<List<Map<String,Object>>> states(@RequestParam("company_code") String company,@RequestBody List<Map<String,Object>> refs){return R.ok(workspace.states(company,refs));}
}
