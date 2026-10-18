package com.oms.channel.controller;

import com.oms.channel.platform.PlatformInteractionLog;
import com.oms.channel.platform.PlatformLogQuery;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.security.annotation.InnerAuth;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import com.ruoyi.common.security.utils.SecurityUtils;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.util.*;

@RestController @RequestMapping("/platform/log-center")
public class PlatformLogController {
    @Resource PlatformLogQuery query;
    @Resource PlatformInteractionLog logs;
    @InnerAuth @PostMapping("/internal/refund-sync/start") public R<Map<String,Object>> refundSync(@RequestParam("company_code") String company,@RequestBody Map<String,Object> input){
        long channel=Long.parseLong(String.valueOf(input.get("channel_id")));company=logs.companyForShop(company,channel);String task=String.valueOf(input.get("task_id"));if(!task.matches("[1-9][0-9]{0,18}"))throw new IllegalArgumentException("退款同步任务无效");
        Map<String,Object> context=new LinkedHashMap<>();context.put("source_service","order");context.put("trigger_source","AUTO".equals(input.get("mode"))?"SCHEDULER":"USER");context.put("operation_id","REFSYNC"+task);context.put("interface_name","JDP.refund.read");Map<String,Object> taskRef=new LinkedHashMap<>();taskRef.put("type","TASK");taskRef.put("number","REFSYNC"+task);context.put("refs",Collections.singletonList(taskRef));
        Map<String,Object> request=new LinkedHashMap<>();for(String k:Arrays.asList("task_id","mode","start_time","end_time","page_time","page_refund_id","selected_ids"))request.put(k,input.get(k));
        return R.ok(logs.begin(company,channel,null,"JDP.refund.read","DATA_SYNC","LOCAL","REFUND",String.valueOf(input.getOrDefault("actor","退款同步")),context,request));
    }
    @InnerAuth @PostMapping("/internal/refund-sync/finish") public R<Boolean> refundSyncFinish(@RequestParam("company_code") String company,@RequestBody Map<String,Object> input){
        company=com.oms.common.tenant.TenantRouting.company(company);Map<String,Object> detail=query.detail(company,Long.parseLong(String.valueOf(input.get("id"))));if(!"order".equals(detail.get("source_service"))||!"DATA_SYNC".equals(detail.get("entry_kind"))||!"JDP.refund.read".equals(detail.get("interface_name"))||!input.get("request_id").equals(detail.get("request_id")))throw new IllegalArgumentException("退款同步日志身份不匹配");
        Map<String,Object> log=new LinkedHashMap<>();log.put("company_code",company);log.put("id",input.get("id"));log.put("request_id",input.get("request_id"));log.put("environment","LOCAL");Map<String,Object> response=new LinkedHashMap<>();response.put("count",input.get("records"));List<Map<String,Object>> records=new ArrayList<>();Object raw=input.get("refs");if(!(raw instanceof List)||((List<?>)raw).size()>50)throw new IllegalArgumentException("退款同步关联数量无效");for(Object value:(List<?>)raw){if(!(value instanceof Map))throw new IllegalArgumentException("退款同步关联无效");Map<?,?> ref=(Map<?,?>)value;String number=String.valueOf(ref.get("number"));if(!"PLATFORM_REFUND".equals(ref.get("type"))||!number.matches("[1-9][0-9]{0,19}"))throw new IllegalArgumentException("退款同步关联无效");Map<String,Object> record=new LinkedHashMap<>();record.put("refund_id",number);records.add(record);}response.put("records",records);logs.finish(log,String.valueOf(input.get("result")),String.valueOf(input.getOrDefault("error_code","")),response,Long.parseLong(String.valueOf(input.getOrDefault("duration_ms",0))),null,false);return R.ok(true);
    }
    private String company(){String c=SecurityUtils.getLoginUser().getCompanyCode();if(c==null||c.isEmpty())throw new IllegalArgumentException("请先选择登录公司");return com.oms.common.tenant.TenantRouting.company(c);}
    @RequiresPermissions("channel:platform:log") @GetMapping public AjaxResult list(@RequestParam Map<String,String> input){return AjaxResult.success(query.list(company(),input));}
    @RequiresPermissions("channel:platform:log") @GetMapping("/summary") public AjaxResult summary(@RequestParam Map<String,String> input){return AjaxResult.success(query.summary(company(),input));}
    @RequiresPermissions("channel:platform:log") @GetMapping("/options") public AjaxResult options(){return AjaxResult.success(query.options(company()));}
    @RequiresPermissions("channel:platform:log") @GetMapping("/context") public AjaxResult context(@RequestParam Map<String,String> input){return AjaxResult.success(query.context(company(),input));}
    @RequiresPermissions("channel:platform:log") @GetMapping("/{id}") public AjaxResult detail(@PathVariable long id){return AjaxResult.success(query.detail(company(),id));}
    @RequiresPermissions("channel:platform:payload") @GetMapping("/{id}/payload") public AjaxResult payload(@PathVariable long id){return AjaxResult.success(query.payload(company(),id));}
    @RequiresPermissions("channel:platform:log") @PostMapping("/business-states") public AjaxResult states(@RequestBody List<Map<String,Object>> refs){return AjaxResult.success(query.states(company(),refs));}
    @SuppressWarnings("unchecked") @InnerAuth @PostMapping("/internal/simulation/start") public R<Map<String,Object>> simulation(@RequestParam("company_code") String company,@RequestBody Map<String,Object> input){
        long channel=((Number)input.get("channel_id")).longValue();company=logs.companyForShop(company,channel);String method=String.valueOf(input.get("method"));if(!Arrays.asList("SIMULATION.shipping.send","SIMULATION.shipping.query").contains(method))throw new IllegalArgumentException("模拟日志接口无效");
        Map<String,Object> context=new LinkedHashMap<>();for(String key:Arrays.asList("refs","operation_id"))context.put(key,input.get(key));context.put("source_service","order");context.put("trigger_source","BUSINESS");context.put("root_request_id",input.get("operation_id"));context.put("interface_name",method);
        return R.ok(logs.begin(company,channel,null,method,"SIMULATION","SIMULATION","SHIPPING",String.valueOf(input.getOrDefault("actor","模拟发货任务")),context,input.get("request")));
    }
    @InnerAuth @PostMapping("/internal/simulation/finish") public R<Boolean> finish(@RequestParam("company_code") String company,@RequestBody Map<String,Object> input){
        company=com.oms.common.tenant.TenantRouting.company(company);Map<String,Object> log=new LinkedHashMap<>();log.put("company_code",company);log.put("id",input.get("id"));log.put("request_id",input.get("request_id"));Map<String,Object> detail=query.detail(company,((Number)input.get("id")).longValue());if(!"order".equals(detail.get("source_service"))||!"SIMULATION".equals(detail.get("entry_kind"))||!input.get("request_id").equals(detail.get("request_id")))throw new IllegalArgumentException("模拟记录不匹配");
        log.put("environment","SIMULATION");logs.finish(log,String.valueOf(input.get("result")),String.valueOf(input.getOrDefault("error_code","")),input.get("response"),((Number)input.getOrDefault("duration_ms",0)).longValue(),null,false);return R.ok(true);
    }
    @ExceptionHandler(IllegalArgumentException.class) public AjaxResult validation(IllegalArgumentException e){return AjaxResult.error(e.getMessage());}
}
