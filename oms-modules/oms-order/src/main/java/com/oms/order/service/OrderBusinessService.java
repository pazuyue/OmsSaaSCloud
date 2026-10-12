package com.oms.order.service;

import com.oms.common.api.OrderStockClient;
import com.ruoyi.common.core.constant.SecurityConstants;
import com.ruoyi.common.core.web.page.TableDataInfo;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import static com.oms.order.service.OrderValues.*;

/** Business-document workspace. Uses the existing queue and immutable converted lines. */
@Service
public class OrderBusinessService {
    @Resource private OrderRepository repository;
    @Resource private OrderStockClient inventory;
    @Resource private OrderCatalog catalog;
    private static final String JOIN=" FROM oms_order o JOIN oms_platform_order p ON p.company_code=o.company_code AND p.id=o.platform_order_id LEFT JOIN oms_order_pull_config c ON c.company_code=o.company_code AND c.channel_id=o.channel_id LEFT JOIN oms_order_allocation_config ac ON ac.company_code=o.company_code AND ac.channel_id=o.channel_id";
    private static final String PROGRESS=",p.change_pending,p.platform_status,p.fulfillment_block_reason,p.next_retry_at,c.channel_name,c.allow_public_stock,c.last_error shop_error,COALESCE(JSON_EXTRACT(COALESCE(o.allocation_policy,ac.policy_json),'$.enabled'),0) allocation_enabled,COALESCE(JSON_EXTRACT(COALESCE(o.allocation_policy,ac.policy_json),'$.allow_partial'),0) allocation_allow_partial";
    private String marks(int n){return String.join(",",Collections.nCopies(n,"?"));}
    private List<Long> ids(List<Long> values){require(values!=null&&!values.isEmpty()&&values.size()<=100,"每次请选择1至100张订单");for(Long id:values)require(id!=null&&id>0,"订单ID无效");return new ArrayList<>(new LinkedHashSet<>(values));}

    public TableDataInfo list(String company,Long channel,String number,String status,String sku,String from,String to,int waiting,int page,int pageSize){return list(company,channel,number,status,sku,from,to,waiting,page,pageSize,"","");}
    public TableDataInfo list(String company,Long channel,String number,String status,String sku,String from,String to,int waiting,int page,int pageSize,String review,String processing){return list(company,channel,number,status,sku,from,to,waiting,page,pageSize,review,processing,"");}
    public TableDataInfo list(String company,Long channel,String number,String status,String sku,String from,String to,int waiting,int page,int pageSize,String review,String processing,String documentStatus){
        String where=" WHERE o.company_code=?";List<Object> args=new ArrayList<>();args.add(company);
        if(!documentStatus.isEmpty()){require(Arrays.asList("WAIT_REVIEW","WAIT_ALLOCATION","PART_ALLOCATED","WAIT_CARRIER","WAIT_DISPATCH","DISPATCHING","PART_DISPATCHED","WAREHOUSE_PROCESSING","PART_SHIPPED","SHIPPED","VOIDING","VOIDED").contains(documentStatus),"业务状态无效");where+=" AND ("+OrderReviewPolicy.businessStatusSql()+")=?";args.add(documentStatus);}
        if(!review.isEmpty()){require(Arrays.asList("PENDING","APPROVED").contains(review),"审单状态无效");where+=" AND o.review_status=?";args.add(review);}
        if(!processing.isEmpty()){require(Arrays.asList("NORMAL","HELD").contains(processing),"处理状态无效");String blocked="(o.manual_hold=1 OR p.change_pending=1 OR p.fulfillment_block_reason<>'' OR p.platform_status<>'WAIT_SELLER_SEND_GOODS')";where+=" AND o.status='WAIT_ALLOCATION' AND "+("HELD".equals(processing)?blocked:"NOT "+blocked);}
        if(channel!=null){where+=" AND o.channel_id=?";args.add(channel);}
        if(number!=null&&!number.trim().isEmpty()){where+=" AND (o.order_sn=? OR o.platform_tid=?)";args.add(number.trim());args.add(number.trim());}
        if(status!=null&&!status.isEmpty()){
            require(Arrays.asList("WAIT_RESERVE","PART_RESERVED","RESERVED","RELEASED","PART_CONSUMED","CONSUMED","SOURCE_CHANGED").contains(status),"逻辑预占状态无效");
            if("SOURCE_CHANGED".equals(status))where+=" AND p.change_pending=1";else{where+=" AND ("+OrderInventoryStatus.logicalStatusSql()+")=?";args.add(status);}
        }
        if(sku!=null&&!sku.trim().isEmpty()){where+=" AND EXISTS (SELECT 1 FROM oms_order_line l WHERE l.company_code=o.company_code AND l.order_id=o.id AND l.sku_sn=?)";args.add(sku.trim());}
        if(!from.isEmpty()){where+=" AND o.create_time>=?";args.add(time(from).format(TIME));}
        if(!to.isEmpty()){where+=" AND o.create_time<=?";args.add(time(to).format(TIME));}
        if(!from.isEmpty()&&!to.isEmpty())require(!time(from).isAfter(time(to)),"开始时间不能晚于结束时间");
        require(waiting>=0&&waiting<=5256000,"等待分钟数无效");
        if(waiting>0){where+=" AND o.status='WAIT_ALLOCATION' AND o.inventory_status<>'RESERVED' AND o.create_time<=DATE_SUB(NOW(),INTERVAL ? MINUTE)";args.add(waiting);}
        long total=repository.db().queryForObject("SELECT COUNT(*)"+JOIN+where,Long.class,args.toArray());
        int count=size(pageSize);args.add(count);args.add(offset(page,count));
        List<Map<String,Object>> rows=repository.db().query("SELECT o.allocation_status,o.allocation_quantity,o.allocation_error,o.allocation_at,o.void_reason,o.void_by,o.void_requested_at,o.void_at,o.void_error,o.status,o.review_status,o.review_policy,o.review_by,o.review_at,o.review_reason,o.manual_hold,o.hold_reason,o.hold_by,o.hold_at,o.control_version,o.id,o.order_sn,o.platform_tid,o.channel_id,o.inventory_status,o.payment,o.create_time,o.stock_attempt_status,o.stock_attempt_at,o.stock_error,o.reserved_at"+PROGRESS+JOIN+where+" ORDER BY o.id DESC LIMIT ? OFFSET ?",ROW,args.toArray());
        summarize(company,rows);
        return page(rows,total);
    }
    private void summarize(String company,List<Map<String,Object>> rows){
        if(!rows.isEmpty()){
            List<Object> lineArgs=new ArrayList<>();lineArgs.add(company);for(Map<String,Object> row:rows)lineArgs.add(row.get("id"));
            Map<Long,Map<String,Object>> summaries=new HashMap<>();
            for(Map<String,Object> sum:repository.db().query("SELECT order_id,COUNT(DISTINCT sku_sn) sku_count,SUM(quantity) demand_quantity,SUM(reserved_quantity) reserved_quantity,SUM(quantity-reserved_quantity) pending_quantity,GROUP_CONCAT(DISTINCT NULLIF(inventory_reason,'') SEPARATOR '；') inventory_reason FROM oms_order_line WHERE company_code=? AND order_id IN ("+marks(rows.size())+") GROUP BY order_id",ROW,lineArgs.toArray()))summaries.put(number(sum,"order_id"),sum);
            for(Map<String,Object> sum:repository.db().query("SELECT s.order_id,COUNT(*) shipment_count,SUM(IF(s.status='WAIT_CARRIER',1,0)) pending_carrier_count,SUM(IF(s.status='DISPATCHING',1,0)) dispatching_count,SUM(IF(s.status IN ('WAREHOUSE_PROCESSING','PART_SHIPPED','SHIPPED') AND NOT EXISTS(SELECT 1 FROM oms_order_shipment_virtual_outbound vo WHERE vo.company_code=s.company_code AND vo.shipment_id=s.id),s.quantity,0)) warehouse_accepted_quantity,SUM(IF(s.status='VOIDED',0,s.quantity)) shipment_quantity,SUM((SELECT COALESCE(SUM(sl.shipped_quantity),0) FROM oms_order_shipment_line sl WHERE sl.company_code=s.company_code AND sl.shipment_id=s.id)) shipped_quantity,SUM(IF(s.status NOT IN ('WAIT_CARRIER','WAIT_DISPATCH','VOIDING','VOIDED') OR (SELECT COALESCE(SUM(sl.shipped_quantity),0) FROM oms_order_shipment_line sl WHERE sl.company_code=s.company_code AND sl.shipment_id=s.id)>0 OR EXISTS(SELECT 1 FROM oms_order_shipment_dispatch ds WHERE ds.company_code=s.company_code AND ds.shipment_id=s.id AND ds.state<>'CANCELED' AND (ds.state<>'FAILED' OR ds.external_order<>'')) OR EXISTS(SELECT 1 FROM oms_order_shipment_virtual_outbound vo WHERE vo.company_code=s.company_code AND vo.shipment_id=s.id),1,0)) shipment_blocks_void FROM oms_order_shipment s WHERE s.company_code=? AND s.order_id IN ("+marks(rows.size())+") GROUP BY s.order_id",ROW,lineArgs.toArray()))summaries.computeIfAbsent(number(sum,"order_id"),key->new HashMap<>()).putAll(sum);
            for(Map<String,Object> row:rows){row.putAll(summaries.getOrDefault(number(row,"id"),Collections.emptyMap()));decorate(row);}
        }
    }

    public Map<String,Object> detail(String company,long id){
        List<Map<String,Object>> found=repository.db().query("SELECT o.*"+PROGRESS+JOIN+" WHERE o.company_code=? AND o.id=?",ROW,company,id);require(!found.isEmpty(),"业务订单不存在或无权访问");Map<String,Object> order=found.get(0);
        List<Map<String,Object>> lines=repository.db().query("SELECT l.*,p.outer_sku_id,p.platform_spec,p.manual_sku_sn,p.manual_match_by,p.manual_match_at,p.manual_match_reason FROM oms_order_line l LEFT JOIN oms_platform_order_line p ON p.company_code=l.company_code AND p.platform_order_id=? AND p.platform_oid=l.platform_oid WHERE l.company_code=? AND l.order_id=? ORDER BY l.id",ROW,order.get("platform_order_id"),company,id);
        long quantity=0,reserved=0;Set<String> skus=new LinkedHashSet<>();
        for(Map<String,Object> line:lines){quantity+=number(line,"quantity");reserved+=number(line,"reserved_quantity");skus.add(text(line,"sku_sn"));line.put("pending_quantity",number(line,"quantity")-number(line,"reserved_quantity"));}
        order.put("demand_quantity",quantity);order.put("reserved_quantity",reserved);order.put("pending_quantity",quantity-reserved);order.put("sku_count",skus.size());
        Set<String> reasons=new LinkedHashSet<>();for(Map<String,Object> line:lines)if(!text(line,"inventory_reason").isEmpty())reasons.add(text(line,"inventory_reason"));order.put("inventory_reason",String.join("；",reasons));
        // Read-only, batched lookups. Failure to load auxiliary details cannot hide the order.
        try{
            Map<String,Map<String,Object>> demandByOid=new HashMap<>();for(Map<String,Object> demand:data(inventory.demands(company,number(order,"channel_id"),text(order,"platform_tid"),SecurityConstants.INNER)))demandByOid.put(text(demand,"platform_oid"),demand);
            for(Map<String,Object> line:lines)line.put("stock_sources",demandByOid.getOrDefault(text(line,"platform_oid"),Collections.emptyMap()).getOrDefault("sources",Collections.emptyList()));
        }catch(RuntimeException e){order.put("stock_sources_error","库存来源暂时加载失败，请刷新详情");}
        try{Map<String,List<Map<String,Object>>> goods=catalog.goods(company,new ArrayList<>(skus));for(Map<String,Object> line:lines){List<Map<String,Object>> matches=goods.getOrDefault(text(line,"sku_sn"),Collections.emptyList());if(matches.size()==1)line.put("oms_goods_name",matches.get(0).get("goods_name"));}}catch(RuntimeException e){order.put("goods_error","当前OMS商品资料暂时加载失败，已保留转单SKU");}
        order.put("lines",lines);
        List<Map<String,Object>> shipments=new OrderShipmentService(repository,catalog).forOrder(company,id);order.put("shipments",shipments);order.put("dispatching_count",shipments.stream().filter(s->"DISPATCHING".equals(text(s,"status"))).count());order.put("warehouse_accepted_quantity",shipments.stream().filter(s->"NONE".equals(text(s,"virtual_outbound_state"))&&Arrays.asList("WAREHOUSE_PROCESSING","PART_SHIPPED","SHIPPED").contains(text(s,"status"))).mapToLong(s->number(s,"quantity")).sum());order.put("pending_carrier_count",shipments.stream().filter(s->"WAIT_CARRIER".equals(text(s,"status"))).count());order.put("shipment_count",shipments.size());order.put("shipment_quantity",shipments.stream().filter(s->!"VOIDED".equals(text(s,"status"))).mapToLong(s->number(s,"quantity")).sum());order.put("shipped_quantity",shipments.stream().mapToLong(s->number(s,"shipped_quantity")).sum());order.put("shipment_blocks_void",shipments.stream().filter(s->!Arrays.asList("WAIT_CARRIER","WAIT_DISPATCH","VOIDING","VOIDED").contains(text(s,"status"))||!"NONE".equals(text(s,"virtual_outbound_state"))||number(s,"shipped_quantity")>0||(!Arrays.asList("NONE","CANCELED").contains(text(s,"wms_dispatch_state"))&&(!"FAILED".equals(text(s,"wms_dispatch_state"))||!text(s,"wms_external_order").isEmpty()))).count());
        Map<Long,Long> shippedByLine=new HashMap<>();for(Map<String,Object> sum:repository.db().query("SELECT sl.order_line_id,SUM(sl.shipped_quantity) shipped_quantity FROM oms_order_shipment_line sl JOIN oms_order_shipment ss ON ss.company_code=sl.company_code AND ss.id=sl.shipment_id WHERE ss.company_code=? AND ss.order_id=? GROUP BY sl.order_line_id",ROW,company,id))shippedByLine.put(number(sum,"order_line_id"),number(sum,"shipped_quantity"));for(Map<String,Object> line:lines){long shipped=shippedByLine.getOrDefault(number(line,"id"),0L);line.put("shipped_quantity",shipped);line.put("logical_active_quantity",Math.max(0,number(line,"reserved_quantity")-shipped));}
        if(order.get("allocation_receipts")!=null)try{order.put("warehouse_allocations",JSON.readValue(text(order,"allocation_receipts"),List.class));}catch(Exception e){order.put("warehouse_allocations",Collections.emptyList());}
        if(order.get("allocation_policy")!=null)try{order.put("allocation_rule",JSON.readValue(text(order,"allocation_policy"),Map.class));}catch(Exception e){order.put("allocation_rule",Collections.emptyMap());}
        order.remove("allocation_receipts");order.remove("allocation_policy");decorate(order);
        order.put("logs",repository.db().query("SELECT action,result,message,operator_name,create_time FROM oms_order_process_log WHERE company_code=? AND platform_order_id=? ORDER BY id DESC LIMIT 100",ROW,company,order.get("platform_order_id")));return order;
    }

    public List<Map<String,Object>> retry(String company,List<Long> requested,String actor){
        List<Map<String,Object>> result=new ArrayList<>();TransactionTemplate tx=new TransactionTemplate(new DataSourceTransactionManager(repository.db().getDataSource()));
        for(long id:ids(requested)){
            Map<String,Object> item=new LinkedHashMap<>();item.put("id",id);
            try{tx.execute(status->{
                List<Map<String,Object>> initial=repository.db().query("SELECT channel_id,platform_order_id FROM oms_order WHERE company_code=? AND id=?",ROW,company,id);require(initial.size()==1,"业务订单不存在或无权访问");
                List<Map<String,Object>> configs=repository.db().query("SELECT lease_until FROM oms_order_pull_config WHERE company_code=? AND channel_id=? FOR UPDATE",ROW,company,initial.get(0).get("channel_id"));require(configs.size()==1,"店铺配置不存在");
                require(configs.get(0).get("lease_until")==null||time(text(configs.get(0),"lease_until")).isBefore(LocalDateTime.now()),"店铺正在处理，请稍后刷新结果");
                Map<String,Object> platform=repository.db().query("SELECT change_pending,platform_status,fulfillment_block_reason,business_order_id,conversion_status FROM oms_platform_order WHERE company_code=? AND id=? FOR UPDATE",ROW,company,initial.get(0).get("platform_order_id")).get(0);
                Map<String,Object> order=repository.db().query("SELECT * FROM oms_order WHERE company_code=? AND id=? FOR UPDATE",ROW,company,id).get(0);
                require("WAIT_ALLOCATION".equals(text(order,"status")),"订单已停止履约，不能重新预占");
                require(number(order,"manual_hold")==0,"订单已人工挂起，请先解除挂起");String block=OrderReviewPolicy.systemBlock(platform);require(block.isEmpty(),block);
                require(number(platform,"change_pending")==0,"平台关键交易信息已变化，预占暂停，待订单更新处理");
                require(number(platform,"business_order_id")==id&&"SUCCESS".equals(text(platform,"conversion_status")),"业务订单关联状态不一致，请核对来源订单");
                require(!"RESERVED".equals(text(order,"inventory_status")),"预占已完成，无需重复提交");
                require(!"QUEUED".equals(text(order,"stock_attempt_status")),"已提交预占，请查看处理结果");
                repository.db().update("UPDATE oms_order SET stock_attempt_status='QUEUED' WHERE company_code=? AND id=?",company,id);
                repository.db().update("UPDATE oms_platform_order SET next_retry_at=NOW() WHERE company_code=? AND id=?",company,order.get("platform_order_id"));
                repository.log(company,number(order,"platform_order_id"),"STOCK_RETRY_REQUEST","PENDING","已提交重新预占，未完成的SKU使用最新转单设置",actor);
                item.put("order_sn",order.get("order_sn"));item.put("platform_tid",order.get("platform_tid"));return null;
            });item.put("submitted",true);item.put("message","已提交，等待逻辑预占结果");item.put("stock_attempt_status","QUEUED");}
            catch(IllegalArgumentException e){item.put("submitted",false);item.put("message",e.getMessage());}
            catch(RuntimeException e){item.put("submitted",false);item.put("message","提交失败，请稍后重试");}
            result.add(item);
        }
        return result;
    }

    public List<Map<String,Object>> results(String company,List<Long> requested){List<Long> keys=ids(requested);List<Object> args=new ArrayList<>();args.add(company);args.addAll(keys);
        List<Map<String,Object>> rows=repository.db().query("SELECT o.allocation_status,o.allocation_quantity,o.allocation_error,o.allocation_at,o.void_reason,o.void_by,o.void_requested_at,o.void_at,o.void_error,o.status,o.review_status,o.review_policy,o.review_by,o.review_at,o.review_reason,o.manual_hold,o.hold_reason,o.hold_by,o.hold_at,o.control_version,o.id,o.order_sn,o.platform_tid,o.inventory_status,o.stock_attempt_status,o.stock_attempt_at,o.stock_error,o.reserved_at"+PROGRESS+JOIN+" WHERE o.company_code=? AND o.id IN ("+marks(keys.size())+")",ROW,args.toArray());summarize(company,rows);return rows;
    }
    private void decorate(Map<String,Object> row){
        OrderReviewPolicy.decorate(row);
        boolean voided="VOIDED".equals(text(row,"status")),voiding="VOIDING".equals(text(row,"status"));
        row.put("can_void",number(row,"shipment_blocks_void")==0&&("WAIT_ALLOCATION".equals(text(row,"status"))||(voiding&&!text(row,"void_error").isEmpty())));
        for(String field:Arrays.asList("shipment_count","shipment_quantity","shipped_quantity"))if(row.get(field)==null)row.put(field,0);
        row.put("pending_allocation_quantity",voided||voiding?0:Math.max(0,number(row,"demand_quantity")-number(row,"allocation_quantity")));
        row.put("unshipped_quantity",voided||voiding?0:Math.max(0,number(row,"demand_quantity")-number(row,"shipped_quantity")));
        if(voided){row.put("pending_quantity",0);Object lines=row.get("lines");if(lines instanceof List)for(Object line:(List<?>)lines)((Map<String,Object>)line).put("pending_quantity",0);}

        boolean held="HELD".equals(text(row,"processing_status"));
        row.put("can_generate_shipments","WAIT_ALLOCATION".equals(text(row,"status"))&&!held&&"APPROVED".equals(text(row,"review_status"))&&Arrays.asList("PART","FULL").contains(text(row,"allocation_status"))&&number(row,"shipment_quantity")<number(row,"allocation_quantity"));
        boolean changed=number(row,"change_pending")!=0,done="RESERVED".equals(text(row,"inventory_status"));String attempt=text(row,"stock_attempt_status");
        row.put("can_reserve","WAIT_ALLOCATION".equals(text(row,"status"))&&!held&&!changed&&!done&&!Arrays.asList("QUEUED","PROCESSING").contains(attempt));
        String message=held?(number(row,"manual_hold")==1?"人工挂起："+text(row,"hold_reason"):text(row,"system_hold_reason")):changed?"平台关键交易信息已变化，后续预占暂停，待订单更新处理":!text(row,"stock_error").isEmpty()?text(row,"stock_error"):done?"全部商品已完成逻辑预占":!text(row,"inventory_reason").isEmpty()?text(row,"inventory_reason"):"未完成的SKU等待足量逻辑预占";
        row.put("stock_message",voided?"订单已作废，销售需求已取消，逻辑预占已释放":voiding?(text(row,"void_error").isEmpty()?"作废处理中，已停止履约，等待逻辑预占释放确认":text(row,"void_error")):message);
        if(voided||voiding||held||changed||done)row.put("next_retry_at",null);
        OrderInventoryStatus.decorate(row);if(number(row,"shipped_quantity")>0&&!voided&&!voiding)row.put("stock_message","已出库核销 "+number(row,"logical_outbound_quantity")+" 件，仍逻辑预占 "+number(row,"logical_active_quantity")+" 件");
    }
}
