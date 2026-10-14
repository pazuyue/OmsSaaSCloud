package com.oms.order.service;

import com.ruoyi.common.core.constant.SecurityConstants;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.annotation.Resource;
import java.util.*;
import static com.oms.order.service.OrderValues.*;

/** Human decisions authorize an immutable signed receipt; the existing worker owns stock posting. */
@Service
public class OrderShipmentReceiptReviewService {
    @Resource private OrderRepository repository;
    @Resource private OrderShipmentWmsClient warehouse;
    @Resource private OrderWarehouseClient inventory;
    @Resource private OrderShipmentDispatchService locks;
    @Resource private OrderShipmentReceiptService receipts;
    /** Batch projection only: a rejected callback never changes fulfillment or stock here. */
    public void attach(String company,List<Map<String,Object>> shipments){
        List<Map<String,Object>> applicable=new ArrayList<>();
        for(Map<String,Object> row:shipments){row.put("receipt_review_pending_count",0L);row.put("receipt_review_lookup_failed",false);if(Boolean.TRUE.equals(row.get("can_reconcile_receipts")))applicable.add(row);}
        for(int start=0;start<applicable.size();start+=100){
            List<Map<String,Object>> batch=applicable.subList(start,Math.min(start+100,applicable.size()));
            List<Long> ids=new ArrayList<>();for(Map<String,Object> row:batch)ids.add(number(row,"id"));
            try{List<Map<String,Object>> summaries=data(warehouse.reviewSummaries(company,ids,SecurityConstants.INNER));Map<Long,Long> counts=new HashMap<>();for(Map<String,Object> summary:summaries)counts.put(number(summary,"shipment_id"),number(summary,"pending_count"));for(Map<String,Object> row:batch)row.put("receipt_review_pending_count",counts.getOrDefault(number(row,"id"),0L));}
            catch(RuntimeException e){for(Map<String,Object> row:batch)row.put("receipt_review_lookup_failed",true);}
        }
    }
    public void attachOrders(String company,List<Map<String,Object>> orders){
        if(orders.isEmpty())return;
        List<Object> args=new ArrayList<>();args.add(company);for(Map<String,Object> row:orders)args.add(row.get("id"));
        List<Map<String,Object>> shipments=repository.db().query("SELECT s.id,s.order_id,s.shipment_sn FROM oms_order_shipment s JOIN oms_order_shipment_dispatch d ON d.company_code=s.company_code AND d.shipment_id=s.id WHERE s.company_code=? AND s.order_id IN ("+String.join(",",Collections.nCopies(orders.size(),"?"))+") AND d.state NOT IN ('NONE','CANCELED')",ROW,args.toArray());
        for(Map<String,Object> shipment:shipments)shipment.put("can_reconcile_receipts",true);
        attach(company,shipments);summarizeOrders(orders,shipments);
    }
    public void summarizeOrders(List<Map<String,Object>> orders,List<Map<String,Object>> shipments){
        for(Map<String,Object> order:orders){
            long count=0;boolean failed=false;List<Map<String,Object>> pending=new ArrayList<>();
            for(Map<String,Object> shipment:shipments)if(number(shipment,"order_id")==number(order,"id")){
                failed|=Boolean.TRUE.equals(shipment.get("receipt_review_lookup_failed"));
                if(number(shipment,"receipt_review_pending_count")>0){count+=number(shipment,"receipt_review_pending_count");Map<String,Object> item=new LinkedHashMap<>();for(String key:Arrays.asList("id","shipment_sn","receipt_review_pending_count"))item.put(key,shipment.get(key));pending.add(item);}
            }
            order.put("receipt_review_pending_count",count);order.put("receipt_review_lookup_failed",failed);order.put("receipt_review_shipments",pending);
        }
    }
    private Map<String,Object> owned(String company,long shipment){List<Map<String,Object>> rows=repository.db().query("SELECT id,order_id,shipment_sn FROM oms_order_shipment WHERE company_code=? AND id=?",ROW,company,shipment);require(rows.size()==1,"发货单不存在或无权访问");return rows.get(0);}
    public Map<String,Object> report(String company,long shipment){
        Map<String,Object> head=owned(company,shipment);
        List<Map<String,Object>> reviews=data(warehouse.reviews(company,shipment,SecurityConstants.INNER));
        for(Map<String,Object> review:reviews){
            String stock="NOT_POSTED";
            if(number(review,"event_id")>0){
                String request="WO"+hash(company+"/"+text(head,"shipment_sn")+"/"+number(review,"event_id")).substring(0,40);
                try{Map<String,Object> reply=data(inventory.warehouseReceipt(company,request,SecurityConstants.INNER));stock="COMPLETED".equals(text(reply,"state"))?"POSTED":"MISSING".equals(text(reply,"state"))?"NOT_POSTED":"UNKNOWN";}catch(RuntimeException e){stock="UNKNOWN";}
            }
            review.put("stock_state",stock);
        }
        Map<String,Object> report=new LinkedHashMap<>();report.put("shipment_id",shipment);report.put("shipment_sn",head.get("shipment_sn"));report.put("reviews",reviews);return report;
    }
    public Map<String,Object> decide(String company,long shipment,long review,Map<String,Object> input,String actor){
        String decision=text(input,"decision"),reason=text(input,"reason").trim();require(Arrays.asList("CONFIRM_SHARED","REJECT").contains(decision)&&!reason.isEmpty()&&reason.length()<=200&&input.containsKey("control_version"),"请选择处理结论并填写核对说明（最多200字）");
        owned(company,shipment);
        new TransactionTemplate(new DataSourceTransactionManager(repository.db().getDataSource())).execute(status->{
            Map<String,Object> head=locks.lock(company,shipment,null,false);
            require(!Arrays.asList("VOIDING","VOIDED").contains(text(head,"status")),"发货单已停止履约，不能接纳出库回传");
            Map<String,Object> body=new LinkedHashMap<>();body.put("decision",decision);body.put("reason",reason);body.put("actor",actor);body.put("control_version",number(input,"control_version"));
            body.put("related_shipment_ids",repository.db().queryForList("SELECT id FROM oms_order_shipment WHERE company_code=? AND order_id=?",Long.class,company,head.get("order_id")));
            data(warehouse.review(company,shipment,review,body,SecurityConstants.INNER));
            repository.db().update("INSERT INTO oms_order_shipment_log(company_code,shipment_id,action,result,message,operator_name) VALUES (?,?,'RECEIPT_REVIEW','SUCCESS',?,?)",company,shipment,("REJECT".equals(decision)?"拒绝原回传：":"确认共用运单并提交原回传记账：")+reason,actor);
            return null;
        });
        // A lost response is safe: warehouse approval and event creation committed together,
        // and the automatic worker can finish using the same stable stock request.
        if("CONFIRM_SHARED".equals(decision))receipts.reconcile(company,shipment,actor);
        return report(company,shipment);
    }
}
