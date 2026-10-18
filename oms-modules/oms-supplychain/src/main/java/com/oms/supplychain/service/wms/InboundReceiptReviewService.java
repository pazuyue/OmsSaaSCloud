package com.oms.supplychain.service.wms;

import com.oms.supplychain.service.warehouse.impl.PurchaseWorkspaceService;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Lazy;
import javax.annotation.Resource;
import java.util.*;
import static com.oms.supplychain.service.wms.WmsStore.*;

@Service
public class InboundReceiptReviewService {
    @Resource private WmsStore db;
    @Resource private PurchaseWorkspaceService purchase;
    @Resource @Lazy private WmsInboundService inbound;
    /** Called while the purchase and WMS task are locked. The rejected fact survives the callback acknowledgement. */
    public long retainLocked(String company,Map<String,Object> task,WmsProtocol.Receipt receipt,long sourceLog){
        String json=db.encode(receipt),hash=QimenProtocol.hash("SHA-256",json);db.jdbc.update("INSERT IGNORE INTO wms_inbound_receipt_review(company_code,task_id,ticket_id,ticket_sn,message_id,receipt_hash,receipt_json,source_log_id,conflict_message) VALUES (?,?,?,?,?,?,?,?,?)",company,task.get("id"),task.get("ticket_id"),task.get("ticket_sn"),receipt.messageId,hash,json,sourceLog,"仓库确认取消后出现新的收货回传，本次未入账，需人工核对");return db.jdbc.queryForObject("SELECT id FROM wms_inbound_receipt_review WHERE company_code=? AND task_id=? AND receipt_hash=?",Long.class,company,task.get("id"),hash);
    }
    public Map<String,Object> detail(String company,long id){Map<String,Object> r=db.one("SELECT id,ticket_id,ticket_sn,message_id,state,conflict_message,reason,operator_name,control_version,receipt_json FROM wms_inbound_receipt_review WHERE company_code=? AND id=?",company,id);try{r.put("receipt",db.json.readValue(text(r.remove("receipt_json")),Map.class));}catch(Exception e){throw new IllegalArgumentException("回传核对快照不可读");}return r;}
    public long decide(String company,long id,Map<String,Object> body,String actor){String decision=required(body,"decision",16),reason=required(body,"reason",500);require(Arrays.asList("REJECT","ACCEPT").contains(decision),"核对动作无效");require(body.containsKey("control_version"),"请刷新核对记录");
        return db.tx().execute(tx->{Map<String,Object> key=db.one("SELECT ticket_id FROM wms_inbound_receipt_review WHERE company_code=? AND id=?",company,id);purchase.lockWmsTicket(company,WmsStore.id(key.get("ticket_id")));Map<String,Object> r=db.one("SELECT * FROM wms_inbound_receipt_review WHERE company_code=? AND id=? FOR UPDATE",company,id);require(WmsStore.id(r.get("control_version"))==WmsStore.id(body.get("control_version")),"核对记录已变化，请刷新");require("WAIT_REVIEW".equals(r.get("state")),"该记录已经处理");
            if("ACCEPT".equals(decision)){require(Boolean.TRUE.equals(body.get("confirmed_actual")),"请明确确认仓库实际收货及对应批次");WmsProtocol.Receipt receipt;try{receipt=db.json.readValue(text(r.get("receipt_json")),WmsProtocol.Receipt.class);}catch(Exception e){throw new IllegalArgumentException("收货快照损坏，不能确认");}inbound.acceptReviewed(company,inbound.task(company,WmsStore.id(r.get("ticket_id"))),receipt);}
            db.jdbc.update("UPDATE wms_inbound_receipt_review SET state=?,reason=?,operator_name=?,control_version=control_version+1,decided_at=NOW() WHERE company_code=? AND id=?","ACCEPT".equals(decision)?"ACCEPTED":"REJECTED",reason,actor,company,id);purchase.recordWmsEvent(company,text(r.get("ticket_sn")),"人工核对取消后收货",actor+"："+decision+"；"+reason);return WmsStore.id(r.get("ticket_id"));
        });
    }
}
