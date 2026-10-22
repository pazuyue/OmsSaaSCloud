package com.oms.inventory.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oms.common.model.dto.OrderStockCommand;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import javax.annotation.Resource;
import java.util.*;

/** Company/SKU logical reservations. Demand identity and row locks make retries idempotent. */
@Service
public class OrderStockService {
    @Resource private JdbcTemplate jdbc;
    @Resource private com.oms.inventory.service.impl.rule.AllocationReservationService allocations;
    private static final ObjectMapper JSON=new ObjectMapper();
    private static void require(boolean ok,String message){if(!ok)throw new IllegalArgumentException(message);}
    private static long n(Map<String,Object> row,String field){Object value=row.get(field);return value==null?0:new java.math.BigDecimal(value.toString()).longValueExact();}
    private static String s(Map<String,Object> row,String field){Object value=row.get(field);return value==null?"":value.toString();}
    private static String marks(int size){return String.join(",",Collections.nCopies(size,"?"));}
    private long scalar(String sql,Object...args){Long result=jdbc.queryForObject(sql,Long.class,args);return result==null?0:result;}

    /** Pure lookup: a missing demand must not create inventory rows or refresh balances. */
    public List<Map<String,Object>> demands(String company,long channel,String tid){
        require(company!=null&&company.matches("[A-Za-z0-9_-]{1,50}")&&channel>0&&tid!=null&&com.oms.common.util.FulfillmentReference.valid(tid),"订单标识无效");
        String scope=company.toUpperCase(Locale.ROOT);
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT id,platform_oid,sku_sn,demand_quantity,reserved_quantity,outbound_quantity,cancelled_quantity,status,reason,reason_code,decision_snapshot FROM oms_order_stock_demand WHERE company_code=? AND channel_id=? AND platform_tid=?",scope,channel,tid);
        Map<Long,List<Map<String,Object>>> sources=new HashMap<>();
        for(Map<String,Object> source:jdbc.queryForList("SELECT s.demand_id,s.source_type,s.store_code,s.quantity,s.outbound_quantity FROM oms_order_stock_source s JOIN oms_order_stock_demand d ON d.id=s.demand_id AND d.company_code=s.company_code WHERE d.company_code=? AND d.channel_id=? AND d.platform_tid=? AND s.reserved=1 ORDER BY s.id",scope,channel,tid))sources.computeIfAbsent(n(source,"demand_id"),k->new ArrayList<>()).add(source);
        for(Map<String,Object> row:rows){row.put("sources",sources.getOrDefault(n(row,"id"),Collections.emptyList()));try{row.put("decision_snapshot",row.get("decision_snapshot")==null?Collections.emptyMap():JSON.readValue(s(row,"decision_snapshot"),Map.class));}catch(Exception e){row.put("snapshot_error","预占快照无法解析");row.put("decision_snapshot",Collections.emptyMap());}}return rows;
    }

    @Transactional(rollbackFor=Exception.class,timeout=20,isolation=Isolation.READ_COMMITTED)
    public List<Map<String,Object>> execute(String company,String action,OrderStockCommand command){
        require(company!=null&&company.matches("[A-Za-z0-9_-]{1,50}"),"公司无效");company=company.toUpperCase(Locale.ROOT);
        require(Arrays.asList("register","reserve","query","cancel").contains(action),"库存动作无效");
        require(command!=null&&command.getChannelId()>0&&command.getPlatformTid()!=null&&com.oms.common.util.FulfillmentReference.valid(command.getPlatformTid()),"订单标识无效");
        require(command.getLines()!=null&&command.getLines().size()<=500,"订单明细超过500行");
        
        Set<String> seen=new HashSet<>();List<OrderStockCommand.Line> lines=new ArrayList<>(command.getLines());
        for(OrderStockCommand.Line line:lines){require(line.getPlatformOid()!=null&&(line.isGift()?line.getPlatformOid().isEmpty():line.getPlatformOid().matches("[0-9]{1,64}"))&&seen.add(com.oms.common.util.FulfillmentLineKey.of(line.getPlatformOid(),line.getSkuSn())),"子订单号无效或重复");require(line.getSkuSn()!=null&&!line.getSkuSn().trim().isEmpty()&&line.getSkuSn().length()<=128&&line.getSkuSn().equals(line.getSkuSn().trim()),"SKU无效");require(line.getQuantity()>0&&line.getQuantity()<=100000000,"需求数量无效");require(line.getComponentQuantity()>=0&&line.getComponentQuantity()<=100000&&(line.getComponentQuantity()==0||line.getQuantity()%line.getComponentQuantity()==0),"套装组件配比无效");}
        lines.sort(Comparator.comparing(OrderStockCommand.Line::getSkuSn).thenComparing(OrderStockCommand.Line::getPlatformOid));
        // All writers (allocation, receiving, adjustment, demand) share the company/SKU mutex.
        Set<String> mutexSkus=new TreeSet<>();for(OrderStockCommand.Line line:lines)mutexSkus.add(line.getSkuSn());if("cancel".equals(action))mutexSkus.addAll(jdbc.queryForList("SELECT DISTINCT bag_sku FROM oms_lucky_claim WHERE company_code=? AND channel_id=? AND platform_tid=? AND state='WAITING'",String.class,company,command.getChannelId(),command.getPlatformTid()));for(String sku:mutexSkus){
            jdbc.update("INSERT INTO oms_inventory(company_code,sku_sn,total_stock,available_stock,version) VALUES (?,?,0,0,1) ON DUPLICATE KEY UPDATE id=id",company,sku);
            jdbc.queryForList("SELECT id FROM oms_inventory WHERE company_code=? AND sku_sn=? FOR UPDATE",company,sku);
            jdbc.update("INSERT INTO oms_channel_inventory(company_code,channel_id,sku_sn) VALUES (?,?,?) ON DUPLICATE KEY UPDATE id=id",company,command.getChannelId(),sku);
        }
        List<Map<String,Object>> result=new ArrayList<>();
        Map<String,List<Map<String,Object>>> pending=new LinkedHashMap<>();
        for(OrderStockCommand.Line line:lines){
            List<Map<String,Object>> found=jdbc.queryForList("SELECT * FROM oms_order_stock_demand WHERE company_code=? AND channel_id=? AND platform_tid=? AND platform_oid=? AND sku_sn=? FOR UPDATE",company,command.getChannelId(),command.getPlatformTid(),line.getPlatformOid(),line.getSkuSn());
            if(found.isEmpty()){
                require("register".equals(action),"尚未登记订单需求");
                jdbc.update("INSERT INTO oms_order_stock_demand(company_code,channel_id,platform_tid,platform_oid,sku_sn,demand_quantity,component_quantity,line_kind) VALUES (?,?,?,?,?,?,?,?)",company,command.getChannelId(),command.getPlatformTid(),line.getPlatformOid(),line.getSkuSn(),line.getQuantity(),line.getComponentQuantity(),line.isGift()?"GIFT":"SALE");
                found=jdbc.queryForList("SELECT * FROM oms_order_stock_demand WHERE company_code=? AND channel_id=? AND platform_tid=? AND platform_oid=? AND sku_sn=?",company,command.getChannelId(),command.getPlatformTid(),line.getPlatformOid(),line.getSkuSn());
                Map<String,Object> demand=found.get(0);event(demand,"REGISTER","登记销售需求，等待转单逻辑预占");
            }
            Map<String,Object> demand=found.get(0);
            require((line.isGift()?"GIFT":"SALE").equals(s(demand,"line_kind")),"需求类型与原库存身份不一致");
            // A canceled demand is immutable. A new replacement round uses a new reference.
            require("cancel".equals(action)||"query".equals(action)||!"CANCELLED".equals(s(demand,"status"))||line.isGift()&&"reserve".equals(action)&&n(demand,"cancelled_quantity")==n(demand,"demand_quantity"),"订单需求已作废，不允许重新登记或预占");
            if("register".equals(action)||"reserve".equals(action))require(n(demand,"component_quantity")==line.getComponentQuantity(),"套装组件配比与原需求不同");
            require(s(demand,"sku_sn").equals(line.getSkuSn())&&n(demand,"demand_quantity")==line.getQuantity(),"已登记需求与本次参数不同，订单更新不属于一期，请核对原单");
            if(("register".equals(action)||"reserve".equals(action))&&n(demand,"demand_quantity")-n(demand,"cancelled_quantity")>n(demand,"reserved_quantity")){
                jdbc.update("UPDATE oms_order_stock_demand SET allow_public_stock=? WHERE id=?",command.isAllowPublicStock()?1:0,demand.get("id"));
                if("reserve".equals(action))pending.computeIfAbsent(line.getSkuSn(),k->new ArrayList<>()).add(demand);
            }
            result.add(demand);
        }
        if("cancel".equals(action)){require((!lines.isEmpty()||scalar("SELECT COUNT(*) FROM oms_lucky_claim WHERE company_code=? AND channel_id=? AND platform_tid=?",company,command.getChannelId(),command.getPlatformTid())>0)&&scalar("SELECT COUNT(*) FROM oms_order_stock_demand WHERE company_code=? AND channel_id=? AND platform_tid=?",company,command.getChannelId(),command.getPlatformTid())==lines.size(),"作废必须包含完整订单需求");cancel(result);LuckyBagStock.cancelWaiting(jdbc,company,command.getChannelId(),command.getPlatformTid());}
        if("reserve".equals(action)){
            require(scalar("SELECT COUNT(*) FROM oms_order_stock_demand WHERE company_code=? AND channel_id=? AND platform_tid=?",company,command.getChannelId(),command.getPlatformTid())==lines.size(),"预占必须包含完整订单需求");
            boolean wholeGift=!lines.isEmpty()&&command.getPlatformTid().startsWith("GIFT:")&&lines.stream().allMatch(line->line.isGift()&&line.getComponentQuantity()>0);
            org.springframework.transaction.TransactionStatus status=wholeGift?org.springframework.transaction.interceptor.TransactionAspectSupport.currentTransactionStatus():null;Object savepoint=wholeGift?status.createSavepoint():null;
            try{for(List<Map<String,Object>> group:pending.values())reserve(group,command.isAllowPublicStock());
                if(wholeGift&&scalar("SELECT COUNT(*) FROM oms_order_stock_demand WHERE company_code=? AND channel_id=? AND platform_tid=? AND demand_quantity-cancelled_quantity>reserved_quantity",company,command.getChannelId(),command.getPlatformTid())>0){status.rollbackToSavepoint(savepoint);waitForStock(result,"GIFT_SET_SHORTAGE","套装赠品组成库存不足，保留完整赠送需求，未新增占用任何子件");}
            }finally{if(savepoint!=null)status.releaseSavepoint(savepoint);}
        }
        Set<String> refreshed=new HashSet<>();
        for(int i=0;i<result.size();i++){
            Map<String,Object> latest=jdbc.queryForMap("SELECT * FROM oms_order_stock_demand WHERE id=?",result.get(i).get("id"));
            if(refreshed.add(s(latest,"sku_sn")))refresh(jdbc,company,s(latest,"sku_sn"),command.getChannelId());
            latest.put("pending_quantity","CANCELLED".equals(s(latest,"status"))?0:n(latest,"demand_quantity")-n(latest,"cancelled_quantity")-n(latest,"reserved_quantity"));result.set(i,latest);
        }
        return result;
    }

    /** Terminal cancellation, serialized with reserve by the same company/SKU mutex. */
    private void cancel(List<Map<String,Object>> demands){
        require(demands.stream().allMatch(d->n(d,"outbound_quantity")==0),"订单已有出库，不能取消销售需求或释放已消耗库存");
        OrderWarehouseAllocationService.release(jdbc,demands);
        Map<String,Map<String,Object>> releasing=new LinkedHashMap<>();
        for(Map<String,Object> demand:demands){
            if("CANCELLED".equals(s(demand,"status")))continue;
            String company=s(demand,"company_code"),sku=s(demand,"sku_sn");long reserved=n(demand,"reserved_quantity"),locked=0,publicStock=0,lucky=0;
            List<Map<String,Object>> sources=jdbc.queryForList("SELECT * FROM oms_order_stock_source WHERE company_code=? AND demand_id=? AND reserved=1 ORDER BY id FOR UPDATE",company,demand.get("id"));
            for(Map<String,Object> source:sources){long quantity=n(source,"quantity");require(quantity>0,"预占来源数量异常");
                if("PUBLIC_EXTRA".equals(s(source,"source_type"))){publicStock+=quantity;continue;}
                if("LUCKY".equals(s(source,"source_type"))){LuckyBagStock.releaseSource(jdbc,company,source,quantity);lucky+=quantity;continue;}
                require("LOCK".equals(s(source,"source_type")),"预占来源类型无效");
                List<Map<String,Object>> found=jdbc.queryForList("SELECT s.*,r.id result_id,r.release_status,h.run_action FROM rule_stock_reservation s JOIN rule_stock_result r ON r.company_code=s.company_code AND r.rule_id=s.rule_id AND r.sku_sn=s.sku_sn JOIN rule_stock_info h ON h.id=s.rule_id AND h.company_code=s.company_code WHERE s.company_code=? AND s.sku_sn=? AND s.channel_id=? AND s.id=? FOR UPDATE",company,sku,demand.get("channel_id"),source.get("source_id"));
                require(found.size()==1,"原锁库来源不存在或归属不一致");Map<String,Object> lock=found.get(0);
                require(jdbc.update("UPDATE rule_stock_reservation SET occupied_quantity=occupied_quantity-? WHERE id=? AND occupied_quantity>=?",quantity,lock.get("id"),quantity)==1,"原锁库占用余额不足");
                require(jdbc.update("UPDATE rule_stock_result SET occupied_quantity=occupied_quantity-? WHERE id=? AND occupied_quantity>=?",quantity,lock.get("result_id"),quantity)==1,"锁库单占用余额不足");
                if(!"NONE".equals(s(lock,"release_status"))||"RELEASE".equals(s(lock,"run_action")))releasing.put(company+"/"+lock.get("result_id"),lock);
                locked+=quantity;
            }
            require(locked+publicStock+lucky==reserved,"订单预占与来源数量不一致，已回滚");
            if(reserved>0){
                require(jdbc.update("UPDATE oms_inventory SET reserved_stock=reserved_stock-?,allocated_stock=allocated_stock+?,available_stock=available_stock+?,version=version+1 WHERE company_code=? AND sku_sn=? AND reserved_stock>=?",reserved,locked+lucky,publicStock,company,sku,reserved)==1,"公司预占余额不足，已回滚");
                require(jdbc.update("UPDATE oms_channel_inventory SET reserved_stock=reserved_stock-?,allocated_stock=allocated_stock+?,version=version+1 WHERE company_code=? AND channel_id=? AND sku_sn=? AND reserved_stock>=?",reserved,locked,company,demand.get("channel_id"),sku,reserved)==1,"渠道预占余额不足，已回滚");
            }
            jdbc.update("UPDATE oms_order_stock_source SET reserved=0 WHERE company_code=? AND demand_id=? AND reserved=1",company,demand.get("id"));
            jdbc.update("UPDATE oms_order_stock_demand SET reserved_quantity=0,cancelled_quantity=demand_quantity,status='CANCELLED',reason='业务订单已作废',reason_code='ORDER_VOIDED' WHERE id=?",demand.get("id"));
            event(demand,"CANCEL","取消销售需求；释放渠道锁库预占 "+locked+" 件，公共预占 "+publicStock+" 件");
        }
        if(!demands.isEmpty()){Map<String,Object> first=demands.get(0);LuckyBagStock.reconcile(jdbc,s(first,"company_code"),n(first,"channel_id"),s(first,"platform_tid"));}
        // A rule already being released must not retain newly returned channel locks.
        for(Map<String,Object> lock:releasing.values()){
            String company=s(lock,"company_code");long rule=n(lock,"rule_id");
            Map<String,Object> result=jdbc.queryForObject("SELECT * FROM rule_stock_result WHERE id=?",InventoryQueryService.ROW,lock.get("result_id"));
            allocations.release(company,rule,result,"订单作废");
            jdbc.update("UPDATE rule_stock_info h SET status=10,revision=revision+1 WHERE h.company_code=? AND h.id=? AND h.status=11 AND NOT EXISTS(SELECT 1 FROM rule_stock_result r WHERE r.company_code=h.company_code AND r.rule_id=h.id AND r.status='SUCCESS' AND r.release_status<>'RELEASED')",company,rule);
        }
    }

    private void event(Map<String,Object> demand,String action,String message){event(demand,action,message,n(demand,"demand_quantity"));}
    private void event(Map<String,Object> demand,String action,String message,long quantity){jdbc.update("INSERT INTO oms_order_stock_event(company_code,demand_id,action,quantity,message) VALUES (?,?,?,?,?) ON DUPLICATE KEY UPDATE quantity=quantity+VALUES(quantity),message=VALUES(message)",demand.get("company_code"),demand.get("id"),action,quantity,message);}
    /** All-or-none per order/SKU; keep platform-line demand and source traceability. */
    private void reserve(List<Map<String,Object>> demands,boolean allowPublic){
        Map<String,Object> demand=demands.get(0);
        String company=s(demand,"company_code"),sku=s(demand,"sku_sn");
        long quantity=demands.stream().mapToLong(d->n(d,"demand_quantity")-n(d,"cancelled_quantity")-n(d,"reserved_quantity")).sum();
        List<Map<String,Object>> locks=jdbc.queryForList("SELECT s.*,b.store_code,r.id result_id FROM rule_stock_reservation s JOIN wms_inventory_batch b ON b.id=s.batch_id AND b.company_code=s.company_code AND b.sku_sn=s.sku_sn JOIN rule_stock_result r ON r.company_code=s.company_code AND r.rule_id=s.rule_id AND r.sku_sn=s.sku_sn JOIN rule_stock_info h ON h.company_code=s.company_code AND h.id=s.rule_id WHERE s.company_code=? AND s.channel_id=? AND s.sku_sn=? AND r.status='SUCCESS' AND r.release_status='NONE' AND h.run_action<>'RELEASE' AND s.original_quantity>s.occupied_quantity+s.consumed_quantity+s.released_quantity ORDER BY s.id",company,demand.get("channel_id"),sku);
        for(Map<String,Object> line:demands)line.put("attempt_quantity",n(line,"demand_quantity")-n(line,"cancelled_quantity")-n(line,"reserved_quantity"));
        if(demands.stream().allMatch(d->n(d,"component_quantity")>0)){
            long lockFree=locks.stream().mapToLong(l->n(l,"original_quantity")-n(l,"occupied_quantity")-n(l,"consumed_quantity")-n(l,"released_quantity")).sum();
            long physicalFree=scalar("SELECT COALESCE(SUM(zp_available_number),0) FROM wms_inventory WHERE company_code=? AND sku_sn=?",company,sku)-publicReserved(jdbc,company,sku);
            long companyFree=scalar("SELECT available_stock FROM oms_inventory WHERE company_code=? AND sku_sn=?",company,sku);
            long capacity=lockFree+(allowPublic?Math.max(0,Math.min(physicalFree,companyFree)):0);
            if(capacity<quantity){quantity=0;for(Map<String,Object> line:demands){long take=Math.min(n(line,"attempt_quantity"),Math.max(0,capacity));line.put("attempt_quantity",take);capacity-=take;quantity+=take;if(take==0)waitForStock(Collections.singletonList(line),allowPublic?"SHORTAGE":"PUBLIC_DISABLED","套装组件库存不足，保留剩余需求等待组件预占");}if(quantity==0)return;}
        }
        Map<Map<String,Object>,Long> takes=new LinkedHashMap<>();long remaining=quantity;
        for(Map<String,Object> lock:locks){long take=Math.min(remaining,n(lock,"original_quantity")-n(lock,"occupied_quantity")-n(lock,"consumed_quantity")-n(lock,"released_quantity"));if(take>0)takes.put(lock,take);remaining-=take;if(remaining==0)break;}
        long publicQuantity=remaining,lockedQuantity=quantity-remaining;
        List<Map<String,Object>> companyRows=jdbc.queryForList("SELECT total_stock,available_stock,reserved_stock FROM oms_inventory WHERE company_code=? AND sku_sn=?",company,sku);Map<String,Object> companyBalance=companyRows.isEmpty()?Collections.emptyMap():companyRows.get(0);
        List<Map<String,Object>> channelRows=jdbc.queryForList("SELECT quota_stock,quota_reserved_stock,allocated_stock,available_stock,frozen_stock FROM oms_channel_inventory WHERE company_code=? AND channel_id=? AND sku_sn=?",company,demand.get("channel_id"),sku);Map<String,Object> channelBalance=channelRows.isEmpty()?Collections.emptyMap():channelRows.get(0);
        long good=scalar("SELECT COALESCE(SUM(zp_available_number),0) FROM wms_inventory WHERE company_code=? AND sku_sn=?",company,sku),publicFree=good-publicReserved(jdbc,company,sku);
        Map<String,Object> snapshot=new LinkedHashMap<>();snapshot.put("basis","RESERVATION_ATTEMPT");snapshot.put("observed_at",java.time.LocalDateTime.now().toString());snapshot.put("sku_demand_quantity",quantity);snapshot.put("company_available",n(companyBalance,"available_stock"));snapshot.put("warehouse_public_free",publicFree);snapshot.put("channel_available",n(channelBalance,"available_stock"));snapshot.put("channel_quota",n(channelBalance,"quota_stock"));snapshot.put("channel_quota_reserved",n(channelBalance,"quota_reserved_stock"));snapshot.put("channel_locked_taken",lockedQuantity);snapshot.put("public_needed",publicQuantity);snapshot.put("allow_public_stock",allowPublic);snapshot.put("public_quota_capacity",Math.max(0,n(channelBalance,"quota_stock")-n(channelBalance,"quota_reserved_stock")-n(channelBalance,"frozen_stock")));
        try{String json=JSON.writeValueAsString(snapshot);for(Map<String,Object> line:demands)jdbc.update("UPDATE oms_order_stock_demand SET decision_snapshot=? WHERE company_code=? AND id=?",json,company,line.get("id"));}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalArgumentException("预占依据无法记录");}
        if(publicQuantity>0&&!allowPublic){waitForStock(demands,"PUBLIC_DISABLED","渠道锁库余额不足，未允许公共库存补占");return;}
        if(publicFree<publicQuantity){waitForStock(demands,"SHORTAGE","同订单同SKU库存不足，保留完整需求等待预占");return;}
        int changed=jdbc.update("UPDATE oms_inventory SET available_stock=available_stock-?,allocated_stock=allocated_stock-?,reserved_stock=reserved_stock+?,version=version+1 WHERE company_code=? AND sku_sn=? AND available_stock>=? AND allocated_stock>=?",publicQuantity,lockedQuantity,quantity,company,sku,publicQuantity,lockedQuantity);
        if(changed==0){waitForStock(demands,"BALANCE_MISMATCH","公司库存余额不足，请核对库存来源");return;}
        require(jdbc.update("UPDATE oms_channel_inventory SET allocated_stock=allocated_stock-?,reserved_stock=reserved_stock+?,version=version+1 WHERE company_code=? AND channel_id=? AND sku_sn=? AND allocated_stock>=?",lockedQuantity,quantity,company,demand.get("channel_id"),sku,lockedQuantity)==1,"渠道锁库余额不一致，请核对后重试");
        for(Map.Entry<Map<String,Object>,Long> entry:takes.entrySet()){
            Map<String,Object> lock=entry.getKey();long take=entry.getValue();
            require(jdbc.update("UPDATE rule_stock_reservation SET occupied_quantity=occupied_quantity+? WHERE id=? AND original_quantity-occupied_quantity-consumed_quantity-released_quantity>=?",take,lock.get("id"),take)==1,"锁库来源余额已变化，请重试");
            require(jdbc.update("UPDATE rule_stock_result SET occupied_quantity=occupied_quantity+? WHERE id=? AND status='SUCCESS' AND release_status='NONE'",take,lock.get("result_id"))==1,"锁库单已开始释放，请重试");
        }
        for(Map<String,Object> line:demands){
            long left=n(line,"attempt_quantity"),locked=0;if(left==0)continue;long attempt=left;
            for(Map.Entry<Map<String,Object>,Long> entry:takes.entrySet()){
                long take=Math.min(left,entry.getValue());if(take==0)continue;
                Map<String,Object> lock=entry.getKey();
                jdbc.update("INSERT INTO oms_order_stock_source(demand_id,company_code,sku_sn,store_code,source_type,source_id,quantity,reserved) VALUES (?,?,?,?,'LOCK',?,?,1)",line.get("id"),company,sku,lock.get("store_code"),lock.get("id"),take);
                entry.setValue(entry.getValue()-take);left-=take;locked+=take;
            }
            if(left>0)jdbc.update("INSERT INTO oms_order_stock_source(demand_id,company_code,sku_sn,store_code,source_type,source_id,quantity,reserved) VALUES (?,?,?,'','PUBLIC_EXTRA',0,?,1)",line.get("id"),company,sku,left);
            long next=n(line,"reserved_quantity")+attempt;boolean full=next==n(line,"demand_quantity")-n(line,"cancelled_quantity");jdbc.update("UPDATE oms_order_stock_demand SET reserved_quantity=?,status=?,reason=?,reason_code=? WHERE id=?",next,full?"RESERVED":"PART_RESERVED",full?"":"套装组件部分逻辑预占，等待余量库存",full?"":"SHORTAGE",line.get("id"));
            event(line,"RESERVE","渠道锁库预占 "+locked+" 件，公共库存补占 "+left+" 件，尚未确定发货仓",attempt);
        }
    }
    private void waitForStock(List<Map<String,Object>> demands,String code,String reason){for(Map<String,Object> demand:demands)jdbc.update("UPDATE oms_order_stock_demand SET reason=?,reason_code=?,status='WAIT_RESERVE' WHERE id=?",reason,code,demand.get("id"));}
    /** Locked reservations are backed by WMS locks; only the remainder uses public goods. */
    public static long publicReserved(JdbcTemplate jdbc,String company,String sku){
        long total=jdbc.queryForObject("SELECT COALESCE(SUM(reserved_quantity-outbound_quantity),0) FROM oms_order_stock_demand WHERE company_code=? AND sku_sn=? FOR UPDATE",Long.class,company,sku);
        long locked=jdbc.queryForObject("SELECT COALESCE(SUM(quantity-outbound_quantity),0) FROM oms_order_stock_source WHERE company_code=? AND sku_sn=? AND source_type IN ('LOCK','LUCKY') AND reserved=1 FOR UPDATE",Long.class,company,sku);
        long warehousePublic=jdbc.queryForObject("SELECT COALESCE(SUM(quantity-released_quantity-outbound_quantity),0) FROM oms_order_warehouse_allocation WHERE company_code=? AND sku_sn=? AND source_type='PUBLIC_EXTRA' AND status='OCCUPIED'",Long.class,company,sku);
        require(total>=locked+warehousePublic,"订单预占与仓库来源记录不一致");return total-locked-warehousePublic;
    }
    /** Receipt refreshes pending summaries; the order retry queue attempts reservations. */
    public static void replanPending(JdbcTemplate jdbc,String company,String sku){refresh(jdbc,company,sku,0);}
    public static void refresh(JdbcTemplate jdbc,String company,String sku,long channel){
        refresh(jdbc,company,sku,channel,true);
    }
    public static void refreshChannel(JdbcTemplate jdbc,String company,String sku,long channel){refresh(jdbc,company,sku,channel,false);}
    private static void refresh(JdbcTemplate jdbc,String company,String sku,long channel,boolean allChannels){
        company=company.toUpperCase(Locale.ROOT);
        if(jdbc.queryForObject("SELECT COUNT(*) FROM oms_lucky_definition WHERE company_code=? AND sku_sn=?",Long.class,company,sku)>0){for(Long bagChannel:jdbc.queryForList("SELECT channel_id FROM oms_channel_inventory WHERE company_code=? AND sku_sn=?"+(allChannels?"":" AND channel_id=?"),Long.class,allChannels?new Object[]{company,sku}:new Object[]{company,sku,channel}))LuckyBagStock.project(jdbc,company,sku,bagChannel);return;}
        List<Object> pendingArgs=new ArrayList<>(Arrays.asList(company,sku));if(!allChannels)pendingArgs.add(channel);
        jdbc.update("UPDATE oms_channel_inventory c SET pending_stock=(SELECT COALESCE(SUM(demand_quantity-cancelled_quantity-reserved_quantity),0) FROM oms_order_stock_demand d WHERE d.company_code=UPPER(c.company_code) AND d.sku_sn=c.sku_sn AND d.channel_id=c.channel_id AND d.status<>'CANCELLED'),version=version+1 WHERE c.company_code=? AND c.sku_sn=?"+(allChannels?"":" AND c.channel_id=?"),pendingArgs.toArray());
        jdbc.update("UPDATE oms_inventory SET pending_public_stock=(SELECT COALESCE(SUM(GREATEST(0,pending_stock-allocated_stock)),0) FROM oms_channel_inventory WHERE company_code=? AND sku_sn=?) WHERE company_code=? AND sku_sn=?",company,sku,company,sku);
        // Public reservations consume the channel's quota even if made before allocation.
        // Rebuild the projection so retries, cancellation and reallocation cannot double count.
        Map<Long,Long> lockedByChannel=new HashMap<>();
        for(Map<String,Object> locked:jdbc.queryForList("SELECT d.channel_id,SUM(s.quantity-s.outbound_quantity) quantity FROM oms_order_stock_source s JOIN oms_order_stock_demand d ON d.id=s.demand_id AND d.company_code=s.company_code WHERE s.company_code=? AND s.sku_sn=? AND s.source_type IN ('LOCK','LUCKY') AND s.reserved=1 GROUP BY d.channel_id",company,sku))lockedByChannel.put(n(locked,"channel_id"),n(locked,"quantity"));
        // Every channel projection for this SKU is capped by its eligible remaining stock.
        Map<String,Long> freeByScope=new HashMap<>();
        List<Object> channelArgs=new ArrayList<>(Arrays.asList(company,sku));if(!allChannels)channelArgs.add(channel);
        for(Map<String,Object> row:jdbc.queryForList("SELECT channel_id,stock_scope_json,allocated_stock,pending_stock,quota_stock,reserved_stock,frozen_stock FROM oms_channel_inventory WHERE company_code=? AND sku_sn=?"+(allChannels?"":" AND channel_id=?"),channelArgs.toArray())){
            List<String> scope;
            try{scope=row.get("stock_scope_json")==null?Collections.emptyList():JSON.readValue(String.valueOf(row.get("stock_scope_json")),new TypeReference<List<String>>(){});if(scope==null)scope=Collections.emptyList();}catch(Exception e){scope=Collections.emptyList();}
            long publicFree=0;
            String scopeKey=String.valueOf(row.get("stock_scope_json"));
            if(freeByScope.containsKey(scopeKey))publicFree=freeByScope.get(scopeKey);
            else if(!scope.isEmpty()){List<Object> args=new ArrayList<>(Arrays.asList(company,sku));args.addAll(scope);publicFree=jdbc.queryForObject("SELECT COALESCE(SUM(zp_available_number),0) FROM wms_inventory WHERE company_code=? AND sku_sn=? AND store_code IN ("+marks(scope.size())+")",Long.class,args.toArray())-publicClaims(jdbc,company,sku,scope);freeByScope.put(scopeKey,publicFree);}
            else publicFree=0;
            long pendingLocked=Math.min(n(row,"pending_stock"),n(row,"allocated_stock"));
            long quotaReserved=Math.max(0,n(row,"reserved_stock")-lockedByChannel.getOrDefault(n(row,"channel_id"),0L));
            long quota=Math.max(0,Math.max(0,n(row,"quota_stock")-quotaReserved)+n(row,"allocated_stock")-n(row,"pending_stock")-n(row,"frozen_stock"));
            long cap=Math.max(0,publicFree)+Math.max(0,n(row,"allocated_stock")-pendingLocked);
            jdbc.update("UPDATE oms_channel_inventory SET quota_reserved_stock=?,available_stock=? WHERE company_code=? AND sku_sn=? AND channel_id=?",quotaReserved,Math.min(quota,cap),company,sku,row.get("channel_id"));
        }
    }
    public static long publicClaims(JdbcTemplate jdbc,String company,String sku,List<String> stores){
        if(stores.isEmpty())return 0;
        Long pending=jdbc.queryForObject("SELECT pending_public_stock FROM oms_inventory WHERE company_code=? AND sku_sn=?",Long.class,company,sku);
        return publicReserved(jdbc,company,sku)+(pending==null?0:pending);
    }
}
