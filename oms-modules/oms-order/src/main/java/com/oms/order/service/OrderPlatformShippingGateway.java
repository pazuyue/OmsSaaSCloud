package com.oms.order.service;

import org.springframework.stereotype.Component;
import javax.annotation.Resource;
import java.util.*;
import static com.oms.order.service.OrderValues.*;

/** Phase one: local, durable mock platform. There is intentionally no production transport. */
@Component
public class OrderPlatformShippingGateway {
    @Resource private OrderRepository repository;
    public OrderPlatformShippingGateway(){}
    OrderPlatformShippingGateway(OrderRepository repository){this.repository=repository;}
    static Map<String,Object> result(String state,String message,String receipt){Map<String,Object> out=new LinkedHashMap<>();out.put("state",state);out.put("message",message);out.put("receipt",receipt);return out;}
    @SuppressWarnings("unchecked") public Map<String,Object> send(Map<String,Object> task){
        require("SIMULATION".equals(text(task,"mode")),"真实平台回传尚未启用");
        Map<String,Object> payload;
        try{payload=JSON.readValue(text(task,"payload_json"),Map.class);}catch(Exception e){throw new IllegalArgumentException("回传快照无法解析");}
        require(text(payload,"express_code").startsWith("TEST-"),"模拟回传只允许TEST-测试运单");
        String company=text(task,"company_code"),request=text(task,"request_id"),digest=text(task,"payload_hash"),receipt="SIMSHIP_"+hash(company+"/"+request).substring(0,24);
        repository.db().update("INSERT IGNORE INTO oms_order_platform_shipping_sim_receipt(company_code,request_id,payload_hash,receipt_code) VALUES (?,?,?,?)",company,request,digest,receipt);
        return query(task);
    }
    public Map<String,Object> query(Map<String,Object> task){
        require("SIMULATION".equals(text(task,"mode")),"真实平台回传尚未启用");
        List<Map<String,Object>> rows=repository.db().query("SELECT payload_hash,receipt_code FROM oms_order_platform_shipping_sim_receipt WHERE company_code=? AND request_id=?",ROW,task.get("company_code"),task.get("request_id"));
        if(rows.isEmpty())return result("UNKNOWN","模拟平台尚无确定回执，保留待确认，不重新提交","");
        require(text(rows.get(0),"payload_hash").equals(text(task,"payload_hash")),"回传请求已存在但商品快照不同");
        return result("SUCCESS","模拟平台已接收包裹及实际发货数量；未向天猫发送",text(rows.get(0),"receipt_code"));
    }
}
