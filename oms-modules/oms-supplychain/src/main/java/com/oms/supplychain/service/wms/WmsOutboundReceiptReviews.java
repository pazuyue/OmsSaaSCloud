package com.oms.supplychain.service.wms;

import java.util.*;
import static com.oms.supplychain.service.wms.WmsStore.*;

/** Only parsed, signed and task-bound conflicts reach this durable human-review queue. */
final class WmsOutboundReceiptReviews {
    private final WmsStore db;
    private final WmsOutboundReceiptService service;
    WmsOutboundReceiptReviews(WmsStore db,WmsOutboundReceiptService service){this.db=db;this.service=service;}
    static class Conflict extends IllegalArgumentException {
        final String code;
        Conflict(String code,String message){super(message);this.code=code;}
    }
    void retain(String company,long taskId,WmsProtocol.OutboundReceipt receipt,long log,Conflict error){
        Map<String,Object> task=db.one("SELECT shipment_id,shipment_sn FROM wms_outbound_task WHERE company_code=? AND id=?",company,taskId);
        String json=db.encode(receipt),hash=QimenProtocol.hash("SHA-256",json).toLowerCase(Locale.ROOT);
        db.jdbc.update("INSERT IGNORE INTO wms_outbound_receipt_review(company_code,task_id,shipment_id,shipment_sn,message_id,receipt_hash,receipt_json,conflict_code,conflict_message,source_log_id) VALUES (?,?,?,?,?,?,?,?,?,?)",company,taskId,task.get("shipment_id"),task.get("shipment_sn"),receipt.messageId,hash,json,error.code,error.getMessage(),log);
    }
    @SuppressWarnings("unchecked") List<Map<String,Object>> list(String company,long shipment){
        List<Map<String,Object>> rows=db.jdbc.queryForList("SELECT r.id,r.shipment_id,r.shipment_sn,r.message_id,r.conflict_code,r.conflict_message,r.source_log_id,r.state,r.control_version,r.event_id,r.decision,r.reason,r.operator_name,r.decided_at,r.create_time,r.receipt_json,e.post_state,e.post_error FROM wms_outbound_receipt_review r LEFT JOIN wms_outbound_receipt_event e ON e.company_code=r.company_code AND e.id=r.event_id WHERE r.company_code=? AND r.shipment_id=? ORDER BY r.id DESC LIMIT 100",company,shipment);
        for(Map<String,Object> row:rows)try{
            WmsProtocol.OutboundReceipt receipt=db.json.readValue(text(row.remove("receipt_json")),WmsProtocol.OutboundReceipt.class);
            List<Map<String,Object>> incoming=new ArrayList<>(),existing=new ArrayList<>();
            for(WmsProtocol.OutboundPackage p:receipt.packages){
                incoming.add(map("package_code",p.packageCode,"express_code",p.expressCode,"logistics_code",p.logisticsCode,"items",p.items));
                existing.addAll(db.jdbc.queryForList("SELECT t.shipment_id,t.shipment_sn,p.package_code,p.express_code,p.logistics_code,p.lines_json FROM wms_outbound_receipt_package p JOIN wms_outbound_task t ON t.company_code=p.company_code AND t.id=p.task_id WHERE p.company_code=? AND ((p.task_id=? AND p.package_code=?) OR (p.logistics_code=? AND p.express_code=?))",company,receiptTask(company,id(row.get("id"))),p.packageCode,p.logisticsCode,p.expressCode));
            }
            for(Map<String,Object> p:existing)p.put("lines",db.json.readValue(text(p.remove("lines_json")),List.class));
            row.put("incoming_packages",incoming);row.put("existing_packages",existing);row.put("can_confirm_shared","WAIT_REVIEW".equals(row.get("state"))&&"WAYBILL_CONFLICT".equals(row.get("conflict_code")));
        }catch(Exception e){throw new IllegalArgumentException("待核对回传快照无法解析");}
        return rows;
    }
    private long receiptTask(String company,long review){return id(db.one("SELECT task_id FROM wms_outbound_receipt_review WHERE company_code=? AND id=?",company,review).get("task_id"));}
    Map<String,Object> decide(String company,long shipment,long review,Map<String,Object> body){
        String decision=required(body,"decision",32),reason=required(body,"reason",200),actor=required(body,"actor",100);
        require(Arrays.asList("CONFIRM_SHARED","REJECT").contains(decision)&&body.containsKey("control_version"),"核对处理参数无效");
        return db.tx().execute(status->{
            Map<String,Object> row=db.one("SELECT * FROM wms_outbound_receipt_review WHERE company_code=? AND shipment_id=? AND id=? FOR UPDATE",company,shipment,review);
            if(!"WAIT_REVIEW".equals(row.get("state"))){require(decision.equals(row.get("decision")),"该回传已处理，不能更换处理结论");return map("state",row.get("state"),"event_id",row.get("event_id"));}
            require(id(row.get("control_version"))==id(body.get("control_version")),"核对记录已变化，请刷新");
            Long event=null;
            if("CONFIRM_SHARED".equals(decision)){
                require("WAYBILL_CONFLICT".equals(row.get("conflict_code"))&&body.get("related_shipment_ids") instanceof List,"当前冲突不能按共用运单处理");
                String json=text(row.get("receipt_json"));require(QimenProtocol.hash("SHA-256",json).equalsIgnoreCase(text(row.get("receipt_hash"))),"原回传快照校验失败");
                WmsProtocol.OutboundReceipt receipt;try{receipt=db.json.readValue(json,WmsProtocol.OutboundReceipt.class);}catch(Exception e){throw new IllegalArgumentException("原回传无法解析");}
                event=service.acceptReviewed(company,id(row.get("task_id")),receipt,review,(List<?>)body.get("related_shipment_ids"));
            }
            String state=event==null?"REJECTED":"CONFIRMED";
            db.jdbc.update("UPDATE wms_outbound_receipt_review SET state=?,decision=?,reason=?,operator_name=?,decided_at=NOW(),event_id=?,control_version=control_version+1 WHERE company_code=? AND id=?",state,decision,reason,actor,event,company,review);
            return map("state",state,"event_id",event);
        });
    }
}
