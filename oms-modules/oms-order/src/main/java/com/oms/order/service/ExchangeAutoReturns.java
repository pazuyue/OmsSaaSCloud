package com.oms.order.service;
import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import java.util.*;
import static com.oms.order.service.OrderValues.*;
import static com.oms.order.service.ExchangeService.*;
import com.ruoyi.common.core.constant.SecurityConstants;

/** Preserve the original source and return identity; parcel aggregation does not merge HH ownership. */
@Service public class ExchangeAutoReturns {
    @Resource OrderRepository repository;
    @Resource ExchangeReturnClient returns;
    @Resource SharedReturnClient parcels;
    @Resource ExchangeFlowPolicy policies;
    Map<String,Object> advance(String c,Map<String,Object> e,Map<String,Object> p){
        long id=number(e,"id");boolean existing=!text(e,"return_sn").isEmpty();
        require(existing||ExchangeFlowPolicy.on(p,"auto_return_create"),"自动创建退回入库单未启用，请从退回安排入口处理");
        Map<String,Object> basis=new LinkedHashMap<>(object(object(e.get("approved_snapshot")).get("return_basis")));
        if(text(basis,"owner_code").isEmpty()){
            List<Map<String,Object>> actual=repository.db().query("SELECT s.owner_code FROM oms_order_shipment s JOIN oms_order_shipment_line l ON l.company_code=s.company_code AND l.shipment_id=s.id WHERE s.company_code=? AND s.id=? AND s.order_id=? AND l.id=? AND l.order_line_id=? AND l.sku_sn=? AND l.shipped_quantity>=?",ROW,c,basis.get("original_shipment_id"),e.get("order_id"),e.get("source_shipment_line_id"),e.get("order_line_id"),e.get("original_sku"),e.get("requested_quantity"));
            require(actual.size()==1&&!text(actual.get(0),"owner_code").isEmpty(),"原实际发货货主无法核对，请从实际发货来源处理");basis.put("owner_code",actual.get(0).get("owner_code"));
        }
        Map<String,Object> input=map("exchange_sn",e.get("exchange_sn"),"store_code",e.get("return_store"),"return_basis",basis,"sender",object(e.get("approved_snapshot")).get("replacement_recipient"),"sku_sn",e.get("original_sku"),"quantity",e.get("requested_quantity"),"platform_tid",e.get("platform_tid"),"order_sn",repository.db().queryForObject("SELECT order_sn FROM oms_order WHERE company_code=? AND id=?",String.class,c,e.get("order_id")));
        policies.requireCurrent(c,number(e,"channel_id"),number(p,"revision"),existing?"":"auto_return_create");
        Map<String,Object> actual=null;
        if(ExchangeFlowPolicy.on(p,"auto_return_merge")){
            Map<String,Object> payload=object(e.get("payload"));String carrier=text(payload,"buyer_logistic_name"),waybill=text(payload,"buyer_logistic_no");
            if(e.get("return_logistics")!=null){try{List<?> items=JSON.readValue(text(e,"return_logistics"),List.class);if(items.size()==1){Map<String,Object> chosen=object(items.get(0));carrier=text(chosen,"carrier");waybill=text(chosen,"tracking_no");}else require(items.isEmpty(),"多组退回物流请人工核对包裹及商品归属");}catch(java.io.IOException ex){throw new IllegalArgumentException("退回物流记录无法核对");}}
            if(carrier.isEmpty()||waybill.isEmpty())return map("posted_quantity",0,"receipt_state","WAITING","pending_review_count",0,"waiting_logistics",true);
            carrier=RefundParcelAutomation.carrier(carrier);
            Map<String,Object> command=map("source",input,"channel_id",e.get("channel_id"),"platform_tid",e.get("platform_tid"),"platform_oid",e.get("platform_oid"),"carrier",carrier,"waybill",waybill,"collect_seconds",p.get("collect_seconds"),"dispatch_enabled",p.get("auto_return_dispatch"),"allocate_enabled",p.get("auto_allocate_receipt"),"mixed_enabled",p.get("auto_mixed_return_merge"),"policy_revision",p.get("revision"),"owner_sn",e.get("exchange_sn"));
            actual=data(parcels.advance(c,"EXCHANGE",id,command,SecurityConstants.INNER));
        }else{Map<String,Object> current=existing?data(returns.detail(c,id,SecurityConstants.INNER)):Collections.emptyMap();boolean prepare=!existing||Arrays.asList("PENDING","UNKNOWN","SENDING").contains(text(current,"dispatch_state"))||number(current,"accepted_quantity")>number(current,"posted_quantity");if(prepare){input.put("dispatch_enabled",p.get("auto_return_dispatch"));data(returns.prepare(c,id,input,SecurityConstants.INNER));}else actual=current;}
        if(actual==null)actual=data(returns.detail(c,id,SecurityConstants.INNER));repository.db().update("UPDATE oms_exchange SET return_sn=?,return_result=?,stage=?,state=IF(state='APPROVED','PROCESSING',state),next_attempt=DATE_ADD(NOW(),INTERVAL 30 SECOND) WHERE company_code=? AND id=?",actual.get("return_sn"),encode(returnSnapshot(actual)),number(actual,"posted_quantity")==number(e,"requested_quantity")?"RETURN_QC":"WAIT_RETURN",c,id);return actual;
    }
}
