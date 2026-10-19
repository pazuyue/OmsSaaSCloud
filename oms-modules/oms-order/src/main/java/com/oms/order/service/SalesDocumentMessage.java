package com.oms.order.service;
import com.oms.messaging.*;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import javax.annotation.Resource;import java.util.*;
import static com.oms.messaging.MessageOutbox.*;
@Component @ConditionalOnProperty(name="oms.messaging.enabled",havingValue="true") public class SalesDocumentMessage implements MessageHandler {
 @Resource OrderSalesDocumentService sales;@Resource OrderRepository repository;@Resource MessageOutbox outbox;
 public Map<String,Object> context(String c,Map<String,Object> m){Map<String,Object> r=new LinkedHashMap<>();r.put("path","/oms-order/shipments");r.put("number",s(m,"business_number"));return r;}
 public String type(){return "SALES_DOCUMENT";}
 public Outcome handle(String c,Map<String,Object> m){long id=n(m,"business_id");sales.sync(c,id);Map<String,Object> t=repository.db().queryForMap("SELECT * FROM oms_order_sales_document_sync WHERE company_code=? AND shipment_id=?",c,id);if(n(t,"synced_dirty_version")>=n(m,"business_version")&&n(t,"synced_revision")>=n(t,"revision"))return Outcome.done("销售出库单据已同步；不重复执行库存出入账");String error=s(t,"last_error");return Outcome.retry(error.isEmpty()?"等待原同步任务完成":error,30);}
 public void replay(String c,Map<String,Object> m,String actor,String reason){long id=n(m,"business_id");Map<String,Object> t=repository.db().queryForMap("SELECT dirty_version FROM oms_order_sales_document_sync WHERE company_code=? AND shipment_id=?",c,id);repository.db().update("UPDATE oms_order_sales_document_sync SET next_attempt=NOW() WHERE company_code=? AND shipment_id=?",c,id);outbox.enqueue(c,type(),id,n(t,"dirty_version"),s(m,"business_number"),"RETRY:"+s(m,"event_id")+":"+token(),0);}
}
