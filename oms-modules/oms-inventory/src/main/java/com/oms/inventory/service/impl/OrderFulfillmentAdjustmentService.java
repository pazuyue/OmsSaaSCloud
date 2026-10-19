package com.oms.inventory.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import javax.annotation.Resource;
import java.util.*;
import static com.oms.inventory.service.impl.WarehouseAllocationPlanner.*;

/** An immutable request releases only confirmed unshipped sources; the SKU mutex also fences outbound. */
@Service
public class OrderFulfillmentAdjustmentService {
    @Resource private JdbcTemplate jdbc;
    @Resource private com.oms.inventory.service.impl.rule.AllocationReservationService locks;
    private static final ObjectMapper JSON=new ObjectMapper();
    private String json(Object value){try{return JSON.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("截单快照无法序列化");}}
    @SuppressWarnings("unchecked") @Transactional(rollbackFor=Exception.class,timeout=30,isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> apply(String rawCompany,Map<String,Object> command){
        require(rawCompany!=null&&rawCompany.matches("[A-Za-z0-9_-]{1,50}"),"公司无效");String company=rawCompany.toUpperCase(Locale.ROOT),request=s(command,"request_id"),mode=s(command,"mode"),tid=s(command,"platform_tid");long channel=n(command,"channel_id");
        require(request.matches("CF[A-Za-z0-9_-]{1,62}")&&Arrays.asList("TERMINATE","REPLAN","REDUCE").contains(mode)&&channel>0&&com.oms.common.util.FulfillmentReference.valid(tid),"截单库存标识无效");
        require(command.get("allocations") instanceof List&&((List<?>)command.get("allocations")).size()<=500,"截单来源无效");
        List<Map<String,Object>> lines=(List<Map<String,Object>>)command.get("allocations");boolean finish=Boolean.TRUE.equals(command.get("finish_order"));require(!finish||"TERMINATE".equals(mode),"撤回不能终止销售需求");
        List<Map<String,Object>> demands=jdbc.queryForList("SELECT * FROM oms_order_stock_demand WHERE company_code=? AND channel_id=? AND platform_tid=? ORDER BY sku_sn,id",company,channel,tid);require(!demands.isEmpty(),"销售需求不存在");
        for(String sku:new TreeSet<String>(){{for(Map<String,Object> d:demands)add(s(d,"sku_sn"));}})require(jdbc.queryForList("SELECT id FROM oms_inventory WHERE company_code=? AND sku_sn=? FOR UPDATE",company,sku).size()==1,"公司库存缺失");
        String digest=com.oms.common.util.AllocationDigest.hash(command);
        List<Map<String,Object>> old=jdbc.queryForList("SELECT * FROM oms_order_fulfillment_adjustment WHERE company_code=? AND request_id=?",company,request);
        if(!old.isEmpty()){require(digest.equals(s(old.get(0),"payload_hash")),"同一截单库存请求内容变化");try{return JSON.readValue(s(old.get(0),"receipt_json"),Map.class);}catch(Exception e){throw new IllegalArgumentException("截单回执损坏");}}
        Set<Long> seen=new HashSet<>();long released=0;Map<Long,Long> terminated=new HashMap<>();
        for(Map<String,Object> line:lines){long id=n(line,"allocation_id"),qty=n(line,"quantity");require(id>0&&qty>0&&seen.add(id),"截单来源重复或数量无效");
            List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM oms_order_warehouse_allocation WHERE company_code=? AND channel_id=? AND platform_tid=? AND id=? FOR UPDATE",company,channel,tid,id);require(rows.size()==1,"截单来源不属于本订单");Map<String,Object> a=rows.get(0);
            require("OCCUPIED".equals(s(a,"status"))&&n(line,"outbound_before")==n(a,"outbound_quantity")&&qty==n(a,"quantity")-n(a,"released_quantity")-n(a,"outbound_quantity"),"仓库出库或占用已变化，请先核对回传");
            require(s(line,"sku_sn").equals(s(a,"sku_sn"))&&n(line,"stock_source_id")==n(a,"stock_source_id"),"截单来源身份不一致");
            if("PUBLIC_EXTRA".equals(s(a,"source_type"))){
                Map<String,Object> before=jdbc.queryForMap("SELECT * FROM wms_inventory_batch WHERE company_code=? AND id=? FOR UPDATE",company,a.get("batch_id"));
                require(jdbc.update("UPDATE wms_inventory_batch SET zp_available_number=zp_available_number+?,zp_lock_number=zp_lock_number-?,version=version+1 WHERE company_code=? AND id=? AND zp_lock_number>=?",qty,qty,company,a.get("batch_id"),qty)==1,"批次占用不足");
                require(jdbc.update("UPDATE wms_inventory SET zp_available_number=zp_available_number+?,zp_lock_number=zp_lock_number-?,version=version+1 WHERE company_code=? AND store_code=? AND sku_sn=? AND zp_lock_number>=?",qty,qty,company,a.get("store_code"),a.get("sku_sn"),qty)==1,"仓库占用不足");
                OrderWarehouseAllocationService.journal(jdbc,before,-qty,"ORDER_UNLOCK",request);
            }
            jdbc.update("UPDATE oms_order_warehouse_allocation SET released_quantity=released_quantity+?,status=IF(outbound_quantity=0, 'RELEASED','OCCUPIED') WHERE company_code=? AND id=?",qty,company,id);
            if("TERMINATE".equals(mode)){releaseSource(company,n(a,"stock_source_id"),qty);terminated.merge(n(a,"demand_id"),qty,Long::sum);}released+=qty;
        }
        if("REDUCE".equals(mode)){
            require(lines.isEmpty()&&!finish&&command.get("demands") instanceof List,"换品需求调整参数无效");
            Set<String> oids=new HashSet<>();
            for(Map<String,Object> reduction:(List<Map<String,Object>>)command.get("demands")){
                String oid=s(reduction,"platform_oid");long qty=n(reduction,"quantity");require(qty>0&&oids.add(com.oms.common.util.FulfillmentLineKey.of(oid,s(reduction,"sku_sn"))),"换品终止数量无效或重复");
                List<Map<String,Object>> matching=new ArrayList<>();for(Map<String,Object> v:demands)if(oid.equals(s(v,"platform_oid"))&&(s(reduction,"sku_sn").isEmpty()||s(reduction,"sku_sn").equals(s(v,"sku_sn"))))matching.add(v);require(matching.size()==1,"原需求不存在或含多个组件，请明确组件SKU");Map<String,Object> d=matching.get(0);
                require(qty<=n(d,"demand_quantity")-n(d,"cancelled_quantity")-n(d,"outbound_quantity"),"换品数量超过未发需求");
                require(jdbc.queryForObject("SELECT COUNT(*) FROM oms_order_warehouse_allocation WHERE company_code=? AND demand_id=? AND status='OCCUPIED' AND quantity-released_quantity-outbound_quantity>0",Long.class,company,d.get("id"))==0,"原商品仍有分仓占用，请先完成仓库撤回与核对");
                long remaining=Math.min(qty,n(d,"reserved_quantity")-n(d,"outbound_quantity"));
                for(Map<String,Object> source:jdbc.queryForList("SELECT * FROM oms_order_stock_source WHERE company_code=? AND demand_id=? AND reserved=1 ORDER BY id FOR UPDATE",company,d.get("id"))){long release=Math.min(remaining,n(source,"quantity")-n(source,"outbound_quantity"));if(release>0){releaseSource(company,n(source,"id"),release);remaining-=release;}}
                require(remaining==0,"逻辑预占来源数量不一致");terminated.put(n(d,"id"),qty);released+=qty;
            }
            require(!oids.isEmpty(),"缺少换品需求");
        }
        if(finish){
            require(jdbc.queryForObject("SELECT COUNT(*) FROM oms_order_warehouse_allocation WHERE company_code=? AND channel_id=? AND platform_tid=? AND quantity-released_quantity-outbound_quantity>0 AND status='OCCUPIED'",Long.class,company,channel,tid)==0,"还有未确认截停的仓库占用");
            for(Map<String,Object> d:demands)for(Map<String,Object> source:jdbc.queryForList("SELECT * FROM oms_order_stock_source WHERE company_code=? AND demand_id=? AND reserved=1 FOR UPDATE",company,d.get("id"))){long qty=n(source,"quantity")-n(source,"outbound_quantity");if(qty>0)releaseSource(company,n(source,"id"),qty);}
        }
        List<Map<String,Object>> result=new ArrayList<>();
        for(Map<String,Object> d:demands){Map<String,Object> now=jdbc.queryForMap("SELECT * FROM oms_order_stock_demand WHERE company_code=? AND id=? FOR UPDATE",company,d.get("id"));long cancelled=finish?n(now,"demand_quantity")-n(now,"outbound_quantity"):n(now,"cancelled_quantity")+terminated.getOrDefault(n(d,"id"),0L);
            require(cancelled>=0&&cancelled+n(now,"outbound_quantity")<=n(now,"demand_quantity"),"终止数量超过未发需求");
            jdbc.update("UPDATE oms_order_stock_demand SET cancelled_quantity=?,status=IF(?+outbound_quantity=demand_quantity,'CANCELLED',status) WHERE company_code=? AND id=?",cancelled,cancelled,company,d.get("id"));
            Map<String,Object> row=jdbc.queryForMap("SELECT platform_oid,sku_sn,reserved_quantity,outbound_quantity,cancelled_quantity FROM oms_order_stock_demand WHERE company_code=? AND id=?",company,d.get("id"));Map<String,Object> sourceTotals=jdbc.queryForMap("SELECT COALESCE(SUM(IF(source_type='LOCK',quantity,0)),0) locked_reserved_quantity,COALESCE(SUM(IF(source_type='PUBLIC_EXTRA',quantity,0)),0) public_reserved_quantity FROM oms_order_stock_source WHERE company_code=? AND demand_id=? AND reserved=1",company,d.get("id"));row.putAll(sourceTotals);result.add(row);OrderStockService.refresh(jdbc,company,s(d,"sku_sn"),channel);
            jdbc.update("INSERT INTO oms_order_stock_event(company_code,demand_id,action,quantity,message) VALUES (?,?,'FULFILLMENT_ADJUST',?,?) ON DUPLICATE KEY UPDATE quantity=quantity+VALUES(quantity),message=VALUES(message)",company,d.get("id"),terminated.getOrDefault(n(d,"id"),0L),request+"："+(finish?"终止剩余需求":"REPLAN".equals(mode)?"撤回分仓，保留逻辑预占":"终止指定未发数量"));
        }
        Map<String,Object> receipt=new LinkedHashMap<>();receipt.put("request_id",request);receipt.put("state","COMPLETED");receipt.put("mode",mode);receipt.put("quantity",released);receipt.put("lines",result);
        jdbc.update("INSERT INTO oms_order_fulfillment_adjustment(company_code,request_id,payload_hash,receipt_json) VALUES (?,?,?,?)",company,request,digest,json(receipt));return receipt;
    }
    private void releaseSource(String company,long sourceId,long qty){
        Map<String,Object> source=jdbc.queryForMap("SELECT * FROM oms_order_stock_source WHERE company_code=? AND id=? FOR UPDATE",company,sourceId);require(n(source,"reserved")==1&&n(source,"quantity")-n(source,"outbound_quantity")>=qty,"逻辑预占来源余额不足");
        Map<String,Object> demand=jdbc.queryForMap("SELECT * FROM oms_order_stock_demand WHERE company_code=? AND id=? FOR UPDATE",company,source.get("demand_id"));boolean locked="LOCK".equals(s(source,"source_type"));require(locked||"PUBLIC_EXTRA".equals(s(source,"source_type")),"预占来源类型无效");Map<String,Object> lock=null;
        if(locked){lock=jdbc.queryForMap("SELECT r.*,x.id result_id,x.release_status,h.run_action FROM rule_stock_reservation r JOIN rule_stock_result x ON x.company_code=r.company_code AND x.rule_id=r.rule_id AND x.sku_sn=r.sku_sn JOIN rule_stock_info h ON h.company_code=r.company_code AND h.id=r.rule_id WHERE r.company_code=? AND r.id=? AND r.channel_id=? AND r.sku_sn=? FOR UPDATE",company,source.get("source_id"),demand.get("channel_id"),source.get("sku_sn"));
            require(jdbc.update("UPDATE rule_stock_reservation SET occupied_quantity=occupied_quantity-? WHERE company_code=? AND id=? AND occupied_quantity>=?",qty,company,lock.get("id"),qty)==1,"渠道锁库余额不足");require(jdbc.update("UPDATE rule_stock_result SET occupied_quantity=occupied_quantity-? WHERE company_code=? AND id=? AND occupied_quantity>=?",qty,company,lock.get("result_id"),qty)==1,"锁库结果余额不足");}
        require(jdbc.update("UPDATE oms_inventory SET reserved_stock=reserved_stock-?,allocated_stock=allocated_stock+?,available_stock=available_stock+?,version=version+1 WHERE company_code=? AND sku_sn=? AND reserved_stock>=?",qty,locked?qty:0,locked?0:qty,company,source.get("sku_sn"),qty)==1,"公司预占余额不足");
        require(jdbc.update("UPDATE oms_channel_inventory SET reserved_stock=reserved_stock-?,allocated_stock=allocated_stock+?,version=version+1 WHERE company_code=? AND channel_id=? AND sku_sn=? AND reserved_stock>=?",qty,locked?qty:0,company,demand.get("channel_id"),source.get("sku_sn"),qty)==1,"渠道预占余额不足");
        jdbc.update("UPDATE oms_order_stock_source SET quantity=quantity-?,released_quantity=released_quantity+?,reserved=IF(quantity=0,0,1) WHERE company_code=? AND id=?",qty,qty,company,sourceId);
        require(jdbc.update("UPDATE oms_order_stock_demand SET reserved_quantity=reserved_quantity-? WHERE company_code=? AND id=? AND reserved_quantity-outbound_quantity>=?",qty,company,demand.get("id"),qty)==1,"需求预占余额不足");
        if(locked&&(!"NONE".equals(s(lock,"release_status"))||"RELEASE".equals(s(lock,"run_action"))))locks.release(company,n(lock,"rule_id"),jdbc.queryForMap("SELECT * FROM rule_stock_result WHERE company_code=? AND id=?",company,lock.get("result_id")),"截单返还原渠道锁库");
    }
}
