package com.oms.order.service;

import com.ruoyi.common.core.constant.SecurityConstants;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.annotation.Resource;
import java.util.*;
import static com.oms.order.service.OrderValues.*;

/** Durable read-model delivery. The warehouse document bridge never executes stock commands. */
@Service
public class OrderSalesDocumentService {
    @Resource private OrderRepository repository;
    @Resource private OrderShipmentWmsClient warehouse;

    public void sync(String company,long shipmentId) {
        String lease=java.util.UUID.randomUUID().toString();
        if(repository.db().update("UPDATE oms_order_sales_document_sync SET work_token=?,work_until=DATE_ADD(NOW(),INTERVAL 180 SECOND) WHERE company_code=? AND shipment_id=? AND (work_until IS NULL OR work_until<NOW())",lease,company,shipmentId)!=1)return;
        try{perform(company,shipmentId);}finally{repository.db().update("UPDATE oms_order_sales_document_sync SET work_token=NULL,work_until=NULL WHERE company_code=? AND shipment_id=? AND work_token=?",company,shipmentId,lease);}
    }
    @SuppressWarnings("unchecked")
    private void perform(String company,long shipmentId) {
        final long[] dirty={0};
        Map<String,Object> delivery=new TransactionTemplate(new DataSourceTransactionManager(repository.db().getDataSource())).execute(tx->{
            // Match business mutation lock order: shop, platform, order, shipment, then projection cursor.
            Map<String,Object> keys=repository.db().queryForMap("SELECT s.channel_id,s.order_id,o.platform_order_id FROM oms_order_shipment s JOIN oms_order o ON o.company_code=s.company_code AND o.id=s.order_id WHERE s.company_code=? AND s.id=?",company,shipmentId);
            repository.db().queryForList("SELECT channel_id FROM oms_order_pull_config WHERE company_code=? AND channel_id=? FOR UPDATE",company,keys.get("channel_id"));
            repository.db().queryForList("SELECT id FROM oms_platform_order WHERE company_code=? AND id=? FOR UPDATE",company,keys.get("platform_order_id"));
            repository.db().queryForList("SELECT id FROM oms_order WHERE company_code=? AND id=? FOR UPDATE",company,keys.get("order_id"));
            repository.db().queryForList("SELECT id FROM oms_order_shipment WHERE company_code=? AND id=? FOR UPDATE",company,shipmentId);
            Map<String,Object> cursor=repository.db().query("SELECT * FROM oms_order_sales_document_sync WHERE company_code=? AND shipment_id=? FOR UPDATE",ROW,company,shipmentId).get(0);
            Map<String,Object> head=repository.db().query("SELECT s.*,CASE WHEN s.business_type='EXCHANGE' THEN e.allocation_receipts ELSE o.allocation_receipts END allocation_receipts,CASE WHEN s.business_type='EXCHANGE' THEN e.exchange_sn ELSE s.order_sn END business_owner_sn,c.channel_name,COALESCE(d.state,'NONE') dispatch_state,COALESCE(d.external_order,'') external_order,COALESCE(d.warehouse_status,'') warehouse_status,COALESCE(d.last_error,'') dispatch_error FROM oms_order_shipment s JOIN oms_order o ON o.company_code=s.company_code AND o.id=s.order_id LEFT JOIN oms_exchange e ON e.company_code=s.company_code AND e.id=s.exchange_id LEFT JOIN oms_order_pull_config c ON c.company_code=s.company_code AND c.channel_id=s.channel_id LEFT JOIN oms_order_shipment_dispatch d ON d.company_code=s.company_code AND d.shipment_id=s.id WHERE s.company_code=? AND s.id=? FOR UPDATE",ROW,company,shipmentId).get(0);
            dirty[0]=number(cursor,"dirty_version");
            require(text(head,"shipment_sn").startsWith("XS"),"仅新建 XS 销售出库单进入统一台账");
            Map<String,Object> body=new LinkedHashMap<>();
            for(String key:Arrays.asList("business_type","business_owner_sn","exchange_id","id","shipment_sn","order_id","order_sn","platform_tid","channel_id","channel_name","owner_code","real_store_code","status","dispatch_state","external_order","warehouse_status","created_by","carrier_code","carrier_name"))body.put(key,head.get(key));
            body.put("source_exchange_id",head.get("exchange_id"));if(!OrderShipmentOwner.exchange(head))body.put("exchange_id",null);body.put("last_error",text(head,"dispatch_error"));
            List<Map<String,Object>> lines=repository.db().query("SELECT id,allocation_id,sku_sn,goods_name,store_code,batch_id,quantity,shipped_quantity,cancelled_quantity FROM oms_order_shipment_line WHERE company_code=? AND shipment_id=? ORDER BY id",ROW,company,shipmentId);
            Map<Long,String> batches=new HashMap<>();if(!text(head,"allocation_receipts").isEmpty())try{for(Map<String,Object> r:(List<Map<String,Object>>)JSON.readValue(text(head,"allocation_receipts"),List.class))batches.put(number(r,"id"),text(r,"batch_code"));}catch(Exception e){throw new IllegalArgumentException("原分仓批次快照损坏");}
            for(Map<String,Object> line:lines)line.put("batch_code",batches.getOrDefault(number(line,"allocation_id"),""));
            require(!lines.isEmpty(),"销售出库明细缺失");body.put("lines",lines);
            List<Map<String,Object>> movements=new ArrayList<>();
            for(Map<String,Object> event:repository.db().query("SELECT request_id,source_mode,payload_json,created_by,COALESCE(outbound_time,completed_at) outbound_time FROM oms_order_shipment_outbound WHERE company_code=? AND shipment_id=? AND state='COMPLETED' ORDER BY id",ROW,company,shipmentId)) {
                Map<String,Object> command;try{command=JSON.readValue(text(event,"payload_json"),Map.class);}catch(Exception e){throw new IllegalArgumentException("销售出库回执损坏");}
                require(command.get("lines") instanceof List,"销售出库回执行缺失");
                for(Map<String,Object> item:(List<Map<String,Object>>)command.get("lines")) {
                    Map<String,Object> movement=new LinkedHashMap<>();for(String key:Arrays.asList("request_id","source_mode","created_by"))movement.put(key,event.get(key));
                    movement.put("outbound_time",String.valueOf(event.get("outbound_time")));movement.put("id",item.get("id"));movement.put("quantity",item.get("quantity"));movement.put("batch_id",item.get("batch_id"));movements.add(movement);
                }
            }
            body.put("movements",movements);String digest=hash(encode(body));long revision=number(cursor,"revision");
            if(!digest.equals(text(cursor,"payload_hash"))) {
                revision++;body.put("source_version",revision);repository.db().update("UPDATE oms_order_sales_document_sync SET revision=?,payload_hash=?,payload_json=?,last_error='' WHERE company_code=? AND shipment_id=?",revision,digest,encode(body),company,shipmentId);
            } else if(number(cursor,"synced_revision")==revision) {
                repository.db().update("UPDATE oms_order_sales_document_sync SET synced_dirty_version=GREATEST(synced_dirty_version,?),next_attempt=NULL WHERE company_code=? AND shipment_id=?",dirty[0],company,shipmentId);return null;
            } else try{body=JSON.readValue(text(cursor,"payload_json"),LinkedHashMap.class);}catch(Exception e){throw new IllegalArgumentException("销售单据同步快照损坏");}
            return body;
        });
        if(delivery==null)return;
        try {
            require(Boolean.TRUE.equals(data(warehouse.salesDocument(company,delivery,SecurityConstants.INNER))),"销售出库单据同步未确认");
            repository.db().update("UPDATE oms_order_sales_document_sync SET synced_revision=GREATEST(synced_revision,?),synced_dirty_version=GREATEST(synced_dirty_version,?),last_error=IF(revision=?,'',last_error),next_attempt=IF(dirty_version>?,NOW(),NULL) WHERE company_code=? AND shipment_id=?",delivery.get("source_version"),dirty[0],delivery.get("source_version"),dirty[0],company,shipmentId);
        } catch(RuntimeException e) {
            String message=e instanceof IllegalArgumentException?e.getMessage():"单据服务暂时不可用，系统会重试同步";if(message==null)message="单据服务暂时不可用，系统会重试同步";
            repository.db().update("UPDATE oms_order_sales_document_sync SET last_error=?,next_attempt=DATE_ADD(NOW(),INTERVAL 30 SECOND) WHERE company_code=? AND shipment_id=? AND revision=? AND dirty_version=?",message.substring(0,Math.min(1000,message.length())),company,shipmentId,delivery.get("source_version"),dirty[0]);
        }
    }
}
