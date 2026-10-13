package com.oms.supplychain.service.wms;

import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import static com.oms.supplychain.service.wms.WmsStore.*;

/** Sales execution documents mirror confirmed OMS facts. This service never calls inventory or WMS. */
@Service
public class SalesOutboundDocumentService {
    @Resource private WmsStore db;
    private String hash(String text){try{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format("%02x",b));return s.toString();}catch(Exception e){throw new IllegalStateException(e);}}
    private Object canonical(Object input){if(input instanceof Map){Map<String,Object> out=new TreeMap<>();((Map<?,?>)input).forEach((k,v)->out.put(k.toString(),canonical(v)));return out;}if(input instanceof List){List<Object> out=new ArrayList<>();for(Object v:(List<?>)input)out.add(canonical(v));return out;}return input;}
    private Timestamp timestamp(Object value){if(value instanceof java.time.LocalDateTime)return Timestamp.valueOf((java.time.LocalDateTime)value);if(value instanceof java.util.Date)return new Timestamp(((java.util.Date)value).getTime());return Timestamp.valueOf(text(value));}
    @SuppressWarnings("unchecked")
    public boolean sync(String company,Map<String,Object> body) {
        require(company!=null&&company.matches("[A-Za-z0-9_-]{1,50}"),"公司编码无效");String c=company.toUpperCase(Locale.ROOT);
        long shipment=id(body.get("id")),version=id(body.get("source_version"));String shipmentSn=required(body,"shipment_sn",64);
        require(shipment>0&&version>0&&shipmentSn.matches("XS[A-Za-z0-9_-]+"),"销售出库来源无效");
        require(body.get("lines") instanceof List && !((List<?>)body.get("lines")).isEmpty() && ((List<?>)body.get("lines")).size()<=500,"销售出库明细须为1至500行");
        require(body.get("movements") instanceof List,"销售出库记录缺失");
        List<Map<String,Object>> lines=(List<Map<String,Object>>)body.get("lines"),movements=(List<Map<String,Object>>)body.get("movements");
        Map<Long,Map<String,Object>> byId=new HashMap<>();Map<String,List<Map<String,Object>>> stores=new TreeMap<>();
        for(Map<String,Object> l:lines){long line=id(l.get("id")),qty=id(l.get("quantity")),shipped=id(l.get("shipped_quantity"));require(line>0&&byId.put(line,l)==null&&qty>0&&qty<=100000000&&shipped>=0&&shipped<=qty&&id(l.get("batch_id"))>0,"销售明细重复或数量无效");required(l,"sku_sn",255);String store=required(l,"store_code",255);require(text(l.get("batch_code")).length()<=255&&text(l.get("goods_name")).length()<=255,"销售商品资料过长");stores.computeIfAbsent(store,k->new ArrayList<>()).add(l);}
        Map<Long,Long> totals=new HashMap<>();Set<String> keys=new HashSet<>();Set<String> modes=new HashSet<>();
        for(Map<String,Object> m:movements){long line=id(m.get("id")),qty=id(m.get("quantity"));Map<String,Object> original=byId.get(line);String request=required(m,"request_id",64),mode=required(m,"source_mode",12);require(original!=null&&qty>0&&qty<=100000000&&id(m.get("batch_id"))==id(original.get("batch_id"))&&keys.add(request+"/"+line)&&Arrays.asList("WMS","VIRTUAL").contains(mode),"销售出库记录来源重复或无效");require(text(m.get("created_by")).length()<=64,"销售出库操作人过长");Timestamp.valueOf(required(m,"outbound_time",30));totals.merge(line,qty,Math::addExact);modes.add(mode);}
        require(modes.size()<=1,"同一销售单不能混用出库方式");for(Map<String,Object> l:lines)require(totals.getOrDefault(id(l.get("id")),0L)==id(l.get("shipped_quantity")),"累计出库量与逐次出库记录不一致");
        String digest=hash(db.encode(canonical(body)));String owner=required(body,"owner_code",64),real=required(body,"real_store_code",255);
        required(body,"order_sn",64);required(body,"platform_tid",100);require(id(body.get("order_id"))>0&&id(body.get("channel_id"))>0,"销售订单关联无效");
        String sourceStatus=required(body,"status",32);require(Arrays.asList("WAIT_CARRIER","DISPATCHING","WAIT_DISPATCH","WAREHOUSE_PROCESSING","PART_SHIPPED","SHIPPED","CANCELED","VOIDING","VOIDED").contains(sourceStatus),"销售出库状态无效");
        return db.tx().execute(tx->{
            db.jdbc.update("INSERT INTO wms_sales_outbound_lock(company_code,shipment_id) VALUES (?,?) ON DUPLICATE KEY UPDATE shipment_id=VALUES(shipment_id)",c,shipment);
            Set<String> previousStores=new HashSet<>(db.jdbc.queryForList("SELECT store_code FROM wms_sales_outbound_document WHERE company_code=? AND shipment_id=?",String.class,c,shipment));
            require(previousStores.isEmpty()||previousStores.equals(stores.keySet()),"销售出库仓库分组发生变化");
            for(Map.Entry<String,List<Map<String,Object>>> group:stores.entrySet()) {
                String store=group.getKey();List<Map<String,Object>> plan=group.getValue();
                List<Map<String,Object>> oldRows=db.jdbc.queryForList("SELECT d.*,t.actual_warehouse planned_mode FROM wms_sales_outbound_document d JOIN wms_tickets t ON t.id=d.ticket_id AND UPPER(t.company_code)=d.company_code WHERE d.company_code=? AND d.shipment_id=? AND d.store_code=? FOR UPDATE",c,shipment,store);
                Map<String,Object> previous=oldRows.isEmpty()?null:oldRows.get(0);
                if(previous!=null&&id(previous.get("source_version"))>version)continue;
                if(previous!=null&&id(previous.get("source_version"))==version){require(digest.equals(text(previous.get("source_hash"))),"相同销售单据版本内容不同");continue;}
                Map<String,Object> warehouse=db.one("SELECT v.*,o.owner_code,w.real_store_code FROM wms_simulation_store_info v JOIN owner_warehouse r ON r.id=v.owner_warehouse_id AND UPPER(r.company_code)=UPPER(v.company_code) JOIN owner_info o ON o.id=r.owner_id AND UPPER(o.company_code)=UPPER(r.company_code) JOIN wms_real_store_info w ON w.id=r.real_store_id AND UPPER(w.company_code)=UPPER(r.company_code) WHERE UPPER(v.company_code)=? AND v.wms_simulation_code=?",c,store);
                require(owner.equals(text(warehouse.get("owner_code")))&&real.equals(text(warehouse.get("real_store_code"))),"销售出库仓库货主归属不一致");
                long mode=modes.isEmpty()?(previous==null?id(warehouse.get("outbound_mode")):id(previous.get("planned_mode"))):modes.contains("WMS")?1:2;require(mode==1||mode==2,"销售出库方式未配置");
                long expected=plan.stream().mapToLong(l->id(l.get("quantity"))).sum(),actual=plan.stream().mapToLong(l->id(l.get("shipped_quantity"))).sum();boolean cancelled=sourceStatus.equals("CANCELED")||sourceStatus.equals("VOIDED");require(!cancelled||actual==0,"已有实际出库的销售单不能取消");
                String sn="XS"+hash(c+"/"+shipment+"/"+store).substring(0,32);long ticket;
                if(previous==null) {
                    ticket=db.insert("wms_tickets",map("company_code",c,"sn",sn,"business_type","SALE_OUT","ticket_type",2,"original_sn",body.get("order_sn"),"relation_sn",shipmentSn,"wms_simulation_code",store,"wms_simulation_name",warehouse.get("wms_simulation_name"),"real_store_code",real,"actual_warehouse",mode,"status_ticket",1,"user_name",text(body.get("created_by")),"remark","销售订单出库执行记录"));
                } else {
                    ticket=id(previous.get("ticket_id"));Map<String,Object> old=db.one("SELECT * FROM wms_tickets WHERE id=? AND UPPER(company_code)=? FOR UPDATE",ticket,c);
                    require(text(old.get("original_sn")).equals(text(body.get("order_sn")))&&text(previous.get("shipment_sn")).equals(shipmentSn)&&id(previous.get("order_id"))==id(body.get("order_id"))&&id(previous.get("channel_id"))==id(body.get("channel_id"))&&text(previous.get("platform_tid")).equals(text(body.get("platform_tid"))),"销售出库来源发生变化");
                    require(id(old.get("status_ticket"))!=5||cancelled,"已取消销售出库单不能恢复执行");
                    if(id(old.get("actual_warehouse"))!=mode){
                        require(actual>0&&modes.size()==1&&id(old.get("status_ticket"))!=5,"执行方式只能由首次实际出库回执确认");
                        require(db.jdbc.queryForObject("SELECT COUNT(*) FROM wms_sales_outbound_movement WHERE company_code=? AND ticket_id=?",Long.class,c,ticket)==0&&db.jdbc.queryForObject("SELECT COALESCE(SUM(number_actually),0) FROM wms_tickets_goods WHERE UPPER(company_code)=? AND sn=?",Long.class,c,sn)==0,"已有实际出库记录，不能切换执行方式");
                        db.jdbc.update("UPDATE wms_tickets SET actual_warehouse=? WHERE UPPER(company_code)=? AND id=?",mode,c,ticket);
                    }
                    require(db.jdbc.queryForObject("SELECT COUNT(*) FROM wms_tickets_goods WHERE UPPER(company_code)=? AND sn=?",Long.class,c,sn)==plan.size(),"销售计划明细发生变化");
                }
                Map<Long,Long> localLines=new HashMap<>();
                for(Map<String,Object> l:plan){long sourceLine=id(l.get("id")),qty=id(l.get("quantity")),shipped=id(l.get("shipped_quantity"));List<Map<String,Object>> old=db.jdbc.queryForList("SELECT * FROM wms_tickets_goods WHERE UPPER(company_code)=? AND sn=? AND source_line_id=? FOR UPDATE",c,sn,sourceLine);long local;
                    if(previous==null){local=db.insert("wms_tickets_goods",map("company_code",c,"sn",sn,"source_line_id",sourceLine,"inventory_type","ZP","sku_sn",l.get("sku_sn"),"goods_name",text(l.get("goods_name")),"batch_code",text(l.get("batch_code")),"number_expected",qty,"number_actually",shipped,"number_zp",shipped,"number_dif_actually",qty-shipped,"inventory_is_handle",shipped>0?2:0));}
                    else {require(old.size()==1,"销售出库来源行缺失");Map<String,Object> row=old.get(0);require(id(row.get("number_expected"))==qty&&text(row.get("sku_sn")).equals(text(l.get("sku_sn")))&&text(row.get("batch_code")).equals(text(l.get("batch_code")))&&shipped>=id(row.get("number_actually")),"销售出库计划变化或累计数量回退");local=id(row.get("id"));db.jdbc.update("UPDATE wms_tickets_goods SET number_actually=?,number_zp=?,number_dif_actually=?,inventory_is_handle=? WHERE id=? AND UPPER(company_code)=?",shipped,shipped,qty-shipped,shipped>0?2:0,local,c);}
                    localLines.put(sourceLine,local);
                }
                for(Map<String,Object> m:movements)if(localLines.containsKey(id(m.get("id")))) {
                    List<Map<String,Object>> found=db.jdbc.queryForList("SELECT * FROM wms_sales_outbound_movement WHERE company_code=? AND request_id=? AND source_line_id=?",c,m.get("request_id"),m.get("id"));
                    if(found.isEmpty())db.insert("wms_sales_outbound_movement",map("company_code",c,"ticket_id",ticket,"ticket_line_id",localLines.get(id(m.get("id"))),"source_line_id",m.get("id"),"request_id",m.get("request_id"),"source_mode",m.get("source_mode"),"quantity",m.get("quantity"),"batch_id",m.get("batch_id"),"operator_name",text(m.get("created_by")),"outbound_time",Timestamp.valueOf(text(m.get("outbound_time")))));
                    else {Map<String,Object> old=found.get(0);require(id(old.get("ticket_id"))==ticket&&id(old.get("quantity"))==id(m.get("quantity"))&&id(old.get("batch_id"))==id(m.get("batch_id"))&&text(old.get("source_mode")).equals(text(m.get("source_mode")))&&text(old.get("operator_name")).equals(text(m.get("created_by")))&&timestamp(m.get("outbound_time")).equals(timestamp(old.get("outbound_time"))),"原销售出库记录内容发生变化");}
                }
                for(Map<String,Object> l:plan)require(db.jdbc.queryForObject("SELECT COALESCE(SUM(quantity),0) FROM wms_sales_outbound_movement WHERE company_code=? AND ticket_id=? AND source_line_id=?",Long.class,c,ticket,l.get("id"))==id(l.get("shipped_quantity")),"销售出库历史记录不完整");
                db.jdbc.update("UPDATE wms_tickets SET status_ticket=?,inventory_status=?,status_notify=?,shipping_code=?,shipping_name=?,real_store_code=? WHERE id=? AND UPPER(company_code)=?",cancelled?5:actual==expected?2:1,actual>0?2:0,text(body.get("dispatch_state")).equals("ACCEPTED")?1:0,text(body.get("carrier_code")),text(body.get("carrier_name")),real,ticket,c);
                Map<String,Object> fields=map("company_code",c,"shipment_id",shipment,"shipment_sn",shipmentSn,"store_code",store,"ticket_id",ticket,"order_id",body.get("order_id"),"platform_tid",body.get("platform_tid"),"channel_id",body.get("channel_id"),"channel_name",text(body.get("channel_name")),"source_version",version,"source_hash",digest,"source_status",sourceStatus,"dispatch_state",text(body.get("dispatch_state")),"external_order",text(body.get("external_order")),"warehouse_status",text(body.get("warehouse_status")),"last_error",text(body.get("last_error")));
                if(previous==null)db.insert("wms_sales_outbound_document",fields);
                else db.jdbc.update("UPDATE wms_sales_outbound_document SET source_version=?,source_hash=?,source_status=?,dispatch_state=?,external_order=?,warehouse_status=?,last_error=? WHERE id=? AND company_code=?",version,digest,sourceStatus,fields.get("dispatch_state"),fields.get("external_order"),fields.get("warehouse_status"),fields.get("last_error"),previous.get("id"),c);
                db.insert("purchase_event",map("company_code",c,"document_sn",sn,"action",previous==null?"生成销售出库单":"同步销售出库进度","operator","OMS","message","计划 "+expected+" 件，累计出库并扣账 "+actual+" 件；来源 "+shipmentSn));
            }
            return true;
        });
    }
}
