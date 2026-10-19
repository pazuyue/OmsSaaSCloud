package com.oms.order.service;

import com.oms.common.api.OrderStockClient;
import com.oms.common.model.dto.OrderStockCommand;
import com.ruoyi.common.core.constant.SecurityConstants;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import static com.oms.order.service.OrderValues.*;

/** Persist intent before the remote release. Retrying the same frozen order is safe. */
@Service
public class OrderVoidService {
    @Resource private OrderRepository repository;
    @Resource private OrderStockClient inventory;
    @Resource private OrderCutoffService cutoff;
    private TransactionTemplate tx(){return new TransactionTemplate(new DataSourceTransactionManager(repository.db().getDataSource()));}
    public List<Map<String,Object>> request(String company,Map<String,Object> body,String actor){
        Object input=body.get("orders");require(input instanceof List&&!((List<?>)input).isEmpty()&&((List<?>)input).size()<=100,"每次请选择1至100张订单");
        String reason=text(body,"reason").trim();require(!reason.isEmpty()&&reason.length()<=200,"请填写1至200字作废原因");
        require(number(body,"confirm_local_void")==1,"请确认：仅终止OMS履约，不关闭平台订单或退款，且不支持反作废");
        Set<Long> unique=new HashSet<>();List<Map<String,Object>> requests=new ArrayList<>();
        for(Object value:(List<?>)input){require(value instanceof Map,"订单参数无效");Map<String,Object> row=(Map<String,Object>)value;require(number(row,"id")>0&&row.containsKey("control_version")&&number(row,"control_version")>=0,"请刷新订单后操作");require(unique.add(number(row,"id")),"不能重复选择同一订单");requests.add(row);}
        List<Map<String,Object>> results=new ArrayList<>();
        for(Map<String,Object> request:requests){long id=number(request,"id");Map<String,Object> result=new LinkedHashMap<>();result.put("id",id);
            try{
                if(cutoff!=null){Map<String,Object> state=new OrderBusinessServiceSnapshot(repository).get(company,id);if(number(state,"shipped")>0)throw new IllegalArgumentException("已有实际出库，不能整单作废；请使用截单终止剩余履约");
                    if(number(state,"warehouse_tasks")>0||OrderFulfillmentLines.revised(repository,company,id)){Map<String,Object> cutoffBody=new LinkedHashMap<>();cutoffBody.put("mode","TERMINATE");cutoffBody.put("reason",reason);cutoffBody.put("control_version",request.get("control_version"));cutoff.request(company,id,cutoffBody,actor);repository.db().update("UPDATE oms_order SET void_reason=?,void_by=?,void_requested_at=NOW() WHERE company_code=? AND id=?",reason,actor,company,id);result.put("submitted",true);result.put("status","CUTTING");result.put("message","已申请作废，先核实仓库截停，再确认库存释放");result.put("order_sn",state.get("order_sn"));results.add(result);continue;}}
                tx().execute(transaction->{
                List<Map<String,Object>> initial=repository.db().query("SELECT channel_id,platform_order_id FROM oms_order WHERE company_code=? AND id=?",ROW,company,id);require(initial.size()==1,"业务订单不存在或无权访问");
                List<Map<String,Object>> configs=repository.db().query("SELECT lease_until FROM oms_order_pull_config WHERE company_code=? AND channel_id=? FOR UPDATE",ROW,company,initial.get(0).get("channel_id"));require(configs.size()==1,"店铺配置不存在");
                require(configs.get(0).get("lease_until")==null||time(text(configs.get(0),"lease_until")).isBefore(LocalDateTime.now()),"店铺任务正在执行，请稍后刷新再作废");
                Map<String,Object> platform=repository.db().query("SELECT business_order_id,conversion_status FROM oms_platform_order WHERE company_code=? AND id=? FOR UPDATE",ROW,company,initial.get(0).get("platform_order_id")).get(0);
                Map<String,Object> order=repository.db().query("SELECT * FROM oms_order WHERE company_code=? AND id=? FOR UPDATE",ROW,company,id).get(0);
                require(number(platform,"business_order_id")==id&&"SUCCESS".equals(text(platform,"conversion_status")),"订单关联状态不一致");
                result.put("order_sn",order.get("order_sn"));
                if("VOIDED".equals(text(order,"status"))){result.put("status","VOIDED");result.put("message","订单已作废，无需重复处理");return null;}
                require(number(order,"control_version")==number(request,"control_version"),"订单已变化，请刷新后重试");
                require(Arrays.asList("WAIT_ALLOCATION","VOIDING").contains(text(order,"status")),"仅支持未进入出库流程的订单整单作废");
                new OrderShipmentService(repository).markVoiding(company,id);
                boolean retry="VOIDING".equals(text(order,"status"));
                if(retry){repository.db().update("UPDATE oms_order SET void_error='',control_version=control_version+1 WHERE company_code=? AND id=?",company,id);}
                else repository.db().update("UPDATE oms_order SET status='VOIDING',void_reason=?,void_by=?,void_requested_at=NOW(),void_error='',control_version=control_version+1 WHERE company_code=? AND id=?",reason,actor,company,id);
                repository.db().update("UPDATE oms_platform_order SET next_retry_at=NOW() WHERE company_code=? AND id=?",company,order.get("platform_order_id"));
                repository.log(company,number(order,"platform_order_id"),retry?"VOID_RETRY":"VOID_REQUEST","PENDING",retry?"重新提交作废；原作废原因："+text(order,"void_reason"):reason,actor);
                result.put("status","VOIDING");result.put("message","已提交作废，等待库存释放确认");return null;
            });result.put("submitted",true);}
            catch(IllegalArgumentException e){result.put("submitted",false);result.put("message",e.getMessage());}
            catch(RuntimeException e){result.put("submitted",false);result.put("message","提交失败，请刷新订单确认后重试");}
            results.add(result);
        }
        return results;
    }
    public List<Map<String,Object>> due(String company,long channel){return repository.db().query("SELECT o.id FROM oms_order o JOIN oms_platform_order p ON p.company_code=o.company_code AND p.id=o.platform_order_id WHERE o.company_code=? AND o.channel_id=? AND o.status='VOIDING' AND p.next_retry_at<=NOW() ORDER BY p.next_retry_at,o.id LIMIT 100",ROW,company,channel);}
    public void process(String company,long id,long channel,String lease){
        Map<String,Object> order=tx().execute(transaction->{repository.fence(company,channel,lease);List<Map<String,Object>> found=repository.db().query("SELECT * FROM oms_order WHERE company_code=? AND channel_id=? AND id=? AND status='VOIDING' FOR UPDATE",ROW,company,channel,id);return found.isEmpty()?null:found.get(0);});
        if(order==null)return;
        try{
            OrderStockCommand command=new OrderStockCommand();command.setChannelId(channel);command.setPlatformTid(stockReference(order));
            List<Map<String,Object>> lines=repository.db().query("SELECT platform_oid,sku_sn,quantity FROM oms_order_line WHERE company_code=? AND order_id=? ORDER BY id",ROW,company,id);require(!lines.isEmpty(),"业务订单明细缺失，不能确认库存已释放");
            for(Map<String,Object> line:lines){OrderStockCommand.Line item=new OrderStockCommand.Line();item.setPlatformOid(text(line,"platform_oid"));item.setSkuSn(text(line,"sku_sn"));item.setQuantity(Math.toIntExact(number(line,"quantity")));command.getLines().add(item);}
            List<Map<String,Object>> released=data(inventory.execute("cancel",company,command,SecurityConstants.INNER));
            require(released!=null&&released.size()==lines.size(),"库存释放返回明细不完整");Map<String,Map<String,Object>> byOid=new HashMap<>();for(Map<String,Object> row:released)require(byOid.put(com.oms.common.util.FulfillmentLineKey.of(row),row)==null,"库存释放返回重复明细");
            for(Map<String,Object> line:lines){Map<String,Object> row=byOid.get(com.oms.common.util.FulfillmentLineKey.of(line));require(row!=null&&"CANCELLED".equals(text(row,"status"))&&number(row,"reserved_quantity")==0&&text(row,"sku_sn").equals(text(line,"sku_sn"))&&number(row,"demand_quantity")==number(line,"quantity"),"库存释放结果未确认");}
            tx().execute(transaction->{repository.fence(company,channel,lease);
                int changed=repository.db().update("UPDATE oms_order SET status='VOIDED',inventory_status='RELEASED',void_at=NOW(),void_error='',stock_attempt_status='DONE',stock_error='',control_version=control_version+1 WHERE company_code=? AND id=? AND status='VOIDING'",company,id);
                if(changed==1){new OrderShipmentService(repository).markVoided(company,id);repository.db().update("UPDATE oms_order_line SET reserved_quantity=0,inventory_reason='订单已作废，需求已取消',inventory_reason_code='ORDER_VOIDED' WHERE company_code=? AND order_id=?",company,id);repository.log(company,number(order,"platform_order_id"),"VOID_COMPLETE","SUCCESS","作废完成，库存需求已取消，预占已按原来源释放；平台交易保持原状态",text(order,"void_by"));}return null;});
        }catch(RuntimeException e){
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Order void release pending company={} order={}",company,id,e);
            String message=e instanceof IllegalArgumentException?e.getMessage():"库存释放未确认，请重试作废；如持续失败请核对库存服务日志";
            tx().execute(transaction->{repository.fence(company,channel,lease);List<Map<String,Object>> current=repository.db().query("SELECT void_error FROM oms_order WHERE company_code=? AND id=? AND status='VOIDING' FOR UPDATE",ROW,company,id);if(current.isEmpty())return null;
                repository.db().update("UPDATE oms_order SET void_error=? WHERE company_code=? AND id=?",message,company,id);
                repository.db().update("UPDATE oms_platform_order SET next_retry_at=DATE_ADD(NOW(),INTERVAL 60 SECOND) WHERE company_code=? AND id=?",company,order.get("platform_order_id"));
                if(!message.equals(text(current.get(0),"void_error")))repository.log(company,number(order,"platform_order_id"),"VOID_FAILED","FAILED",message,"订单任务");return null;});
        }
    }
}
