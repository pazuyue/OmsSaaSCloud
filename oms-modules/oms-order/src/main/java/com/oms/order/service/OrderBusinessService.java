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
    @Resource private OrderShipmentReceiptReviewService receiptReviews;
    private static final String JOIN=" FROM oms_order o JOIN oms_platform_order p ON p.company_code=o.company_code AND p.id=o.platform_order_id LEFT JOIN oms_order_pull_config c ON c.company_code=o.company_code AND c.channel_id=o.channel_id LEFT JOIN oms_order_allocation_config ac ON ac.company_code=o.company_code AND ac.channel_id=o.channel_id";
    private static final String PROGRESS=",o.refund_block_reason,o.exchange_block_reason,COALESCE(JSON_UNQUOTE(JSON_EXTRACT(ac.policy_json,'$.execution_mode')),'MANUAL') auto_execution_mode,COALESCE(JSON_EXTRACT(ac.policy_json,'$.enabled'),0) auto_enabled,COALESCE(JSON_EXTRACT(ac.policy_json,'$.auto_replenish'),0) auto_replenish,p.update_version,p.update_state,p.update_categories,p.update_summary,p.change_pending,p.platform_status,p.fulfillment_block_reason,p.next_retry_at,c.channel_name,c.allow_public_stock,c.last_error shop_error,COALESCE(JSON_EXTRACT(COALESCE(o.allocation_policy,ac.policy_json),'$.enabled'),0) allocation_enabled,COALESCE(JSON_EXTRACT(COALESCE(o.allocation_policy,ac.policy_json),'$.allow_partial'),0) allocation_allow_partial";
    private String marks(int n){return String.join(",",Collections.nCopies(n,"?"));}
    private List<Long> ids(List<Long> values){require(values!=null&&!values.isEmpty()&&values.size()<=100,"每次请选择1至100张订单");for(Long id:values)require(id!=null&&id>0,"订单ID无效");return new ArrayList<>(new LinkedHashSet<>(values));}

    public TableDataInfo list(String company,Long channel,String number,String status,String sku,String from,String to,int waiting,int page,int pageSize){return list(company,channel,number,status,sku,from,to,waiting,page,pageSize,"","");}
    public TableDataInfo list(String company,Long channel,String number,String status,String sku,String from,String to,int waiting,int page,int pageSize,String review,String processing){return list(company,channel,number,status,sku,from,to,waiting,page,pageSize,review,processing,"");}
    public TableDataInfo list(String company,Long channel,String number,String status,String sku,String from,String to,int waiting,int page,int pageSize,String review,String processing,String documentStatus){
        return list(company,channel,number,status,sku,from,to,waiting,page,pageSize,review,processing,documentStatus,"","","","");
    }
    public TableDataInfo list(String company,Long channel,String number,String status,String sku,String from,String to,int waiting,int page,int pageSize,String review,String processing,String documentStatus,String issueCategory,String issueScope,String issueBlocking,String issueState){
        return list(company,channel,number,status,sku,from,to,waiting,page,pageSize,review,processing,documentStatus,issueCategory,issueScope,issueBlocking,issueState,"");
    }
    public TableDataInfo list(String company,Long channel,String number,String status,String sku,String from,String to,int waiting,int page,int pageSize,String review,String processing,String documentStatus,String issueCategory,String issueScope,String issueBlocking,String issueState,String updateState){
        return list(company,channel,number,status,sku,from,to,waiting,page,pageSize,review,processing,documentStatus,issueCategory,issueScope,issueBlocking,issueState,updateState,"");
    }
    public TableDataInfo list(String company,Long channel,String number,String status,String sku,String from,String to,int waiting,int page,int pageSize,String review,String processing,String documentStatus,String issueCategory,String issueScope,String issueBlocking,String issueState,String updateState,String exchangeStatus){
        String where=" WHERE o.company_code=? AND o.business_type='SALE'";List<Object> args=new ArrayList<>();args.add(company);
        if(!issueCategory.isEmpty()||!issueScope.isEmpty()||!issueBlocking.isEmpty()||!issueState.isEmpty()){
            String filter=" AND EXISTS(SELECT 1 FROM oms_order_issue oi WHERE oi.company_code=o.company_code AND oi.order_id=o.id";
            if(!issueCategory.isEmpty()){require(Arrays.asList("SHORTAGE","QUOTA","RESTRICTED","LEDGER","PLATFORM","AFTER_SALE").contains(issueCategory),"问题类型无效");filter+=" AND oi.category=?";args.add(issueCategory);}
            if(!issueScope.isEmpty()){require(Arrays.asList("COMPANY","CHANNEL","WAREHOUSE","BATCH","ORDER").contains(issueScope),"问题层级无效");filter+=" AND oi.scope=?";args.add(issueScope);}
            if(!issueBlocking.isEmpty()){require(Arrays.asList("0","1").contains(issueBlocking),"问题影响无效");filter+=" AND oi.blocking=?";args.add(Integer.parseInt(issueBlocking));}
            if(!issueState.isEmpty()){require(Arrays.asList("OPEN","ACKNOWLEDGED","RESOLVED").contains(issueState),"问题状态无效");filter+=" AND oi.state=?";args.add(issueState);}else filter+=" AND oi.state<>'RESOLVED'";
            where+=filter+")";
        }
        if(!exchangeStatus.isEmpty()){
            require(Arrays.asList("NONE","ACTIVE","COMPLETED","CLOSED").contains(exchangeStatus),"换货情况无效");
            String scope="SELECT 1 FROM oms_exchange ex WHERE ex.company_code=o.company_code AND ex.order_id=o.id";
            if("NONE".equals(exchangeStatus))where+=" AND NOT EXISTS("+scope+")";
            else where+=" AND EXISTS("+scope+("ACTIVE".equals(exchangeStatus)?" AND ex.state NOT IN ('COMPLETED','CANCELED','REJECTED')":"COMPLETED".equals(exchangeStatus)?" AND ex.state='COMPLETED'":" AND ex.state IN ('CANCELED','REJECTED')")+")";
        }
        if(!updateState.isEmpty()){require(Arrays.asList("WAITING","PROCESSING","ERROR","COMPLETED").contains(updateState),"订单更新状态无效");where+=" AND p.update_state=?";args.add(updateState);}
        if(!documentStatus.isEmpty()){require(Arrays.asList("WAIT_REVIEW","WAIT_ALLOCATION","PART_ALLOCATED","WAIT_CARRIER","WAIT_DISPATCH","DISPATCHING","PART_DISPATCHED","WAREHOUSE_PROCESSING","PART_SHIPPED","SHIPPED","CUTTING","CLOSED","VOIDING","VOIDED").contains(documentStatus),"业务状态无效");where+=" AND ("+OrderReviewPolicy.businessStatusSql()+")=?";args.add(documentStatus);}
        if(!review.isEmpty()){require(Arrays.asList("PENDING","APPROVED").contains(review),"审单状态无效");where+=" AND o.review_status=?";args.add(review);if("PENDING".equals(review))where+=" AND o.status='WAIT_ALLOCATION' AND EXISTS(SELECT 1 FROM oms_order_line rl WHERE rl.company_code=o.company_code AND rl.order_id=o.id AND rl.business_type='SALE' AND rl.quantity-rl.cancelled_quantity>(SELECT COALESCE(SUM(rs.shipped_quantity),0) FROM oms_order_shipment_line rs WHERE rs.company_code=rl.company_code AND rs.order_line_id=rl.id))";}
        if(!processing.isEmpty()){require(Arrays.asList("NORMAL","HELD").contains(processing),"处理状态无效");String blocked="(o.manual_hold=1 OR o.refund_block_reason<>'' OR o.exchange_block_reason<>'' OR p.change_pending=1 OR p.fulfillment_block_reason<>'' OR p.platform_status<>'WAIT_SELLER_SEND_GOODS')";where+=" AND o.status='WAIT_ALLOCATION' AND "+("HELD".equals(processing)?blocked:"NOT "+blocked);}
        if(channel!=null){where+=" AND o.channel_id=?";args.add(channel);}
        if(number!=null&&!number.trim().isEmpty()){where+=" AND (o.order_sn=? OR o.platform_tid=? OR EXISTS(SELECT 1 FROM oms_order_shipment fs WHERE fs.company_code=o.company_code AND fs.order_id=o.id AND (fs.shipment_sn=? OR EXISTS(SELECT 1 FROM oms_order_shipment_package fp WHERE fp.company_code=fs.company_code AND fp.shipment_id=fs.id AND fp.express_code=?))) OR EXISTS(SELECT 1 FROM oms_exchange fx WHERE fx.company_code=o.company_code AND fx.order_id=o.id AND (fx.exchange_sn=? OR fx.dispute_id=?)))";for(int lookup=0;lookup<6;lookup++)args.add(number.trim());}
        if(status!=null&&!status.isEmpty()){
            require(Arrays.asList("WAIT_RESERVE","PART_RESERVED","RESERVED","RELEASED","PART_CONSUMED","CONSUMED","SOURCE_CHANGED").contains(status),"逻辑预占状态无效");
            if("SOURCE_CHANGED".equals(status))where+=" AND p.change_pending=1";else{where+=" AND ("+OrderInventoryStatus.logicalStatusSql()+")=?";args.add(status);}
        }
        if(sku!=null&&!sku.trim().isEmpty()){where+=" AND EXISTS (SELECT 1 FROM oms_order_line l WHERE l.company_code=o.company_code AND l.order_id=o.id AND (l.sku_sn=? OR EXISTS(SELECT 1 FROM oms_order_bundle kb WHERE kb.company_code=l.company_code AND kb.id=l.bundle_id AND kb.sku_sn=?)))";args.add(sku.trim());args.add(sku.trim());}
        if(!from.isEmpty()){where+=" AND o.create_time>=?";args.add(time(from).format(TIME));}
        if(!to.isEmpty()){where+=" AND o.create_time<=?";args.add(time(to).format(TIME));}
        if(!from.isEmpty()&&!to.isEmpty())require(!time(from).isAfter(time(to)),"开始时间不能晚于结束时间");
        require(waiting>=0&&waiting<=5256000,"等待分钟数无效");
        if(waiting>0){where+=" AND o.status='WAIT_ALLOCATION' AND o.inventory_status<>'RESERVED' AND o.create_time<=DATE_SUB(NOW(),INTERVAL ? MINUTE)";args.add(waiting);}
        long total=repository.db().queryForObject("SELECT COUNT(*)"+JOIN+where,Long.class,args.toArray());
        int count=size(pageSize);args.add(count);args.add(offset(page,count));
        List<Map<String,Object>> rows=repository.db().query("SELECT o.gift_plan_id,o.gift_block_reason,o.business_type,o.exchange_id,o.source_order_id,o.issue_synced_at,o.issue_sync_error,o.locked_reserved_quantity,o.public_reserved_quantity,o.allocation_status,o.allocation_quantity,o.allocation_error,o.allocation_at,o.cutoff_state,o.cutoff_mode,o.cutoff_id,o.void_reason,o.void_by,o.void_requested_at,o.void_at,o.void_error,o.status,o.review_decision,o.review_evaluated_at,JSON_EXTRACT(o.review_snapshot,'$.rule_revision') review_rule_revision,o.review_status,o.review_policy,o.review_by,o.review_at,o.review_reason,o.manual_hold,o.hold_reason,o.hold_by,o.hold_at,o.control_version,o.id,o.order_sn,o.platform_tid,o.channel_id,o.inventory_status,o.payment,o.create_time,o.stock_attempt_status,o.stock_attempt_at,o.stock_error,o.reserved_at"+PROGRESS+JOIN+where+" ORDER BY o.id DESC LIMIT ? OFFSET ?",ROW,args.toArray());
        summarize(company,rows);
        OrderExchangeView.summaries(repository,company,rows);
        OrderAutoAllocationService.attach(repository.db(),company,rows);
        new OrderIssueService(repository).attach(company,rows);
        if(receiptReviews!=null)receiptReviews.attachOrders(company,rows);
        return page(rows,total);
    }
    private void summarize(String company,List<Map<String,Object>> rows){
        if(!rows.isEmpty()){
            List<Object> lineArgs=new ArrayList<>();lineArgs.add(company);for(Map<String,Object> row:rows)lineArgs.add(row.get("id"));
            Map<Long,Map<String,Object>> summaries=new HashMap<>();
            for(Map<String,Object> sum:repository.db().query("SELECT order_id,COUNT(DISTINCT CASE WHEN quantity>cancelled_quantity THEN sku_sn END) sku_count,SUM(quantity) demand_quantity,SUM(cancelled_quantity) cancelled_quantity,SUM(reserved_quantity) reserved_quantity,SUM(quantity-cancelled_quantity-reserved_quantity) pending_quantity,GROUP_CONCAT(DISTINCT NULLIF(inventory_reason,'') SEPARATOR '；') inventory_reason FROM oms_order_line WHERE company_code=? AND business_type='SALE' AND order_id IN ("+marks(rows.size())+") GROUP BY order_id",ROW,lineArgs.toArray()))summaries.put(number(sum,"order_id"),sum);
            for(Map<String,Object> sum:repository.db().query("SELECT s.order_id,COUNT(*) shipment_count,SUM(IF(s.status='WAIT_CARRIER',1,0)) pending_carrier_count,SUM(IF(EXISTS(SELECT 1 FROM oms_order_shipment_virtual_outbound pv WHERE pv.company_code=s.company_code AND pv.shipment_id=s.id AND pv.state<>'COMPLETED'),1,0)) pending_virtual_void,SUM(IF(s.status='DISPATCHING',1,0)) dispatching_count,SUM(IF(s.status IN ('WAREHOUSE_PROCESSING','PART_SHIPPED','SHIPPED') AND NOT EXISTS(SELECT 1 FROM oms_order_shipment_virtual_outbound vo WHERE vo.company_code=s.company_code AND vo.shipment_id=s.id),s.quantity,0)) warehouse_accepted_quantity,SUM(IF(s.status='VOIDED',0,s.quantity)) shipment_quantity,SUM((SELECT COALESCE(SUM(sl.shipped_quantity),0) FROM oms_order_shipment_line sl WHERE sl.company_code=s.company_code AND sl.shipment_id=s.id)) shipped_quantity,SUM(IF(s.status NOT IN ('WAIT_CARRIER','WAIT_DISPATCH','VOIDING','VOIDED') OR (SELECT COALESCE(SUM(sl.shipped_quantity),0) FROM oms_order_shipment_line sl WHERE sl.company_code=s.company_code AND sl.shipment_id=s.id)>0 OR EXISTS(SELECT 1 FROM oms_order_shipment_dispatch ds WHERE ds.company_code=s.company_code AND ds.shipment_id=s.id AND ds.state<>'CANCELED' AND (ds.state<>'FAILED' OR ds.external_order<>'')) OR EXISTS(SELECT 1 FROM oms_order_shipment_virtual_outbound vo WHERE vo.company_code=s.company_code AND vo.shipment_id=s.id),1,0)) shipment_blocks_void FROM oms_order_shipment s WHERE s.company_code=? AND s.business_type='SALE' AND s.order_id IN ("+marks(rows.size())+") GROUP BY s.order_id",ROW,lineArgs.toArray()))summaries.computeIfAbsent(number(sum,"order_id"),key->new HashMap<>()).putAll(sum);
            for(Map<String,Object> sum:repository.db().query("SELECT order_id,COUNT(*) bundle_count,SUM(IF(business_type='LUCKY_BAG',0,quantity)) bundle_sets,SUM(IF(business_type='LUCKY_BAG',quantity,0)) lucky_bag_quantity FROM oms_order_bundle WHERE company_code=? AND gift_plan_id IS NULL AND order_id IN ("+marks(rows.size())+") GROUP BY order_id",ROW,lineArgs.toArray()))summaries.computeIfAbsent(number(sum,"order_id"),key->new HashMap<>()).putAll(sum);
            for(Map<String,Object> row:rows){row.putAll(summaries.getOrDefault(number(row,"id"),Collections.emptyMap()));decorate(row);}
        }
    }

    public Map<String,Object> detail(String company,long id){
        List<Map<String,Object>> found=repository.db().query("SELECT o.*"+PROGRESS+JOIN+" WHERE o.company_code=? AND o.id=?",ROW,company,id);require(!found.isEmpty(),"业务订单不存在或无权访问");Map<String,Object> order=found.get(0);
        List<Map<String,Object>> lines=repository.db().query("SELECT l.*,ex.exchange_sn,p.outer_sku_id,p.platform_spec,p.manual_sku_sn,p.manual_match_by,p.manual_match_at,p.manual_match_reason FROM oms_order_line l LEFT JOIN oms_exchange ex ON ex.company_code=l.company_code AND ex.id=l.exchange_id LEFT JOIN oms_platform_order_line p ON p.company_code=l.company_code AND p.platform_order_id=? AND p.platform_oid=l.platform_oid AND l.exchange_id IS NULL WHERE l.company_code=? AND l.order_id=? AND l.business_type='SALE' ORDER BY l.id",ROW,order.get("platform_order_id"),company,id);
        long quantity=0,reserved=0,cancelled=0;Set<String> skus=new LinkedHashSet<>();
        for(Map<String,Object> line:lines){quantity+=number(line,"quantity");cancelled+=number(line,"cancelled_quantity");reserved+=number(line,"reserved_quantity");if(number(line,"quantity")>number(line,"cancelled_quantity"))skus.add(text(line,"sku_sn"));line.put("pending_quantity",number(line,"quantity")-number(line,"cancelled_quantity")-number(line,"reserved_quantity"));}
        order.put("cancelled_quantity",cancelled);order.put("demand_quantity",quantity);order.put("reserved_quantity",reserved);order.put("pending_quantity",quantity-cancelled-reserved);order.put("sku_count",skus.size());
        Set<String> reasons=new LinkedHashSet<>();for(Map<String,Object> line:lines)if(!text(line,"inventory_reason").isEmpty())reasons.add(text(line,"inventory_reason"));order.put("inventory_reason",String.join("；",reasons));
        // Read-only, batched lookups. Failure to load auxiliary details cannot hide the order.
        try{
            Map<String,Map<String,Object>> demandByOid=new HashMap<>();for(String reference:OrderFulfillmentLines.groups(order,lines).keySet())for(Map<String,Object> demand:data(inventory.demands(company,number(order,"channel_id"),reference,SecurityConstants.INNER)))demandByOid.put(reference+"/"+com.oms.common.util.FulfillmentLineKey.of(demand),demand);
            for(Map<String,Object> line:lines){Map<String,Object> demand=demandByOid.getOrDefault(OrderFulfillmentLines.reference(order,line)+"/"+com.oms.common.util.FulfillmentLineKey.of(line),Collections.emptyMap());line.put("stock_sources",demand.getOrDefault("sources",Collections.emptyList()));line.put("stock_snapshot",demand.getOrDefault("decision_snapshot",Collections.emptyMap()));}
        }catch(RuntimeException e){order.put("stock_sources_error","库存来源暂时加载失败，请刷新详情");}
        order.put("lines",lines);List<Map<String,Object>> bundles=OrderBundles.report(repository,company,id);order.put("bundles",bundles);order.put("bundle_count",bundles.stream().filter(b->b.get("gift_plan_id")==null).count());order.put("bundle_sets",bundles.stream().filter(b->b.get("gift_plan_id")==null&&!OrderLuckyBags.lucky(b)).mapToLong(b->number(b,"quantity")).sum());order.put("lucky_bag_quantity",bundles.stream().filter(b->b.get("gift_plan_id")==null&&OrderLuckyBags.lucky(b)).mapToLong(b->number(b,"quantity")).sum());
        List<Map<String,Object>> allShipments=new OrderShipmentService(repository,catalog).forOrder(company,id),shipments=new ArrayList<>(),afterShipments=new ArrayList<>();for(Map<String,Object> shipment:allShipments)(OrderShipmentOwner.exchange(shipment)?afterShipments:shipments).add(shipment);order.put("after_sales_shipments",afterShipments);if(receiptReviews!=null){receiptReviews.attach(company,shipments);receiptReviews.summarizeOrders(Collections.singletonList(order),shipments);}order.put("shipments",shipments);order.put("dispatching_count",shipments.stream().filter(s->"DISPATCHING".equals(text(s,"status"))).count());order.put("warehouse_accepted_quantity",shipments.stream().filter(s->"NONE".equals(text(s,"virtual_outbound_state"))&&Arrays.asList("WAREHOUSE_PROCESSING","PART_SHIPPED","SHIPPED").contains(text(s,"status"))).mapToLong(s->number(s,"quantity")).sum());order.put("pending_carrier_count",shipments.stream().filter(s->"WAIT_CARRIER".equals(text(s,"status"))).count());order.put("pending_virtual_void",shipments.stream().filter(s->!Arrays.asList("NONE","COMPLETED").contains(text(s,"virtual_outbound_state"))).count());order.put("shipment_count",shipments.size());order.put("shipment_quantity",shipments.stream().filter(s->!"VOIDED".equals(text(s,"status"))).mapToLong(s->number(s,"quantity")).sum());order.put("shipped_quantity",shipments.stream().mapToLong(s->number(s,"shipped_quantity")).sum());order.put("shipment_blocks_void",shipments.stream().filter(s->!Arrays.asList("WAIT_CARRIER","WAIT_DISPATCH","VOIDING","VOIDED").contains(text(s,"status"))||!"NONE".equals(text(s,"virtual_outbound_state"))||number(s,"shipped_quantity")>0||(!Arrays.asList("NONE","CANCELED").contains(text(s,"wms_dispatch_state"))&&(!"FAILED".equals(text(s,"wms_dispatch_state"))||!text(s,"wms_external_order").isEmpty()))).count());
        Map<Long,Long> shippedByLine=new HashMap<>(),shippedByAllocation=new HashMap<>();for(Map<String,Object> sum:repository.db().query("SELECT sl.order_line_id,sl.allocation_id,sl.shipped_quantity FROM oms_order_shipment_line sl JOIN oms_order_shipment ss ON ss.company_code=sl.company_code AND ss.id=sl.shipment_id WHERE ss.company_code=? AND ss.order_id=? AND ss.business_type='SALE'",ROW,company,id)){shippedByLine.merge(number(sum,"order_line_id"),number(sum,"shipped_quantity"),Long::sum);shippedByAllocation.put(number(sum,"allocation_id"),number(sum,"shipped_quantity"));}for(Map<String,Object> line:lines){long shipped=shippedByLine.getOrDefault(number(line,"id"),0L);line.put("shipped_quantity",shipped);line.put("logical_active_quantity",Math.max(0,number(line,"reserved_quantity")-shipped));}
        if(order.get("allocation_receipts")!=null)try{List<Map<String,Object>> warehouseAllocations=JSON.readValue(text(order,"allocation_receipts"),List.class);for(Map<String,Object> receipt:warehouseAllocations)receipt.put("outbound_quantity",shippedByAllocation.getOrDefault(number(receipt,"id"),0L));order.put("warehouse_allocations",warehouseAllocations);}catch(Exception e){order.put("warehouse_allocations",Collections.emptyList());}
        if(order.get("allocation_policy")!=null)try{order.put("allocation_rule",JSON.readValue(text(order,"allocation_policy"),Map.class));}catch(Exception e){order.put("allocation_rule",Collections.emptyMap());}
        OrderExchangeView.summaries(repository,company,Collections.singletonList(order));
        OrderExchangeView.detail(repository,catalog,company,order,lines,allShipments);
        OrderAutoReviewService.attachDetail(repository,company,order);
        order.remove("allocation_receipts");order.remove("allocation_policy");decorate(order);
        OrderIssueService issueReader=new OrderIssueService(repository);issueReader.attach(company,Collections.singletonList(order));Map<String,Object> report=issueReader.report(company,id);order.put("issue_history",report.get("issues"));order.put("issue_events",report.get("events"));
        for(Map<String,Object> line:lines){line.put("issues",((List<Map<String,Object>>)order.get("issues")).stream().filter(issue->text(issue,"sku_sn").equals(text(line,"sku_sn"))).collect(java.util.stream.Collectors.toList()));long lock=0,pub=0;for(Map<String,Object> source:(List<Map<String,Object>>)line.getOrDefault("stock_sources",Collections.emptyList())){if(Arrays.asList("LOCK","LUCKY").contains(text(source,"source_type")))lock+=number(source,"quantity");else if("PUBLIC_EXTRA".equals(text(source,"source_type")))pub+=number(source,"quantity");}line.put("locked_reserved_quantity",lock);line.put("public_reserved_quantity",pub);line.put("issue_evidence",((List<Map<String,Object>>)report.get("issues")).stream().filter(issue->!"RESOLVED".equals(text(issue,"state"))&&text(issue,"sku_sn").equals(text(line,"sku_sn"))).map(issue->issue.get("evidence")).findFirst().orElse(null));}
        OrderAutoAllocationService.attach(repository.db(),company,Collections.singletonList(order));
        String exchangeBlock=OrderExchangeGuard.reason(repository,company,id,false);if(!exchangeBlock.isEmpty()){order.put("system_hold_reason",exchangeBlock);order.put("exchange_block_reason",exchangeBlock);order.put("can_allocate",false);order.put("can_reserve",false);order.put("release_eligible",false);List<String> blocks=new ArrayList<>((List<String>)order.getOrDefault("release_blocks",Collections.emptyList()));blocks.add(exchangeBlock);order.put("release_blocks",blocks);}OrderDocumentInfo.attach(order);OrderDocumentInfo.related(repository,company,order);order.put("logs",repository.db().query("SELECT action,result,message,operator_name,create_time FROM oms_order_process_log WHERE company_code=? AND platform_order_id=? ORDER BY id DESC LIMIT 100",ROW,company,order.get("platform_order_id")));return order;
    }

    public List<Map<String,Object>> retry(String company,List<Long> requested,String actor){
        List<Map<String,Object>> result=new ArrayList<>();TransactionTemplate tx=new TransactionTemplate(new DataSourceTransactionManager(repository.db().getDataSource()));
        for(long id:ids(requested)){
            Map<String,Object> item=new LinkedHashMap<>();item.put("id",id);
            try{tx.execute(status->{
                List<Map<String,Object>> initial=repository.db().query("SELECT channel_id,platform_order_id FROM oms_order WHERE company_code=? AND id=?",ROW,company,id);require(initial.size()==1,"业务订单不存在或无权访问");
                List<Map<String,Object>> configs=repository.db().query("SELECT lease_until FROM oms_order_pull_config WHERE company_code=? AND channel_id=? FOR UPDATE",ROW,company,initial.get(0).get("channel_id"));require(configs.size()==1,"店铺配置不存在");
                require(configs.get(0).get("lease_until")==null||time(text(configs.get(0),"lease_until")).isBefore(LocalDateTime.now()),"店铺正在处理，请稍后刷新结果");
                Map<String,Object> platform=repository.db().query("SELECT change_pending,platform_status,fulfillment_block_reason,refund_block_reason,exchange_block_reason,business_order_id,conversion_status FROM oms_platform_order WHERE company_code=? AND id=? FOR UPDATE",ROW,company,initial.get(0).get("platform_order_id")).get(0);
                Map<String,Object> order=repository.db().query("SELECT * FROM oms_order WHERE company_code=? AND id=? FOR UPDATE",ROW,company,id).get(0);
                require("WAIT_ALLOCATION".equals(text(order,"status")),"订单已停止履约，不能重新预占");OrderReviewPolicy.requireRemaining(repository,company,id);
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
        List<Map<String,Object>> rows=repository.db().query("SELECT o.gift_plan_id,o.gift_block_reason,o.business_type,o.exchange_id,o.source_order_id,o.issue_synced_at,o.issue_sync_error,o.locked_reserved_quantity,o.public_reserved_quantity,o.allocation_status,o.allocation_quantity,o.allocation_error,o.allocation_at,o.cutoff_state,o.cutoff_mode,o.cutoff_id,o.void_reason,o.void_by,o.void_requested_at,o.void_at,o.void_error,o.status,o.review_decision,o.review_evaluated_at,JSON_EXTRACT(o.review_snapshot,'$.rule_revision') review_rule_revision,o.review_status,o.review_policy,o.review_by,o.review_at,o.review_reason,o.manual_hold,o.hold_reason,o.hold_by,o.hold_at,o.control_version,o.id,o.order_sn,o.platform_tid,o.inventory_status,o.stock_attempt_status,o.stock_attempt_at,o.stock_error,o.reserved_at"+PROGRESS+JOIN+" WHERE o.company_code=? AND o.id IN ("+marks(keys.size())+")",ROW,args.toArray());summarize(company,rows);return rows;
    }
    private void decorate(Map<String,Object> row){
        OrderReviewPolicy.decorate(row);
        boolean voided="VOIDED".equals(text(row,"status")),voiding="VOIDING".equals(text(row,"status"));
        row.put("can_cutoff",!Arrays.asList("VOIDING","VOIDED","CLOSED").contains(text(row,"status"))&&number(row,"demand_quantity")>number(row,"cancelled_quantity")+number(row,"shipped_quantity"));
        row.put("can_void",number(row,"shipped_quantity")==0&&number(row,"pending_virtual_void")==0&&("WAIT_ALLOCATION".equals(text(row,"status"))||(voiding&&!text(row,"void_error").isEmpty())));
        for(String field:Arrays.asList("shipment_count","shipment_quantity","shipped_quantity"))if(row.get(field)==null)row.put(field,0);
        row.put("pending_allocation_quantity",voided||voiding?0:Math.max(0,number(row,"demand_quantity")-number(row,"cancelled_quantity")-number(row,"allocation_quantity")));
        row.put("unshipped_quantity",voided||voiding?0:Math.max(0,number(row,"demand_quantity")-number(row,"cancelled_quantity")-number(row,"shipped_quantity")));
        if(voided){row.put("pending_quantity",0);Object lines=row.get("lines");if(lines instanceof List)for(Object line:(List<?>)lines)((Map<String,Object>)line).put("pending_quantity",0);}

        boolean held="HELD".equals(text(row,"processing_status"));
        row.put("can_generate_shipments",!OrderReviewPolicy.noRemaining(row)&&"WAIT_ALLOCATION".equals(text(row,"status"))&&!held&&"APPROVED".equals(text(row,"review_status"))&&Arrays.asList("PART","FULL").contains(text(row,"allocation_status"))&&number(row,"shipment_quantity")<number(row,"allocation_quantity"));
        boolean changed=number(row,"change_pending")!=0,done="RESERVED".equals(text(row,"inventory_status"));String attempt=text(row,"stock_attempt_status");
        row.put("can_reserve",!OrderReviewPolicy.noRemaining(row)&&"WAIT_ALLOCATION".equals(text(row,"status"))&&!held&&!changed&&!done&&!Arrays.asList("QUEUED","PROCESSING").contains(attempt));
        String message=held?(number(row,"manual_hold")==1?"人工挂起："+text(row,"hold_reason"):text(row,"system_hold_reason")):changed?"平台关键交易信息已变化，后续预占暂停，待订单更新处理":!text(row,"stock_error").isEmpty()?text(row,"stock_error"):done?"全部商品已完成逻辑预占":!text(row,"inventory_reason").isEmpty()?text(row,"inventory_reason"):"未完成的SKU等待足量逻辑预占";
        row.put("stock_message",voided?"订单已作废，销售需求已取消，逻辑预占已释放":voiding?(text(row,"void_error").isEmpty()?"作废处理中，已停止履约，等待逻辑预占释放确认":text(row,"void_error")):message);
        if(voided||voiding||held||changed||done)row.put("next_retry_at",null);
        OrderInventoryStatus.decorate(row);if(number(row,"shipped_quantity")>0&&!voided&&!voiding)row.put("stock_message","已出库核销 "+number(row,"logical_outbound_quantity")+" 件，仍逻辑预占 "+number(row,"logical_active_quantity")+" 件");
    }
}
