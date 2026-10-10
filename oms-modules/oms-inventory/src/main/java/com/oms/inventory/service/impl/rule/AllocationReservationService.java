package com.oms.inventory.service.impl.rule;

import com.oms.inventory.model.dto.ReservationCommand;
import com.oms.inventory.service.impl.InventoryMutationService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import javax.annotation.Resource;
import java.util.*;
import static com.oms.inventory.service.impl.InventoryQueryService.*;

/** Header -> result -> OMS SKU mutex -> physical stock -> sources/channels/events.
 * No remote calls, cross-SKU transaction, or automatic retry. */
@Service
public class AllocationReservationService {
    @Resource private JdbcTemplate jdbc;
    @Resource private InventoryMutationService inventory;
    private static void require(boolean valid,String message){if(!valid)throw new IllegalArgumentException(message);}
    private static long free(Map<String,Object> row){return number(row,"originalQuantity")-number(row,"occupiedQuantity")-number(row,"consumedQuantity")-number(row,"releasedQuantity");}

    @Transactional(propagation=Propagation.MANDATORY)
    public void record(String company,long rule,String sku,List<Map<String,Object>> targets,long quantity) {
        List<Map<String,Object>> batches=jdbc.query("SELECT batch_id,change_quantity FROM wms_inventory_change_history WHERE company_code=? AND sku_sn=? AND request_id=? AND operation_type='LOCK' ORDER BY store_code,batch_id",ROW,company,sku,"RULE-"+rule);
        require(batches.stream().mapToLong(b->number(b,"changeQuantity")).sum()==quantity,"原始锁库流水与渠道分配不一致");
        List<Object[]> inserts=new ArrayList<>();int cursor=0;long left=batches.isEmpty()?0:number(batches.get(0),"changeQuantity");
        for(Map<String,Object> target:targets) {
            long remaining=number(target,"target");
            while(remaining>0) {
                require(cursor<batches.size(),"锁库来源数量不足");long take=Math.min(left,remaining);
                inserts.add(new Object[]{company,rule,sku,target.get("channelId"),batches.get(cursor).get("batchId"),take});
                remaining-=take;left-=take;
                if(left==0 && ++cursor<batches.size())left=number(batches.get(cursor),"changeQuantity");
            }
        }
        if(!inserts.isEmpty())jdbc.batchUpdate("INSERT INTO rule_stock_reservation(company_code,rule_id,sku_sn,channel_id,batch_id,original_quantity) VALUES (?,?,?,?,?,?)",inserts);

    }

    private List<Map<String,Object>> sources(String company,long rule,String sku) {
        return jdbc.query("SELECT * FROM rule_stock_reservation WHERE company_code=? AND rule_id=? AND sku_sn=? ORDER BY channel_id,batch_id FOR UPDATE",ROW,company,rule,sku);
    }
    private void tracked(Map<String,Object> result) {

        require("SUCCESS".equals(result.get("status")),"分货尚未成功，不能使用锁库库存");
    }
    private void checkSources(List<Map<String,Object>> rows,Map<String,Object> result) {
        long original=0,occupied=0,consumed=0,released=0;
        for(Map<String,Object> row:rows) {
            require(free(row)>=0 && number(row,"occupiedQuantity")>=0 && number(row,"consumedQuantity")>=0 && number(row,"releasedQuantity")>=0,"来源余额异常，操作已取消");
            original+=number(row,"originalQuantity");occupied+=number(row,"occupiedQuantity");consumed+=number(row,"consumedQuantity");released+=number(row,"releasedQuantity");
        }
        require(original==number(result,"allocatedQuantity") && occupied==number(result,"occupiedQuantity") && consumed==number(result,"consumedQuantity") && released==number(result,"releasedQuantity"),"锁库明细与本单余额不一致，请核对");
    }
    /** Before source counters change, verify aggregate covers all tracked rules on each affected channel. */
    private void settleChannels(String company,String sku,Map<Long,Long> amounts) {
        for(Map.Entry<Long,Long> entry:new TreeMap<>(amounts).entrySet()) {
            long owed=jdbc.queryForObject("SELECT COALESCE(SUM(original_quantity-consumed_quantity-released_quantity),0) FROM rule_stock_reservation WHERE company_code=? AND sku_sn=? AND channel_id=?",Long.class,company,sku,entry.getKey());
            int changed=jdbc.update("UPDATE oms_channel_inventory SET allocated_stock=allocated_stock-?,version=version+1 WHERE company_code=? AND sku_sn=? AND channel_id=? AND allocated_stock>=?",entry.getValue(),company,sku,entry.getKey(),Math.max(owed,entry.getValue()));
            require(changed==1,"渠道锁库与来源余额不一致，本 SKU 已回滚，请核对");
        }
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public void release(String company,long rule,Map<String,Object> result,String actor) {
        tracked(result);String sku=(String)result.get("skuSn");
        inventory.allocationAvailable(company,sku,Collections.emptyList());
        List<Map<String,Object>> rows=sources(company,rule,sku);checkSources(rows,result);
        Map<Long,Long> batches=new TreeMap<>(),channels=new TreeMap<>();long total=0;
        for(Map<String,Object> row:rows) {long take=free(row);if(take==0)continue;total+=take;batches.merge(number(row,"batchId"),take,Long::sum);channels.merge(number(row,"channelId"),take,Long::sum);}
        if(total>0) {
            inventory.settleReservation(company,sku,batches,"REL-"+UUID.randomUUID(),"RULE-"+rule,false);
            settleChannels(company,sku,channels);
            jdbc.update("UPDATE rule_stock_reservation SET released_quantity=original_quantity-consumed_quantity-occupied_quantity WHERE company_code=? AND rule_id=? AND sku_sn=?",company,rule,sku);
        }
        jdbc.update("UPDATE rule_stock_result SET released_quantity=released_quantity+?,release_status=?,release_error='',release_operator=? WHERE id=?",total,number(result,"occupiedQuantity")>0?"PARTIAL":"RELEASED",actor,result.get("id"));
    }

    @Transactional(rollbackFor=Exception.class,timeout=15,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void order(String company,long rule,String action,ReservationCommand command,String actor) {
        require(command!=null,"缺少订单库存参数");
        require(Arrays.asList("OCCUPY","CANCEL","CONSUME").contains(action),"订单库存动作无效");
        String sku=command.getSkuSn(),line=command.getOrderLine(),request=command.getRequestId();
        require(sku!=null && !sku.trim().isEmpty() && sku.length()<=50 && sku.equals(sku.trim()),"SKU 无效");
        require(line!=null && !line.trim().isEmpty() && line.length()<=100 && line.equals(line.trim()),"请提供唯一订单行编号");
        require(request!=null && request.matches("[a-zA-Z0-9_-]{16,64}"),"请求编号须为 16 至 64 位字母、数字、下划线或短横线");
        require(command.getQuantity()!=null && command.getQuantity()>0 && command.getQuantity()<=100000000 && command.getChannelId()!=null && command.getChannelId()>0,"渠道和数量无效");
        List<Map<String,Object>> heads=jdbc.query("SELECT * FROM rule_stock_info WHERE company_code=? AND id=? FOR UPDATE",ROW,company,rule);
        require(!heads.isEmpty() && number(heads.get(0),"allocationType")==2,"锁库单不存在或无权访问");
        List<Map<String,Object>> results=jdbc.query("SELECT * FROM rule_stock_result WHERE company_code=? AND rule_id=? AND sku_sn=? FOR UPDATE",ROW,company,rule,sku);
        require(!results.isEmpty(),"本单没有该 SKU 的锁库记录");Map<String,Object> result=results.get(0);tracked(result);
        inventory.allocationAvailable(company,sku,Collections.emptyList());
        List<Map<String,Object>> events=jdbc.query("SELECT * FROM rule_stock_reservation_event WHERE company_code=? AND rule_id=? AND sku_sn=? AND request_id=?",ROW,company,rule,sku,request);
        long quantity=command.getQuantity(),channel=command.getChannelId();
        if(!events.isEmpty()) {
            Map<String,Object> old=events.get(0);
            require(action.equals(old.get("action")) && line.equals(old.get("orderLine")) && quantity==number(old,"quantity") && channel==number(old,"channelId"),"重复请求的库存操作内容不一致");return;
        }
        List<Map<String,Object>> rows=sources(company,rule,sku);checkSources(rows,result);
        Map<Long,Map<String,Object>> orders=new HashMap<>();
        for(Map<String,Object> row:jdbc.query("SELECT * FROM rule_stock_order_reservation WHERE company_code=? AND rule_id=? AND sku_sn=? AND order_line=? ORDER BY source_id FOR UPDATE",ROW,company,rule,sku,line))orders.put(number(row,"sourceId"),row);
        if(action.equals("OCCUPY")) {
            require("NONE".equals(result.get("releaseStatus")) && !"RELEASE".equals(heads.get(0).get("runAction")),"本单已开始释放，不再接受新的订单占用");
            require(orders.isEmpty(),"该订单行已占用过本单库存，请使用原请求编号重试");
        }
        Map<Long,Long> takes=new LinkedHashMap<>(),batches=new TreeMap<>();long remaining=quantity;
        for(Map<String,Object> row:rows) {
            if(number(row,"channelId")!=channel)continue;
            Map<String,Object> order=orders.get(number(row,"id"));
            long available=action.equals("OCCUPY")?free(row):(order==null?0:number(order,"occupiedQuantity"));
            long take=Math.min(remaining,available);if(take==0)continue;
            require(action.equals("OCCUPY") || number(row,"occupiedQuantity")>=take,"订单占用与来源余额不一致");
            takes.put(number(row,"id"),take);batches.merge(number(row,"batchId"),take,Long::sum);remaining-=take;if(remaining==0)break;
        }
        require(remaining==0,action.equals("OCCUPY")?"本单本渠道可占用余额不足":"该订单行剩余占用数量不足");
        String journalRequest=action.equals("CONSUME")?"OUT-"+UUID.randomUUID():"";
        if(action.equals("CONSUME")) {
            // Use a globally unique physical journal request; public callback idempotency lives in the event table.
            inventory.settleReservation(company,sku,batches,journalRequest,"RULE-"+rule,true);
            settleChannels(company,sku,Collections.singletonMap(channel,quantity));
        }
        for(Map.Entry<Long,Long> take:takes.entrySet()) {
            long source=take.getKey(),amount=take.getValue();
            if(action.equals("OCCUPY")) {
                jdbc.update("INSERT INTO rule_stock_order_reservation(company_code,rule_id,sku_sn,order_line,source_id,original_quantity,occupied_quantity) VALUES (?,?,?,?,?,?,?)",company,rule,sku,line,source,amount,amount);
                jdbc.update("UPDATE rule_stock_reservation SET occupied_quantity=occupied_quantity+? WHERE id=?",amount,source);
            } else {
                String orderField=action.equals("CONSUME")?"consumed_quantity":"cancelled_quantity";
                jdbc.update("UPDATE rule_stock_order_reservation SET occupied_quantity=occupied_quantity-?,"+orderField+"="+orderField+"+? WHERE id=?",amount,amount,orders.get(source).get("id"));
                jdbc.update("UPDATE rule_stock_reservation SET occupied_quantity=occupied_quantity-?,consumed_quantity=consumed_quantity+? WHERE id=?",amount,action.equals("CONSUME")?amount:0,source);
            }
        }
        jdbc.update("UPDATE rule_stock_result SET occupied_quantity=occupied_quantity+?,consumed_quantity=consumed_quantity+? WHERE id=?",action.equals("OCCUPY")?quantity:-quantity,action.equals("CONSUME")?quantity:0,result.get("id"));
        jdbc.update("INSERT INTO rule_stock_reservation_event(company_code,rule_id,sku_sn,request_id,action,order_line,channel_id,quantity,operator_name,journal_request) VALUES (?,?,?,?,?,?,?,?,?,?)",company,rule,sku,request,action,line,channel,quantity,actor,journalRequest);
        if("PARTIAL".equals(result.get("releaseStatus"))) {
            jdbc.update("UPDATE rule_stock_result SET release_status='RELEASED' WHERE id=? AND occupied_quantity=0 AND allocated_quantity=consumed_quantity+released_quantity",result.get("id"));
            if(number(heads.get(0),"status")==11 && jdbc.queryForObject("SELECT COUNT(*) FROM rule_stock_result WHERE rule_id=? AND status='SUCCESS' AND release_status<>'RELEASED'",Long.class,rule)==0)
                jdbc.update("UPDATE rule_stock_info SET status=10,revision=revision+1 WHERE id=?",rule);
        }
    }
}
