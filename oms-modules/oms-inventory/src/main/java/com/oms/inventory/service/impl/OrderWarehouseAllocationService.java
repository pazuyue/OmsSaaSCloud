package com.oms.inventory.service.impl;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import javax.annotation.Resource;
import java.util.*;
import static com.oms.inventory.service.impl.WarehouseAllocationPlanner.*;

/** Converts logical sources into traceable warehouse occupations under the shared SKU mutex. */
@Service
public class OrderWarehouseAllocationService {
    @Resource private JdbcTemplate jdbc;
    public List<Map<String,Object>> receipts(String company,long channel,String tid){require(company!=null&&company.matches("[A-Za-z0-9_-]{1,50}")&&channel>0&&tid.matches("[0-9]{1,64}"),"订单标识无效");return receipts(jdbc,company.toUpperCase(Locale.ROOT),channel,tid);}
    @Transactional(rollbackFor=Exception.class,timeout=30,isolation=Isolation.READ_COMMITTED)
    @SuppressWarnings("unchecked")
    public List<Map<String,Object>> allocate(String company,Map<String,Object> command){
        require(company!=null&&company.matches("[A-Za-z0-9_-]{1,50}"),"公司无效");company=company.toUpperCase(Locale.ROOT);
        long channel=n(command,"channel_id");String tid=s(command,"platform_tid");
        require(channel>0&&tid.matches("[0-9]{1,64}"),"订单标识无效");
        List<Map<String,Object>> expected=(List<Map<String,Object>>)command.get("lines"),stores=(List<Map<String,Object>>)command.get("stores");
        require(expected!=null&&!expected.isEmpty()&&expected.size()<=500,"完整订单明细不能为空或超过500行");
        require(stores!=null&&!stores.isEmpty()&&stores.size()<=100,"店铺允许仓未配置");
        Set<String> storeCodes=new HashSet<>(),owners=new HashSet<>();for(Map<String,Object> store:stores){
            require(!s(store,"store_code").isEmpty()&&storeCodes.add(s(store,"store_code"))&&!s(store,"real_store_code").isEmpty()&&!s(store,"owner_code").isEmpty(),"仓库映射无效或重复");owners.add(s(store,"owner_code"));}
        require(owners.size()==1,"订单分仓只允许同一货主的仓库");
        TreeSet<String> skus=new TreeSet<>();Map<String,Map<String,Object>> lines=new HashMap<>();for(Map<String,Object> line:expected){require(!s(line,"sku_sn").isEmpty()&&n(line,"quantity")>0&&lines.put(s(line,"platform_oid"),line)==null,"订单明细无效或重复");skus.add(s(line,"sku_sn"));}
        for(String sku:skus){require(jdbc.queryForList("SELECT id FROM oms_inventory WHERE company_code=? AND sku_sn=? FOR UPDATE",company,sku).size()==1,"公司SKU库存记录缺失");}
        List<Map<String,Object>> demands=jdbc.queryForList("SELECT * FROM oms_order_stock_demand WHERE company_code=? AND channel_id=? AND platform_tid=? ORDER BY sku_sn,platform_oid FOR UPDATE",company,channel,tid);
        require(demands.size()==expected.size(),"订单与库存需求明细不一致");
        require(n(command,"allow_split_sku")==0||n(command,"allow_multi")==1,"同SKU跨仓需先允许多仓发货");
        boolean partial=n(command,"allow_partial")==1;long required=0;
        List<Map<String,Object>> pending=new ArrayList<>();List<Map<String,Object>> existing=receipts(jdbc,company,channel,tid);
        for(Map<String,Object> demand:demands){Map<String,Object> line=lines.get(s(demand,"platform_oid"));
            require(line!=null&&s(line,"sku_sn").equals(s(demand,"sku_sn"))&&n(line,"quantity")==n(demand,"demand_quantity"),"订单与库存需求身份不一致");
            require(!"CANCELLED".equals(s(demand,"status")),"订单需求已作废，不能分仓");
            long reserved=n(demand,"reserved_quantity");require(reserved>=0&&reserved<=n(demand,"demand_quantity"),"逻辑预占数量异常");required+=n(demand,"demand_quantity");
            require(partial||reserved==n(demand,"demand_quantity"),"逻辑预占尚未全部完成，且店铺未允许部分发货");
            List<Map<String,Object>> sources=jdbc.queryForList("SELECT * FROM oms_order_stock_source WHERE company_code=? AND demand_id=? AND reserved=1 ORDER BY id FOR UPDATE",company,demand.get("id"));
            require(sources.stream().mapToLong(r->n(r,"quantity")).sum()==reserved,"逻辑预占来源缺失或数量不一致，请先核对来源台账");
            for(Map<String,Object> source:sources){require(s(source,"sku_sn").equals(s(demand,"sku_sn"))&&n(source,"quantity")>0,"逻辑预占来源身份或数量异常");
                if("LOCK".equals(s(source,"source_type"))){
                    require(storeCodes.contains(s(source,"store_code")),"原渠道锁库仓不在店铺允许仓内，请调整允许仓配置");
                    List<Map<String,Object>> locks=jdbc.queryForList("SELECT r.batch_id,r.occupied_quantity,b.store_code,b.zp_lock_number FROM rule_stock_reservation r JOIN wms_inventory_batch b ON b.company_code=r.company_code AND b.id=r.batch_id AND b.sku_sn=r.sku_sn WHERE r.company_code=? AND r.channel_id=? AND r.sku_sn=? AND r.id=? FOR UPDATE",company,channel,demand.get("sku_sn"),source.get("source_id"));
                    require(locks.size()==1&&s(locks.get(0),"store_code").equals(s(source,"store_code")),"原渠道锁库批次缺失或仓库不一致");
                    long claims=jdbc.queryForObject("SELECT COALESCE(SUM(quantity-outbound_quantity),0) FROM oms_order_stock_source WHERE company_code=? AND source_type='LOCK' AND source_id=? AND reserved=1",Long.class,company,source.get("source_id"));
                    require(n(locks.get(0),"occupied_quantity")>=claims&&n(locks.get(0),"zp_lock_number")>=claims,"原渠道锁库占用余额不足");source.put("batch_id",locks.get(0).get("batch_id"));
                }else require("PUBLIC_EXTRA".equals(s(source,"source_type"))&&n(source,"source_id")==0,"预占来源类型无效");
                long occupied=existing.stream().filter(r->n(r,"stock_source_id")==n(source,"id")).mapToLong(r->n(r,"quantity")).sum();
                require(occupied<=n(source,"quantity"),"仓库占用超过逻辑预占来源数量");
                long left=n(source,"quantity")-occupied;if(left>0){source.put("quantity",left);pending.add(source);}
            }
        }
        Set<Long> sourceIds=new HashSet<>();for(Map<String,Object> demand:demands)for(Map<String,Object> source:jdbc.queryForList("SELECT id FROM oms_order_stock_source WHERE company_code=? AND demand_id=? AND reserved=1",company,demand.get("id")))sourceIds.add(n(source,"id"));
        for(Map<String,Object> receipt:existing)require(sourceIds.contains(n(receipt,"stock_source_id"))&&storeCodes.contains(s(receipt,"store_code")),"原分仓结果与当前来源或允许仓配置冲突");
        List<Map<String,Object>> batches=new ArrayList<>();
        for(String sku:skus)for(Map<String,Object> store:stores){
            List<Map<String,Object>> stock=jdbc.queryForList("SELECT zp_available_number,zp_actual_number,zp_lock_number FROM wms_inventory WHERE company_code=? AND sku_sn=? AND store_code=? FOR UPDATE",company,sku,store.get("store_code"));
            List<Map<String,Object>> rows=jdbc.queryForList("SELECT id,store_code,sku_sn,zp_available_number available,zp_lock_number,zp_actual_number FROM wms_inventory_batch WHERE company_code=? AND sku_sn=? AND store_code=? ORDER BY id FOR UPDATE",company,sku,store.get("store_code"));
            for(Map<String,Object> batch:rows)require(n(batch,"available")>=0&&n(batch,"zp_lock_number")>=0&&n(batch,"zp_actual_number")==n(batch,"available")+n(batch,"zp_lock_number"),"批次实物、可用与占用库存不一致");
            long batchLocked=rows.stream().mapToLong(r->n(r,"zp_lock_number")).sum();require(batchLocked==(stock.isEmpty()?0:n(stock.get(0),"zp_lock_number")),"仓库汇总与批次占用库存不一致");
            long sum=rows.stream().mapToLong(r->n(r,"available")).sum();require(stock.isEmpty()||n(stock.get(0),"zp_actual_number")==sum+batchLocked,"仓库实物、可用与占用库存不一致");require(sum==(stock.isEmpty()?0:n(stock.get(0),"zp_available_number")),"仓库汇总与批次可用库存不一致，请先核对库存台账");batches.addAll(rows);
        }
        List<Map<String,Object>> plan=WarehouseAllocationPlanner.plan(pending,batches,stores,existing,required,n(command,"allow_multi")==1,partial,n(command,"allow_split_sku")==1);
        for(Map<String,Object> receipt:plan){long qty=n(receipt,"quantity");
            if("PUBLIC_EXTRA".equals(s(receipt,"source_type"))){
                Map<String,Object> before=jdbc.queryForMap("SELECT * FROM wms_inventory_batch WHERE company_code=? AND id=?",company,receipt.get("batch_id"));
                require(jdbc.update("UPDATE wms_inventory_batch SET zp_available_number=zp_available_number-?,zp_lock_number=zp_lock_number+?,version=version+1 WHERE company_code=? AND id=? AND zp_available_number>=?",qty,qty,company,receipt.get("batch_id"),qty)==1,"批次库存不足，分仓已回滚");
                require(jdbc.update("UPDATE wms_inventory SET zp_available_number=zp_available_number-?,zp_lock_number=zp_lock_number+?,version=version+1 WHERE company_code=? AND store_code=? AND sku_sn=? AND zp_available_number>=?",qty,qty,company,receipt.get("store_code"),receipt.get("sku_sn"),qty)==1,"仓库库存不足，分仓已回滚");
                journal(jdbc,before,qty,"ORDER_LOCK",channel+"/"+tid);
            }
            jdbc.update("INSERT INTO oms_order_warehouse_allocation(company_code,channel_id,platform_tid,demand_id,stock_source_id,sku_sn,source_type,batch_id,store_code,owner_code,real_store_code,real_store_name,quantity) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",company,channel,tid,receipt.get("demand_id"),receipt.get("stock_source_id"),receipt.get("sku_sn"),receipt.get("source_type"),receipt.get("batch_id"),receipt.get("store_code"),receipt.get("owner_code"),receipt.get("real_store_code"),receipt.get("real_store_name"),qty);
            jdbc.update("INSERT INTO oms_order_stock_event(company_code,demand_id,action,quantity,message) VALUES (?,?,'WAREHOUSE_ALLOCATE',?,?) ON DUPLICATE KEY UPDATE quantity=quantity+VALUES(quantity),message=VALUES(message)",company,receipt.get("demand_id"),qty,"分仓至 "+s(receipt,"real_store_code")+" / "+s(receipt,"store_code")+"，"+("LOCK".equals(s(receipt,"source_type"))?"继承渠道锁库":"公共库存转仓库占用"));
        }
        for(String sku:skus)OrderStockService.refresh(jdbc,company,sku,channel);
        return receipts(jdbc,company,channel,tid);
    }
    public static List<Map<String,Object>> receipts(JdbcTemplate jdbc,String company,long channel,String tid){return jdbc.queryForList("SELECT a.*,d.platform_oid,b.batch_code FROM oms_order_warehouse_allocation a JOIN oms_order_stock_demand d ON d.company_code=a.company_code AND d.id=a.demand_id JOIN wms_inventory_batch b ON b.company_code=a.company_code AND b.id=a.batch_id AND b.sku_sn=a.sku_sn AND b.store_code=a.store_code WHERE a.company_code=? AND a.channel_id=? AND a.platform_tid=? AND a.status='OCCUPIED' ORDER BY a.id",company,channel,tid);}
    /** Called inside logical cancellation's transaction and already-held SKU mutexes. */
    public static void release(JdbcTemplate jdbc,List<Map<String,Object>> demands){
        require(demands.stream().allMatch(d->n(d,"outbound_quantity")==0),"已有出库，不能释放整单仓库占用");
        for(Map<String,Object> demand:demands){String company=s(demand,"company_code");for(Map<String,Object> receipt:jdbc.queryForList("SELECT * FROM oms_order_warehouse_allocation WHERE company_code=? AND demand_id=? AND status='OCCUPIED' ORDER BY id FOR UPDATE",company,demand.get("id"))){long qty=n(receipt,"quantity");
            if("PUBLIC_EXTRA".equals(s(receipt,"source_type"))){
                Map<String,Object> before=jdbc.queryForMap("SELECT * FROM wms_inventory_batch WHERE company_code=? AND id=?",company,receipt.get("batch_id"));
                require(jdbc.update("UPDATE wms_inventory_batch SET zp_available_number=zp_available_number+?,zp_lock_number=zp_lock_number-?,version=version+1 WHERE company_code=? AND id=? AND zp_lock_number>=?",qty,qty,company,receipt.get("batch_id"),qty)==1,"原分仓批次占用余额不足，释放已回滚");
                require(jdbc.update("UPDATE wms_inventory SET zp_available_number=zp_available_number+?,zp_lock_number=zp_lock_number-?,version=version+1 WHERE company_code=? AND store_code=? AND sku_sn=? AND zp_lock_number>=?",qty,qty,company,receipt.get("store_code"),receipt.get("sku_sn"),qty)==1,"原分仓仓库占用余额不足，释放已回滚");
                journal(jdbc,before,-qty,"ORDER_UNLOCK",receipt.get("channel_id")+"/"+receipt.get("platform_tid"));
            }
            jdbc.update("UPDATE oms_order_warehouse_allocation SET status='RELEASED' WHERE id=?",receipt.get("id"));
        }}
    }
    private static void journal(JdbcTemplate jdbc,Map<String,Object> before,long delta,String operation,String relation){
        Map<String,Object> after=jdbc.queryForMap("SELECT * FROM wms_inventory_batch WHERE id=?",before.get("id"));
        List<Object> args=new ArrayList<>(Arrays.asList(before.get("company_code"),before.get("store_code"),before.get("sku_sn"),before.get("id"),before.get("batch_code"),"ZP",operation,"OA:"+UUID.randomUUID().toString().replace("-",""),relation,delta,"OMS订单分仓占用或作废释放",0,"OMS订单"));
        StringBuilder columns=new StringBuilder("company_code,store_code,sku_sn,batch_id,batch_code,inventory_type,operation_type,request_id,relation_sn,change_quantity,change_reason,operator_id,operator_name");
        for(String key:Arrays.asList("zp_actual_number","cp_actual_number","zp_available_number","cp_available_number","zp_lock_number","cp_lock_number")){columns.append(",old_").append(key).append(",new_").append(key);args.add(before.get(key));args.add(after.get(key));}
        jdbc.update("INSERT INTO wms_inventory_change_history("+columns+") VALUES ("+String.join(",",Collections.nCopies(args.size(),"?"))+")",args.toArray());
    }
}
