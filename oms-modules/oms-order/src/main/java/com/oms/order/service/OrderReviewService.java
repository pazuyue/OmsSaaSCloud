package com.oms.order.service;

import org.springframework.stereotype.Service;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import static com.oms.order.service.OrderValues.*;

@Service
public class OrderReviewService {
    @Resource private OrderRepository repository;
    public List<Map<String,Object>> apply(String company,String action,Map<String,Object> body,String actor){
        require(Arrays.asList("approve","hold","resume").contains(action),"订单操作无效");
        Object input=body.get("orders");require(input instanceof List&&!((List<?>)input).isEmpty()&&((List<?>)input).size()<=100,"每次请选择1至100张订单");
        String reason=text(body,"reason").trim();require(reason.length()<=200,"说明不能超过200字");require(!"hold".equals(action)||!reason.isEmpty(),"请填写挂起原因");
        Set<Long> unique=new HashSet<>();List<Map<String,Object>> requested=new ArrayList<>();
        for(Object value:(List<?>)input){require(value instanceof Map,"订单参数无效");Map<String,Object> row=(Map<String,Object>)value;require(number(row,"id")>0&&row.containsKey("control_version")&&number(row,"control_version")>=0,"请刷新订单后操作");require(unique.add(number(row,"id")),"不能重复选择同一订单");requested.add(row);}
        List<Map<String,Object>> results=new ArrayList<>();TransactionTemplate tx=new TransactionTemplate(new DataSourceTransactionManager(repository.db().getDataSource()));
        for(Map<String,Object> request:requested){long id=number(request,"id");Map<String,Object> result=new LinkedHashMap<>();result.put("id",id);
            try{tx.execute(status->{
                List<Map<String,Object>> initial=repository.db().query("SELECT channel_id,platform_order_id FROM oms_order WHERE company_code=? AND id=?",ROW,company,id);require(initial.size()==1,"业务订单不存在或无权访问");
                List<Map<String,Object>> configs=repository.db().query("SELECT lease_until FROM oms_order_pull_config WHERE company_code=? AND channel_id=? FOR UPDATE",ROW,company,initial.get(0).get("channel_id"));require(configs.size()==1,"店铺配置不存在");
                require(configs.get(0).get("lease_until")==null||time(text(configs.get(0),"lease_until")).isBefore(LocalDateTime.now()),"店铺任务正在处理，请稍后刷新再操作");
                Map<String,Object> platform=repository.db().query("SELECT change_pending,fulfillment_block_reason,platform_status,business_order_id,conversion_status FROM oms_platform_order WHERE company_code=? AND id=? FOR UPDATE",ROW,company,initial.get(0).get("platform_order_id")).get(0);
                Map<String,Object> order=repository.db().query("SELECT * FROM oms_order WHERE company_code=? AND id=? FOR UPDATE",ROW,company,id).get(0);
                require(number(order,"control_version")==number(request,"control_version"),"订单已被其他操作更新，请刷新后重试");
                require(number(platform,"business_order_id")==id&&"SUCCESS".equals(text(platform,"conversion_status")),"订单关联状态不一致");
                require("WAIT_ALLOCATION".equals(text(order,"status")),"当前业务阶段不允许此操作");
                require(!"PROCESSING".equals(text(order,"stock_attempt_status")),"上次预占尚未确认完成，请等待重试核对后操作");
                OrderReviewPolicy.requireRemaining(repository,company,id);order.putAll(platform);result.put("order_sn",order.get("order_sn"));String message;
                if("approve".equals(action)){
                    require("PENDING".equals(text(order,"review_status")),"订单已审核，无需重复审核");String block=OrderReviewPolicy.systemBlock(order);require(block.isEmpty(),block);
                    require(!reason.isEmpty(),"请填写人工审核说明，原命中条件将保留");message=reason;
                    repository.db().update("UPDATE oms_order SET review_status='APPROVED',review_decision='HUMAN_APPROVED',review_by=?,review_at=NOW(),review_reason=?,control_version=control_version+1 WHERE company_code=? AND id=?",actor,message,company,id);
                    Map<String,Object> evidence=new LinkedHashMap<>();evidence.put("note",reason);evidence.put("previous_decision",order.get("review_decision"));if(order.get("review_snapshot")!=null)try{evidence.put("original_assessment",JSON.readValue(text(order,"review_snapshot"),Map.class));}catch(Exception e){evidence.put("original_assessment_error","原依据不可读，已保留原始记录");}
                    repository.db().update("INSERT INTO oms_order_review_event(company_code,order_id,action,decision,assessment,operator_name) VALUES (?,?,'HUMAN_APPROVE','HUMAN_APPROVED',?,?)",company,id,encode(evidence),actor);
                }else if("hold".equals(action)){
                    require(number(order,"manual_hold")==0,"订单已人工挂起");message=reason;
                    repository.db().update("UPDATE oms_order SET manual_hold=1,hold_reason=?,hold_by=?,hold_at=NOW(),control_version=control_version+1,stock_attempt_status=IF(stock_attempt_status='QUEUED','DONE',stock_attempt_status) WHERE company_code=? AND id=?",reason,actor,company,id);
                }else{
                    require(number(order,"manual_hold")==1,"订单没有人工挂起");String block=OrderReviewPolicy.systemBlock(order);require(block.isEmpty(),block);
                    message=reason.isEmpty()?"解除人工挂起，保留原审核结果":reason;
                    repository.db().update("UPDATE oms_order SET manual_hold=0,hold_reason='',hold_by='',hold_at=NULL,control_version=control_version+1 WHERE company_code=? AND id=?",company,id);
                    repository.db().update("UPDATE oms_platform_order SET next_retry_at=NOW() WHERE company_code=? AND id=?",company,order.get("platform_order_id"));
                }
                if("resume".equals(action))OrderAutoReviewService.evaluate(repository,company,id,false,"解除挂起后审单");
                if(!"hold".equals(action))repository.autoAllocationChanged(company,id,"approve".equals(action)?"审核通过":"解除人工挂起");
                repository.log(company,number(order,"platform_order_id"),"approve".equals(action)?"REVIEW_APPROVE":"hold".equals(action)?"ORDER_HOLD":"ORDER_RESUME","SUCCESS",message,actor);return null;
            });result.put("success",true);result.put("message","approve".equals(action)?"审核通过，原挂起状态保留":"hold".equals(action)?"已挂起，保留需求和已预占库存":"已解除人工挂起，审核结果保留");}
            catch(IllegalArgumentException e){result.put("success",false);result.put("message",e.getMessage());}
            catch(RuntimeException e){result.put("success",false);result.put("message","处理失败，请稍后刷新重试");}
            results.add(result);
        }
        return results;
    }
}
