package com.oms.order.service;

import com.ruoyi.common.core.web.page.TableDataInfo;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import static com.oms.order.service.OrderValues.*;

/** Shipment documents reference immutable inventory receipts; they never reserve stock again. */
@Service
public class OrderShipmentService {
    @Resource private OrderRepository repository;
    @Resource private OrderCatalog catalog;
    public OrderShipmentService(){}
    OrderShipmentService(OrderRepository repository){this.repository=repository;}
    OrderShipmentService(OrderRepository repository,OrderCatalog catalog){this.repository=repository;this.catalog=catalog;}
    private static final List<String> STATES=Arrays.asList("WAIT_CARRIER","WAIT_DISPATCH","DISPATCHING","WAREHOUSE_PROCESSING","PART_SHIPPED","SHIPPED","VOIDING","VOIDED");
    private static final String SHIPPED="(SELECT COALESCE(SUM(l.shipped_quantity),0) FROM oms_order_shipment_line l WHERE l.company_code=s.company_code AND l.shipment_id=s.id)";
    private static final String JOIN=" FROM oms_order_shipment s JOIN oms_order o ON o.company_code=s.company_code AND o.id=s.order_id LEFT JOIN oms_order_pull_config c ON c.company_code=s.company_code AND c.channel_id=s.channel_id LEFT JOIN oms_platform_order p ON p.company_code=o.company_code AND p.id=o.platform_order_id LEFT JOIN oms_order_shipment_dispatch d ON d.company_code=s.company_code AND d.shipment_id=s.id LEFT JOIN oms_order_shipment_virtual_outbound v ON v.company_code=s.company_code AND v.id=(SELECT MAX(vv.id) FROM oms_order_shipment_virtual_outbound vv WHERE vv.company_code=s.company_code AND vv.shipment_id=s.id)";
    private static final String SELECT="(SELECT COALESCE(SUM(pk.quantity),0) FROM oms_order_shipment_package pk WHERE pk.company_code=s.company_code AND pk.shipment_id=s.id) packaged_quantity,(SELECT COUNT(*) FROM oms_order_shipment_package pk WHERE pk.company_code=s.company_code AND pk.shipment_id=s.id) package_count,(SELECT COUNT(*) FROM oms_order_shipment_outbound ob WHERE ob.company_code=s.company_code AND ob.shipment_id=s.id AND ob.state IN ('PENDING','UNKNOWN','FAILED')) outbound_unconfirmed_count,s.id,s.shipment_sn,s.order_id,s.order_sn,s.channel_id,s.platform_tid,s.generation_no,s.owner_code,s.real_store_code,s.real_store_name,s.quantity,s.status,s.create_time,s.created_by,s.cancelled_at,s.receiver_province,s.carrier_code,s.carrier_name,s.warehouse_carrier_code,s.carrier_match_status,s.carrier_match_mode,s.carrier_match_reason,s.carrier_attempt_at,s.carrier_matched_at,s.carrier_matched_by,s.control_version,COALESCE(v.state,'NONE') virtual_outbound_state,v.last_error virtual_outbound_error,v.quantity virtual_outbound_quantity,v.request_id virtual_outbound_request_id,v.completed_at virtual_outbound_completed_at,COALESCE(d.state,'NONE') wms_dispatch_state,d.external_order wms_external_order,d.last_error wms_error,d.query_message wms_query_message,d.last_query_at wms_query_at,d.attempts wms_attempts,d.create_time wms_submitted_at,d.update_time wms_updated_at,d.warehouse_status wms_warehouse_status,c.channel_name,o.status order_status,o.manual_hold,o.review_status,p.change_pending,p.platform_status,p.fulfillment_block_reason,"+SHIPPED+" shipped_quantity";

    /** Called within the allocation transaction, after locking the order. Validate everything before writing. */
    public List<Map<String,Object>> create(String company,Map<String,Object> order,List<Map<String,Object>> lines,List<Map<String,Object>> receipts,String actor){
        require("WAIT_ALLOCATION".equals(text(order,"status")),"订单已停止履约，不能生成发货单");
        require(receipts!=null&&!receipts.isEmpty(),"没有已确认的分仓回执");
        Map<String,Map<String,Object>> byOid=new HashMap<>();for(Map<String,Object> line:lines)require(byOid.put(text(line,"platform_oid"),line)==null,"订单明细存在重复平台子单");
        List<Map<String,Object>> saved=repository.db().query("SELECT l.*,s.order_id,s.owner_code,s.real_store_code,s.real_store_name FROM oms_order_shipment_line l JOIN oms_order_shipment s ON s.company_code=l.company_code AND s.id=l.shipment_id WHERE s.company_code=? AND s.order_id=? ORDER BY l.id FOR UPDATE",ROW,company,order.get("id"));
        Map<Long,Map<String,Object>> previous=new HashMap<>();for(Map<String,Object> row:saved)previous.put(number(row,"allocation_id"),row);
        Map<String,Long> totals=new HashMap<>();Set<Long> ids=new HashSet<>();Set<String> owners=new HashSet<>();Map<String,List<Map<String,Object>>> groups=new LinkedHashMap<>();
        for(Map<String,Object> r:receipts){
            long receipt=number(r,"id"),qty=number(r,"quantity");require(receipt>0&&ids.add(receipt)&&qty>0,"分仓回执ID或数量无效、重复");
            if(r.containsKey("company_code"))require(company.equals(text(r,"company_code")),"分仓回执公司不一致");
            if(r.containsKey("channel_id"))require(number(order,"channel_id")==number(r,"channel_id"),"分仓回执店铺不一致");
            if(r.containsKey("platform_tid"))require(text(order,"platform_tid").equals(text(r,"platform_tid")),"分仓回执订单不一致");
            if(r.containsKey("status"))require("OCCUPIED".equals(text(r,"status")),"分仓回执已释放，不能生成发货单");
            String oid=text(r,"platform_oid");Map<String,Object> line=byOid.get(oid);require(line!=null&&text(line,"sku_sn").equals(text(r,"sku_sn")),"分仓回执商品与原订单不一致");
            require(!text(r,"owner_code").isEmpty()&&!text(r,"real_store_code").isEmpty()&&!text(r,"store_code").isEmpty()&&number(r,"batch_id")>0&&number(r,"stock_source_id")>0&&Arrays.asList("LOCK","PUBLIC_EXTRA").contains(text(r,"source_type")),"分仓回执缺少仓库或占用来源");
            owners.add(text(r,"owner_code"));long total=Math.addExact(totals.getOrDefault(oid,0L),qty);require(total<=number(line,"quantity"),"分仓回执超过原单商品数量");totals.put(oid,total);
            Map<String,Object> old=previous.get(receipt);
            if(old!=null){
                for(String key:Arrays.asList("platform_oid","sku_sn","owner_code","real_store_code","store_code","source_type","real_store_name"))require(text(old,key).equals(text(r,key)),"已生成发货单的分仓回执发生变化");
                for(String key:Arrays.asList("quantity","batch_id","stock_source_id"))require(number(old,key)==number(r,key),"已生成发货单的分仓回执数量或来源发生变化");
            }else groups.computeIfAbsent(encode(Arrays.asList(text(r,"owner_code"),text(r,"real_store_code"))),key->new ArrayList<>()).add(r);
        }
        require(owners.size()==1,"同一订单的分仓回执必须属于同一货主");require(ids.containsAll(previous.keySet()),"累计分仓回执缺失，不能重建发货单");
        if(!text(order,"allocation_policy").isEmpty())try{require(owners.contains(JSON.readTree(text(order,"allocation_policy")).path("owner_code").asText()),"分仓回执货主与规则不一致");}catch(java.io.IOException e){throw new IllegalArgumentException("分仓规则快照无效");}
        List<Map<String,Object>> created=new ArrayList<>();if(groups.isEmpty())return created;
        Map<String,Object> address=order;
        if(!order.containsKey("processing_payload")){
            List<Map<String,Object>> platform=repository.db().query("SELECT processing_payload FROM oms_platform_order WHERE company_code=? AND id=?",ROW,company,order.get("platform_order_id"));
            if(!platform.isEmpty())address=platform.get(0);
        }
        String province=OrderCarrierService.receiverProvince(address);
        long wave=repository.db().queryForObject("SELECT COALESCE(MAX(generation_no),0)+1 FROM oms_order_shipment WHERE company_code=? AND order_id=?",Long.class,company,order.get("id"));
        for(List<Map<String,Object>> group:groups.values()){
            Map<String,Object> first=group.get(0);long qty=group.stream().mapToLong(r->number(r,"quantity")).sum();String sn="XS"+token();
            for(Map<String,Object> r:group)require(text(first,"real_store_name").equals(text(r,"real_store_name")),"分仓回执实体仓名称不一致");
            repository.db().update("INSERT INTO oms_order_shipment(company_code,shipment_sn,order_id,order_sn,channel_id,platform_tid,generation_no,owner_code,real_store_code,real_store_name,quantity,receiver_name,receiver_phone,receiver_address,oaid,created_by) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",company,sn,order.get("id"),order.get("order_sn"),order.get("channel_id"),order.get("platform_tid"),wave,first.get("owner_code"),first.get("real_store_code"),text(first,"real_store_name"),qty,text(order,"receiver_name"),text(order,"receiver_phone"),text(order,"receiver_address"),text(order,"oaid"),actor);
            long shipment=repository.db().queryForObject("SELECT id FROM oms_order_shipment WHERE company_code=? AND shipment_sn=?",Long.class,company,sn);
            for(Map<String,Object> r:group){Map<String,Object> line=byOid.get(text(r,"platform_oid"));repository.db().update("INSERT INTO oms_order_shipment_line(company_code,shipment_id,allocation_id,order_line_id,platform_oid,sku_sn,goods_name,store_code,batch_id,source_type,stock_source_id,quantity) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",company,shipment,r.get("id"),line.get("id"),r.get("platform_oid"),r.get("sku_sn"),line.get("goods_name"),r.get("store_code"),r.get("batch_id"),r.get("source_type"),r.get("stock_source_id"),r.get("quantity"));}
            repository.db().update("UPDATE oms_order_shipment SET receiver_province=? WHERE company_code=? AND id=?",province,company,shipment);
            Map<String,Object> summary=new LinkedHashMap<>();summary.put("id",shipment);summary.put("shipment_sn",sn);summary.put("quantity",qty);summary.put("real_store_code",first.get("real_store_code"));summary.put("carrier_result",new OrderCarrierService(repository).matchNew(company,shipment,number(order,"platform_order_id"),actor));created.add(summary);
        }
        repository.log(company,number(order,"platform_order_id"),"SHIPMENT_CREATE","SUCCESS","生成 "+created.size()+" 张发货单，新增 "+groups.values().stream().flatMap(Collection::stream).mapToLong(r->number(r,"quantity")).sum()+" 件；已尝试按店铺规则匹配快递",actor);return created;
    }

    @SuppressWarnings("unchecked") public List<Map<String,Object>> generate(String company,long id,long version,String actor){
        // Historical receipt mirrors must also point to currently active physical warehouses.
        Set<String> active=new HashSet<>();for(Map<String,Object> store:catalog.warehouses(company))active.add(OrderCarrierPolicy.warehouseKey(store));
        return new TransactionTemplate(new DataSourceTransactionManager(repository.db().getDataSource())).execute(status->{
            List<Map<String,Object>> keys=repository.db().query("SELECT channel_id,platform_order_id FROM oms_order WHERE company_code=? AND id=?",ROW,company,id);require(keys.size()==1,"业务订单不存在或无权访问");
            List<Map<String,Object>> shops=repository.db().query("SELECT lease_until FROM oms_order_pull_config WHERE company_code=? AND channel_id=? FOR UPDATE",ROW,company,keys.get(0).get("channel_id"));require(shops.size()==1,"店铺配置不存在");require(shops.get(0).get("lease_until")==null||time(text(shops.get(0),"lease_until")).isBefore(LocalDateTime.now()),"店铺正在处理，请稍后重试");
            Map<String,Object> platform=repository.db().query("SELECT * FROM oms_platform_order WHERE company_code=? AND id=? FOR UPDATE",ROW,company,keys.get(0).get("platform_order_id")).get(0);
            Map<String,Object> order=repository.db().query("SELECT * FROM oms_order WHERE company_code=? AND id=? FOR UPDATE",ROW,company,id).get(0);
            require(number(order,"control_version")==version,"订单已变化，请刷新后重试");require(number(order,"manual_hold")==0&&"APPROVED".equals(text(order,"review_status")),"请先解除挂起并审核订单");String block=OrderReviewPolicy.systemBlock(platform);require(block.isEmpty(),block);
            require(number(platform,"business_order_id")==id&&"SUCCESS".equals(text(platform,"conversion_status")),"订单关联状态不一致");require(Arrays.asList("PART","FULL").contains(text(order,"allocation_status")),"请先确认分仓结果");
            List<Map<String,Object>> receipts;try{receipts=JSON.readValue(text(order,"allocation_receipts"),List.class);}catch(Exception e){throw new IllegalArgumentException("分仓回执缺失或损坏，请重试核对分仓结果");}
            require(receipts.stream().mapToLong(r->number(r,"quantity")).sum()==number(order,"allocation_quantity"),"分仓回执汇总数量不一致");
            for(Map<String,Object> receipt:receipts)require(active.contains(OrderCarrierPolicy.warehouseKey(receipt)),"分仓回执实体仓已停用或货主映射变化，请核对仓库资料");
            List<Map<String,Object>> created=create(company,order,repository.db().query("SELECT * FROM oms_order_line WHERE company_code=? AND order_id=? ORDER BY id",ROW,company,id),receipts,actor);
            if(!created.isEmpty())repository.db().update("UPDATE oms_order SET control_version=control_version+1 WHERE company_code=? AND id=?",company,id);return created;
        });
    }
    private void decorate(Map<String,Object> row){OrderShipmentWarehouseProgress.decorate(row);String block=OrderReviewPolicy.systemBlock(row);if(number(row,"manual_hold")==1)block="来源订单已人工挂起";else if(!"WAIT_ALLOCATION".equals(text(row,"order_status")))block="来源订单已停止履约";else if(!"APPROVED".equals(text(row,"review_status")))block="来源订单尚未审核通过";row.put("fulfillment_block",block);row.put("can_match_carrier","NONE".equals(text(row,"wms_dispatch_state"))&&block.isEmpty()&&number(row,"shipped_quantity")==0&&Arrays.asList("WAIT_CARRIER","WAIT_DISPATCH").contains(text(row,"status")));row.put("can_dispatch",block.isEmpty()&&number(row,"shipped_quantity")==0&&"WAIT_DISPATCH".equals(text(row,"status"))&&"MATCHED".equals(text(row,"carrier_match_status"))&&!text(row,"warehouse_carrier_code").isEmpty()&&Arrays.asList("NONE","FAILED").contains(text(row,"wms_dispatch_state")));row.put("can_query_warehouse",!Arrays.asList("NONE","CANCELED").contains(text(row,"wms_dispatch_state")));if(!"NONE".equals(text(row,"virtual_outbound_state"))){row.put("can_match_carrier",false);row.put("can_dispatch",false);}row.put("unpacked_quantity",Math.max(0,number(row,"shipped_quantity")-number(row,"packaged_quantity")));row.put("can_register_package",number(row,"unpacked_quantity")>0&&!"NONE".equals(text(row,"virtual_outbound_state"))&&Arrays.asList("PART_SHIPPED","SHIPPED").contains(text(row,"status")));row.put("can_reconcile_receipts",!Arrays.asList("NONE","CANCELED").contains(text(row,"wms_dispatch_state")));row.put("can_virtual_outbound",false);row.put("can_retry_virtual_outbound",false);row.put("can_query_virtual_outbound",!"NONE".equals(text(row,"virtual_outbound_state"))&&!"COMPLETED".equals(text(row,"virtual_outbound_state")));}
    private void modes(String company,List<Map<String,Object>> rows){if(rows.isEmpty()||catalog==null)return;try{List<Map<String,Object>> stores=catalog.warehouses(company);List<Object> args=new ArrayList<>();args.add(company);for(Map<String,Object> row:rows)args.add(row.get("id"));List<Map<String,Object>> lines=repository.db().query("SELECT shipment_id,store_code FROM oms_order_shipment_line WHERE company_code=? AND shipment_id IN ("+String.join(",",Collections.nCopies(rows.size(),"?"))+")",ROW,args.toArray());for(Map<String,Object> row:rows){Set<Long> values=new HashSet<>();int count=0;for(Map<String,Object> line:lines)if(number(line,"shipment_id")==number(row,"id")){count++;List<Map<String,Object>> matched=new ArrayList<>();for(Map<String,Object> store:stores)if(text(store,"wms_simulation_code").equals(text(line,"store_code"))&&text(store,"owner_code").equals(text(row,"owner_code"))&&text(store,"real_store_code").equals(text(row,"real_store_code")))matched.add(store);values.add(matched.size()==1?number(matched.get(0),"outbound_mode"):0);}long mode=count>0&&values.size()==1?values.iterator().next():0;row.put("outbound_mode",mode);if(mode!=1)row.put("can_dispatch",false);boolean ready=mode==2&&text(row,"fulfillment_block").isEmpty()&&"MATCHED".equals(text(row,"carrier_match_status"))&&"NONE".equals(text(row,"wms_dispatch_state"))&&Arrays.asList("WAIT_DISPATCH","PART_SHIPPED").contains(text(row,"status"))&&number(row,"shipped_quantity")<number(row,"quantity");row.put("can_virtual_outbound",ready&&Arrays.asList("NONE","COMPLETED").contains(text(row,"virtual_outbound_state")));row.put("can_retry_virtual_outbound",ready&&"FAILED".equals(text(row,"virtual_outbound_state")));if(mode==0)row.put("outbound_mode_error","来源仓已停用、归属变化或出库方式不一致，请核对仓库资料");}}catch(RuntimeException e){for(Map<String,Object> row:rows){row.put("outbound_mode",0);row.put("outbound_mode_error","出库方式暂未确认，请刷新后重试");row.put("can_dispatch",false);}}}
    public List<Map<String,Object>> forOrder(String company,long id){List<Map<String,Object>> rows=repository.db().query("SELECT "+SELECT+JOIN+" WHERE s.company_code=? AND s.order_id=? ORDER BY s.generation_no,s.id",ROW,company,id);for(Map<String,Object> row:rows)decorate(row);modes(company,rows);return rows;}
    public TableDataInfo list(String company,String sn,Long channel,String status,String store,String sku,int page,int pageSize){
        return list(company,sn,channel,status,store,sku,"",page,pageSize);
    }
    public TableDataInfo list(String company,String sn,Long channel,String status,String store,String sku,String dispatchState,int page,int pageSize){
        String where=" WHERE s.company_code=?";List<Object> args=new ArrayList<>();args.add(company);
        if(!dispatchState.isEmpty()){require(Arrays.asList("NONE","PENDING","PROCESSING","UNKNOWN","FAILED","ACCEPTED","CANCELED").contains(dispatchState),"仓库下发状态无效");where+=" AND COALESCE(d.state,'NONE')=?";args.add(dispatchState);}
        if(!sn.trim().isEmpty()){where+=" AND (s.shipment_sn=? OR s.order_sn=? OR s.platform_tid=?)";args.add(sn.trim());args.add(sn.trim());args.add(sn.trim());}
        if(channel!=null){where+=" AND s.channel_id=?";args.add(channel);}if(!status.isEmpty()){require(STATES.contains(status),"发货单状态无效");where+=" AND s.status=?";args.add(status);}
        if(!store.trim().isEmpty()){where+=" AND s.real_store_code=?";args.add(store.trim());}
        if(!sku.trim().isEmpty()){where+=" AND EXISTS(SELECT 1 FROM oms_order_shipment_line l WHERE l.company_code=s.company_code AND l.shipment_id=s.id AND l.sku_sn=?)";args.add(sku.trim());}
        long total=repository.db().queryForObject("SELECT COUNT(*)"+JOIN+where,Long.class,args.toArray());int count=size(pageSize);args.add(count);args.add(offset(page,count));List<Map<String,Object>> rows=repository.db().query("SELECT "+SELECT+JOIN+where+" ORDER BY s.id DESC LIMIT ? OFFSET ?",ROW,args.toArray());for(Map<String,Object> row:rows)decorate(row);modes(company,rows);return page(rows,total);
    }
    public Map<String,Object> detail(String company,long id){
        List<Map<String,Object>> rows=repository.db().query("SELECT "+SELECT+",s.receiver_name,s.receiver_phone,s.receiver_address,s.carrier_snapshot"+JOIN+" WHERE s.company_code=? AND s.id=?",ROW,company,id);require(rows.size()==1,"发货单不存在或无权访问");Map<String,Object> row=rows.get(0);
        row.put("lines",repository.db().query("SELECT l.*,(SELECT COALESCE(SUM(pl.quantity),0) FROM oms_order_shipment_package_line pl WHERE pl.company_code=l.company_code AND pl.shipment_line_id=l.id) packaged_quantity FROM oms_order_shipment_line l WHERE company_code=? AND shipment_id=? ORDER BY id",ROW,company,id));decorate(row);new OrderShipmentOutboundRecords(repository).detail(company,row);List<Map<String,Object>> intents=repository.db().query("SELECT payload_json FROM oms_order_shipment_dispatch WHERE company_code=? AND shipment_id=?",ROW,company,id);if(!intents.isEmpty())try{com.fasterxml.jackson.databind.JsonNode snapshot=JSON.readTree(text(intents.get(0),"payload_json"));row.put("warehouse_target",JSON.readValue(snapshot.path("route").toString(),Map.class));row.put("dispatch_lines",JSON.readValue(snapshot.path("lines").toString(),List.class));row.put("dispatch_quantity",snapshot.path("quantity").asLong());}catch(Exception e){row.put("wms_snapshot_error","仓库下发快照无法解析，请核对台账");}row.put("logs",repository.db().query("SELECT action,result,message,operator_name,create_time FROM oms_order_shipment_log WHERE company_code=? AND shipment_id=? ORDER BY id DESC LIMIT 100",ROW,company,id));modes(company,Arrays.asList(row));return row;
    }
    /** Frozen as soon as the parent stops fulfillment; release confirmation completes cancellation. */
    public void markVoiding(String company,long order){
        List<Map<String,Object>> rows=repository.db().query("SELECT s.id,s.status,COALESCE(d.state,'NONE') wms_dispatch_state,d.external_order wms_external_order,"+SHIPPED+" shipped_quantity FROM oms_order_shipment s LEFT JOIN oms_order_shipment_dispatch d ON d.company_code=s.company_code AND d.shipment_id=s.id WHERE s.company_code=? AND s.order_id=? FOR UPDATE",ROW,company,order);
        require(repository.db().queryForObject("SELECT COUNT(*) FROM oms_order_shipment_virtual_outbound WHERE company_code=? AND shipment_id IN (SELECT id FROM oms_order_shipment WHERE company_code=? AND order_id=?)",Long.class,company,company,order)==0,"发货单已有虚拟出库任务，不能直接整单作废");
        for(Map<String,Object> row:rows)require(Arrays.asList("WAIT_CARRIER","WAIT_DISPATCH","VOIDING","VOIDED").contains(text(row,"status"))&&number(row,"shipped_quantity")==0&&("NONE".equals(text(row,"wms_dispatch_state"))||(Arrays.asList("FAILED","CANCELED").contains(text(row,"wms_dispatch_state"))&&text(row,"wms_external_order").isEmpty())),"发货单已提交仓库或已有出库，不能直接整单作废");
        repository.db().update("UPDATE oms_order_shipment_dispatch SET state='CANCELED' WHERE company_code=? AND shipment_id IN (SELECT id FROM oms_order_shipment WHERE company_code=? AND order_id=?) AND state='FAILED' AND external_order=''",company,company,order);
        repository.db().update("UPDATE oms_order_shipment SET status='VOIDING' WHERE company_code=? AND order_id=? AND status IN ('WAIT_CARRIER','WAIT_DISPATCH')",company,order);
    }
    public void markVoided(String company,long order){repository.db().update("UPDATE oms_order_shipment SET status='VOIDED',cancelled_at=NOW() WHERE company_code=? AND order_id=? AND status='VOIDING'",company,order);}
}
