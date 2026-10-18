package com.oms.order.service;

import com.ruoyi.common.core.constant.SecurityConstants;
import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import java.util.*;
import java.util.function.Supplier;
import static com.oms.order.service.OrderValues.*;
import static com.oms.order.service.ExchangeService.map;

/** Central recording for the existing local simulated shipping gateway; no production transport. */
@Service public class OrderPlatformInteractionRecorder {
    public static class NotStarted extends RuntimeException {public NotStarted(){super("平台调用日志未登记，尚未执行回传，可按原请求重试");}}
    @Resource OrderRepository repository;
    @Resource OrderPlatformInteractionClient client;
    private void ref(List<Map<String,Object>> refs,String type,Object value){if(value!=null&&!value.toString().isEmpty())refs.add(map("type",type,"number",value.toString()));}
    public Map<String,Object> record(Map<String,Object> task,boolean query,Supplier<Map<String,Object>> invoke){
        require("SIMULATION".equals(text(task,"mode")),"真实平台回传尚未启用");String company=text(task,"company_code");Map<String,Object> payload;try{payload=JSON.readValue(text(task,"payload_json"),Map.class);}catch(Exception e){throw new IllegalArgumentException("模拟回传快照无法读取");}
        List<Map<String,Object>> refs=new ArrayList<>();ref(refs,"TASK",task.get("request_id"));ref(refs,"PLATFORM_TID",payload.get("platform_tid"));ref(refs,"PACKAGE",payload.get("package_code"));ref(refs,"WAYBILL",payload.get("express_code"));
        List<Map<String,Object>> rows=repository.db().query("SELECT s.shipment_sn,o.order_sn FROM oms_order_shipment s JOIN oms_order o ON o.company_code=s.company_code AND o.id=s.order_id WHERE s.company_code=? AND s.id=?",ROW,company,task.get("shipment_id"));require(rows.size()==1,"模拟发货单来源不完整");ref(refs,"SHIPMENT",rows.get(0).get("shipment_sn"));ref(refs,"OMS_ORDER",rows.get(0).get("order_sn"));
        Map<String,Object> log;try{log=data(client.start(company,map("channel_id",task.get("channel_id"),"method",query?"SIMULATION.shipping.query":"SIMULATION.shipping.send","operation_id",task.get("request_id"),"actor",task.getOrDefault("created_by","平台模拟回传任务"),"refs",refs,"request",payload),SecurityConstants.INNER));require(log!=null&&log.get("id")!=null,"模拟日志未登记，未执行回传");}catch(RuntimeException e){throw new NotStarted();}long started=System.nanoTime();
        try{Map<String,Object> result=invoke.get();String state=text(result,"state");Map<String,Object> body=new LinkedHashMap<>(log);body.putAll(map("result","SUCCESS".equals(state)?"SUCCESS":Arrays.asList("FAILED","RETRYABLE").contains(state)?"FAILED":"UNKNOWN","response",result,"duration_ms",(System.nanoTime()-started)/1000000));data(client.finish(company,body,SecurityConstants.INNER));return result;}
        catch(RuntimeException e){Map<String,Object> body=new LinkedHashMap<>(log);body.putAll(map("result","UNKNOWN","error_code","SIMULATION_RESULT_UNCONFIRMED","response",Collections.emptyMap(),"duration_ms",(System.nanoTime()-started)/1000000));try{client.finish(company,body,SecurityConstants.INNER);}catch(RuntimeException ignored){/* Unfinished durable record remains UNKNOWN; task queries its original identity. */}throw e;}
    }
}
