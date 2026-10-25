package com.oms.order.service;
import com.oms.messaging.*;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import javax.annotation.Resource;
import java.util.*;
import static com.oms.order.service.OrderValues.*;
import static com.oms.order.service.RefundValues.*;
/** Company scans enqueue bounded work; remote warehouse/inventory waits run in independent workers. */
@Component @ConditionalOnProperty(name="oms.messaging.enabled",havingValue="true")
public class RefundGoodsTaskMessage implements MessageHandler {
 @Resource RefundGoodsTasks tasks;
 public String type(){return "REFUND_GOODS";}
 public Map<String,Object> context(String c,Map<String,Object> message){Map<String,Object> task=tasks.refunds.repository.db().queryForMap("SELECT refund_id FROM oms_refund_goods_task WHERE company_code=? AND id=?",c,number(message,"business_id"));return m("path","/oms-order/businessRefunds","id",task.get("refund_id"));}
 public Outcome handle(String c,Map<String,Object> message){long id=number(message,"business_id");tasks.process(c,id);Map<String,Object> task=tasks.refunds.repository.db().queryForMap("SELECT state,result_message FROM oms_refund_goods_task WHERE company_code=? AND id=?",c,id);return Arrays.asList("DONE","CANCELED","WAIT_RETURN","MANUAL").contains(text(task,"state"))?Outcome.done(text(task,"result_message")):OrderPullMessage.waiting(text(task,"result_message"),30);}
 public void replay(String c,Map<String,Object> message,String actor,String reason){long id=number(message,"business_id");require(tasks.refunds.repository.db().update("UPDATE oms_refund_goods_task SET state=IF(command_json IS NULL,'PENDING','UNKNOWN'),next_attempt=NOW(),control_version=control_version+1 WHERE company_code=? AND id=? AND state NOT IN ('DONE','CANCELED','WAIT_RETURN') AND (lease_until IS NULL OR lease_until<NOW())",c,id)==1,"原货物任务已结束或正在处理，请查看原业务结果");tasks.scan(c);}
}
