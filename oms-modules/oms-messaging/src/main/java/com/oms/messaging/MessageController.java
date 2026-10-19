package com.oms.messaging;
import com.oms.common.tenant.TenantRouting;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import com.ruoyi.common.security.utils.SecurityUtils;
import com.ruoyi.common.core.web.domain.AjaxResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import javax.annotation.Resource;
import java.util.*;
@RestController @RequestMapping("/messages") @ConditionalOnProperty(name="oms.messaging.enabled",havingValue="true")
public class MessageController {
 @Resource MessageOutbox outbox;@Resource MessageRuntime runtime;
 String company(){return TenantRouting.company(SecurityUtils.getLoginUser().getCompanyCode());}
 @RequiresPermissions("oms:message:query") @GetMapping public AjaxResult list(@RequestParam(defaultValue="")String type,@RequestParam(defaultValue="")String publish,@RequestParam(defaultValue="")String state,@RequestParam(defaultValue="")String keyword,@RequestParam(defaultValue="")String problem,@RequestParam(defaultValue="")String focus,@RequestParam(defaultValue="1")int pageNum,@RequestParam(defaultValue="20")int pageSize){return AjaxResult.success(outbox.list(company(),type,publish,state,keyword.trim(),problem,focus,pageNum,pageSize));}
 @RequiresPermissions("oms:message:query") @GetMapping("/{event}") public AjaxResult detail(@PathVariable String event){Map<String,Object> r=outbox.detail(company(),event);r.put("business",runtime.handler(MessageOutbox.s(r,"message_type")).context(company(),r));return AjaxResult.success(r);}
 @RequiresPermissions("oms:message:retry") @PostMapping("/{event}/retry") public AjaxResult retry(@PathVariable String event,@RequestBody Map<String,Object> body){runtime.retry(company(),event,SecurityUtils.getUsername(),MessageOutbox.s(body,"reason"));return AjaxResult.success("已按原业务任务申请重试，请刷新查看新消息");}
 @com.ruoyi.common.security.annotation.InnerAuth @PostMapping("/internal/compensate") public com.ruoyi.common.core.domain.R<Map<String,Object>> compensate(){return com.ruoyi.common.core.domain.R.ok(runtime.compensate());}
 @ExceptionHandler(IllegalArgumentException.class) public AjaxResult invalid(IllegalArgumentException e){return AjaxResult.error(e.getMessage());}
}
