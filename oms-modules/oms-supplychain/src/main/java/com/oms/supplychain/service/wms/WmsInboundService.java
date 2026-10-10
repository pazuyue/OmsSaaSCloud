package com.oms.supplychain.service.wms;

import com.oms.supplychain.model.dto.warehouse.SimulationStoreInfoDto;
import com.oms.supplychain.service.warehouse.impl.PurchaseWorkspaceService;
import com.oms.common.api.RemoteInventoryService;
import com.oms.common.model.dto.wms.WmsInventoryBatchDto;
import com.oms.common.model.entity.wms.WmsInventoryBatch;
import com.ruoyi.common.core.domain.R;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import javax.annotation.Resource;
import java.net.URI;
import java.net.http.*;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import static com.oms.supplychain.service.wms.WmsStore.*;

@Service
public class WmsInboundService {
    @Resource private WmsStore db;
    @Resource private WmsConnections connections;
    @Resource private WmsInteractionLog logs;
    @Resource private List<WmsProtocol> protocols;
    @Resource @Lazy private PurchaseWorkspaceService purchase;
    @Resource private RemoteInventoryService inventory;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();

    public WmsProtocol protocol(String name){return protocols.stream().filter(p->p.provider().equals(name)).findFirst().orElseThrow(()->new IllegalArgumentException("该仓库协议尚未接入，不能下发"));}
    /** Called in the approval transaction, so approval and durable dispatch are atomic. */
    public void prepare(String company,long ticketId,SimulationStoreInfoDto warehouse){
        require(TransactionSynchronizationManager.isActualTransactionActive(),"创建入库任务必须在审核事务内");
        require(warehouse.getConnectionId()!=null,"真实入库虚仓未绑定对接配置");
        db.one("SELECT id FROM wms_connection WHERE id=? AND company_code=? LOCK IN SHARE MODE",warehouse.getConnectionId(),company);
        Map<String,Object> c=connections.usable(company,warehouse.getConnectionId());protocol(text(c.get("provider")));
        require(!text(warehouse.getExternalWarehouse()).isEmpty()&&!text(warehouse.getExternalOwner()).isEmpty(),"请维护虚仓的外部仓库和货主编码");
        Map<String,Object> ticket=db.one("SELECT * FROM wms_tickets WHERE id=? AND UPPER(company_code)=?",ticketId,company);
        db.insert("wms_inbound_task",map("company_code",company,"ticket_id",ticketId,"ticket_sn",ticket.get("sn"),"connection_id",warehouse.getConnectionId(),"external_warehouse",warehouse.getExternalWarehouse(),"external_owner",warehouse.getExternalOwner()));
    }
    public Map<String,Object> task(String company,long ticket){List<Map<String,Object>> rows=db.jdbc.queryForList("SELECT t.*,c.provider,c.name connection_name FROM wms_inbound_task t JOIN wms_connection c ON c.id=t.connection_id AND c.company_code=t.company_code WHERE t.company_code=? AND t.ticket_id=?",company,ticket);return rows.isEmpty()?null:rows.get(0);}
    public void retry(String company,long ticket){db.tx().execute(s->{purchase.lockWmsTicket(company,ticket);Map<String,Object> t=task(company,ticket);require(t!=null,"该单据没有真实入库任务");require("FAILED".equals(t.get("dispatch_state")),"只有明确下发失败的单据可以重试，超时单据请先查询仓库结果");connections.usable(company,id(t.get("connection_id")));db.jdbc.update("UPDATE wms_inbound_task SET dispatch_state='PENDING',next_attempt=NOW(),last_error='' WHERE id=?",t.get("id"));return null;});}
    public void dispatch(String company,long taskId){
        int claimed=db.jdbc.update("UPDATE wms_inbound_task SET dispatch_state='SENDING',attempts=attempts+1,lease_until=DATE_ADD(NOW(),INTERVAL 60 SECOND) WHERE id=? AND company_code=? AND dispatch_state='PENDING' AND next_attempt<=NOW()",taskId,company);
        if(claimed!=1)return;
        Map<String,Object> task=db.one("SELECT * FROM wms_inbound_task WHERE id=? AND company_code=?",taskId,company);
        try{connections.usable(company,id(task.get("connection_id")));}catch(Exception e){db.jdbc.update("UPDATE wms_inbound_task SET dispatch_state='FAILED',last_error=?,lease_until=NULL WHERE id=? AND dispatch_state='SENDING'",text(e.getMessage()),taskId);return;}
        send(company,task,"CREATE");
    }
    public void query(String company,long ticket){Map<String,Object> t=task(company,ticket);require(t!=null,"没有真实入库任务");require(!Arrays.asList("PENDING","SENDING","CANCELED").contains(text(t.get("dispatch_state"))),"当前状态不能查询仓库");require(claimAction(company,id(t.get("id"))),"该单据正在与仓库交互，请稍后重试");send(company,t,"QUERY");}
    private boolean claimAction(String company,long task){return db.jdbc.update("UPDATE wms_inbound_task SET lease_until=DATE_ADD(NOW(),INTERVAL 60 SECOND) WHERE id=? AND company_code=? AND (lease_until IS NULL OR lease_until<NOW())",task,company)==1;}
    public void cancel(String company,long ticket){
        Map<String,Object> task=db.tx().execute(s->{Map<String,Object> document=purchase.lockWmsTicket(company,ticket);Map<String,Object> t=task(company,ticket);require(t!=null,"没有真实入库任务");require(db.jdbc.queryForObject("SELECT COUNT(*) FROM wms_receipt_line WHERE task_id=?",Long.class,t.get("id"))==0,"已收货的入库单不能取消");require(id(document.get("statusTicket"))==1,"当前执行单不可取消");require("ACCEPTED".equals(t.get("dispatch_state")),"请先确认仓库受理结果，再申请取消");require(claimAction(company,id(t.get("id"))),"单据正在与仓库交互");db.jdbc.update("UPDATE wms_inbound_task SET dispatch_state='CANCEL_PENDING' WHERE id=?",t.get("id"));return t;});
        send(company,task,"CANCEL");
    }
    private void send(String company,Map<String,Object> task,String action){
        long taskId=id(task.get("id")),start=System.currentTimeMillis(),logId=0;String secret="",responseBody="";
        try{
            Map<String,Object> config=connections.get(company,id(task.get("connection_id")));secret=connections.secret(config);WmsProtocol p=protocol(text(config.get("provider")));
            Map<String,Object> ticket=db.one("SELECT * FROM wms_tickets WHERE id=? AND UPPER(company_code)=?",task.get("ticket_id"),company);
            List<Map<String,Object>> lines=db.jdbc.queryForList("SELECT * FROM wms_tickets_goods WHERE sn=? AND UPPER(company_code)=? ORDER BY id",task.get("ticket_sn"),company);
            WmsProtocol.Wire wire=p.request(config,secret,action,task,ticket,lines);
            logId=logs.start(company,id(config.get("id")),taskId,text(task.get("ticket_sn")),"OUT",action,wire.body);
            HttpRequest request=HttpRequest.newBuilder(URI.create(wire.url)).timeout(Duration.ofSeconds(15)).header("Content-Type",wire.contentType).POST(HttpRequest.BodyPublishers.ofString(wire.body)).build();
            HttpResponse<String> response=http.send(request,HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));String body=response.body();responseBody=body;require(body.length()<=1024*1024,"仓库响应超过1MB");
            require(response.statusCode()>=200&&response.statusCode()<300,"仓库 HTTP 响应 "+response.statusCode());
            WmsProtocol.Reply reply=p.reply(action,body);updateReply(company,task,action,reply);
            logs.finish(logId,reply.success?"SUCCESS":"FAILED",body.replace(secret,"***"),reply.message.replace(secret,"***"),System.currentTimeMillis()-start);
        }catch(Exception e){
            if(e instanceof InterruptedException)Thread.currentThread().interrupt();
            // The remote warehouse may have committed even when its reply is lost. Never re-create blindly.
            String error=e instanceof IllegalArgumentException?text(e.getMessage()):"仓库交互未确认："+e.getClass().getSimpleName();
            db.jdbc.update("UPDATE wms_inbound_task SET dispatch_state=CASE WHEN dispatch_state='SENDING' THEN 'UNKNOWN' ELSE dispatch_state END,last_error=?,lease_until=NULL WHERE id=? AND company_code=?",error,taskId,company);
            if(logId==0)logId=logs.start(company,id(task.get("connection_id")),taskId,text(task.get("ticket_sn")),"OUT",action,"");
            logs.finish(logId,"UNKNOWN",secret.isEmpty()?responseBody:responseBody.replace(secret,"***"),error,System.currentTimeMillis()-start);
        }finally{db.jdbc.update("UPDATE wms_inbound_task SET lease_until=NULL WHERE id=? AND company_code=?",taskId,company);}
    }
    private void updateReply(String company,Map<String,Object> task,String action,WmsProtocol.Reply reply){db.tx().execute(s->{
        purchase.lockWmsTicket(company,id(task.get("ticket_id")));Map<String,Object> current=db.one("SELECT * FROM wms_inbound_task WHERE id=? AND company_code=? FOR UPDATE",task.get("id"),company);
        String state=text(current.get("dispatch_state"));
        if(action.equals("CREATE")&&state.equals("SENDING")){
            db.jdbc.update("UPDATE wms_inbound_task SET dispatch_state=?,external_order=?,last_error=? WHERE id=?",reply.success?"ACCEPTED":"FAILED",reply.externalOrder,reply.success?"":reply.message,task.get("id"));
            db.jdbc.update("UPDATE wms_tickets SET status_notify=?,time_notify=NOW() WHERE id=? AND UPPER(company_code)=?",reply.success?1:2,task.get("ticket_id"),company);
        }else if(reply.success&&action.equals("QUERY")&&!state.equals("CANCELED")){
            if(Arrays.asList("NEW","ACCEPT","PARTFULFILLED","FULFILLED","CLOSED").contains(reply.status))db.jdbc.update("UPDATE wms_inbound_task SET dispatch_state=CASE WHEN dispatch_state='CANCEL_PENDING' THEN dispatch_state ELSE 'ACCEPTED' END,external_order=?,last_error=? WHERE id=?",reply.externalOrder,"仓库状态："+reply.status+"；实收数量以收货回传为准",task.get("id"));
            if(reply.status.equals("CANCELED"))confirmCancel(company,current);
        }else if(action.equals("CANCEL")){
            // A successful cancel request is not proof of physical cancellation; query/callback confirms it.
            db.jdbc.update("UPDATE wms_inbound_task SET last_error=? WHERE id=?",reply.success?"取消申请已受理，等待仓库确认":reply.message,task.get("id"));
            if(reply.success&&reply.status.equals("CANCELED"))confirmCancel(company,current);
            if(!reply.success&&state.equals("CANCEL_PENDING"))db.jdbc.update("UPDATE wms_inbound_task SET dispatch_state='ACCEPTED' WHERE id=?",task.get("id"));
        }
        return null;
    });}
    private void confirmCancel(String company,Map<String,Object> task){
        require(db.jdbc.queryForObject("SELECT COUNT(*) FROM wms_receipt_line WHERE task_id=?",Long.class,task.get("id"))==0,"仓库取消与收货回传冲突，请核对仓库执行结果");
        db.jdbc.update("UPDATE wms_inbound_task SET dispatch_state='CANCELED',last_error='' WHERE id=?",task.get("id"));
        db.jdbc.update("UPDATE wms_tickets SET status_ticket=5,time_cancel=NOW() WHERE id=? AND UPPER(company_code)=?",task.get("ticket_id"),company);
        db.jdbc.update("UPDATE no_tickets n JOIN wms_tickets t ON t.relation_sn=n.no_sn AND UPPER(t.company_code)=UPPER(n.company_code) SET n.no_state=-1 WHERE t.id=? AND UPPER(t.company_code)=?",task.get("ticket_id"),company);
        purchase.recordWmsEvent(company,text(task.get("ticket_sn")),"仓库取消确认","仓库确认取消，已释放剩余可安排到货数量");
    }
    public String callback(String company,String callbackKey,Map<String,String> params,String body){
        Map<String,Object> config=db.one("SELECT * FROM wms_connection WHERE company_code=? AND callback_key=?",company,callbackKey);
        WmsProtocol p=protocol(text(config.get("provider")));long start=System.currentTimeMillis();
        long logId=logs.start(company,id(config.get("id")),null,"","IN","RECEIPT",body);
        try{
            WmsProtocol.Receipt receipt=p.callback(config,connections.secret(config),params,body);
            Map<String,Object> task=db.one("SELECT * FROM wms_inbound_task WHERE company_code=? AND connection_id=? AND ticket_sn=?",company,config.get("id"),receipt.ticketSn);
            db.jdbc.update("UPDATE wms_interaction_log SET task_id=?,ticket_sn=? WHERE id=?",task.get("id"),receipt.ticketSn,logId);
            accept(company,task,receipt);
            String reply=p.acknowledgement(true,"回传已接收，入账异步处理");logs.finish(logId,"SUCCESS",reply,"",System.currentTimeMillis()-start);return reply;
        }catch(Exception e){String error=e instanceof IllegalArgumentException?text(e.getMessage()):"回传处理失败，请重试";String reply=p.acknowledgement(false,error);logs.finish(logId,"FAILED",reply,error,System.currentTimeMillis()-start);return reply;}
    }
    public void accept(String company,Map<String,Object> task,WmsProtocol.Receipt receipt){db.tx().execute(s->{
        Map<String,Object> ticket=purchase.lockWmsTicket(company,id(task.get("ticket_id")));
        Map<String,Object> current=db.one("SELECT * FROM wms_inbound_task WHERE id=? AND company_code=? FOR UPDATE",task.get("id"),company);
        require(text(current.get("external_warehouse")).equals(receipt.warehouse)&&text(current.get("external_owner")).equals(receipt.owner),"回传仓库或货主与单据绑定不一致");
        require(receipt.messageId!=null&&!receipt.messageId.isEmpty()&&receipt.messageId.length()<=128,"回传缺少有效消息ID");
        String hash=QimenProtocol.hash("SHA-256",db.encode(receipt));
        List<Map<String,Object>> previous=db.jdbc.queryForList("SELECT * FROM wms_receipt_event WHERE task_id=? AND message_id=?",task.get("id"),receipt.messageId);
        if(!previous.isEmpty()){require(hash.equals(previous.get(0).get("payload_hash")),"相同消息ID的回传内容发生变化");return null;}
        require(!text(current.get("dispatch_state")).equals("PENDING"),"单据尚未下发，不能接收仓库回传");
        require(!text(current.get("dispatch_state")).equals("CANCELED"),"单据已取消，收货回传需核对");
        if("CANCELED".equals(receipt.status)){require(receipt.lines.isEmpty(),"取消回传不能包含收货明细");confirmCancel(company,current);db.insert("wms_receipt_event",map("company_code",company,"task_id",task.get("id"),"message_id",receipt.messageId,"payload_hash",hash,"final_receipt",0));return null;}
        require(Arrays.asList("ACCEPT","NEW","PARTFULFILLED","FULFILLED","CLOSED").contains(receipt.status),"仓库回传状态不支持自动处理，请核对");
        boolean receiving=Arrays.asList("PARTFULFILLED","FULFILLED","CLOSED").contains(receipt.status);
        require(receiving||receipt.lines.isEmpty(),"接单状态不能包含实收数量");
        require(receipt.totalLines==null||receipt.totalLines==receipt.lines.size(),"收货报文尚未包含完整明细，请合并后重传");
        List<Map<String,Object>> planned=db.jdbc.queryForList("SELECT * FROM wms_tickets_goods WHERE sn=? AND UPPER(company_code)=? ORDER BY id",task.get("ticket_sn"),company);
        Map<String,Map<String,Object>> bySku=new HashMap<>();for(Map<String,Object> line:planned)bySku.put(text(line.get("sku_sn")),line);
        Map<String,Long> added=new HashMap<>();
        for(WmsProtocol.Line l:receipt.lines){require(bySku.containsKey(l.sku),"回传包含未计划的SKU："+l.sku);require(text(l.owner).equals(receipt.owner),"商品明细货主不匹配");require(l.good>=0&&l.bad>=0,"实收数量不能为负数");require(text(l.batch).length()<=100,"收货批次编码过长");added.merge(l.sku,(long)l.good+l.bad,Math::addExact);}
        for(Map.Entry<String,Long> item:added.entrySet()){Map<String,Object> plan=bySku.get(item.getKey());require(id(plan.get("number_actually"))+item.getValue()<=id(plan.get("number_expected")),"累计实收超过计划数量："+item.getKey());}
        long event=db.insert("wms_receipt_event",map("company_code",company,"task_id",task.get("id"),"message_id",receipt.messageId,"payload_hash",hash,"final_receipt",receipt.complete?1:0));
        for(WmsProtocol.Line l:receipt.lines){Map<String,Object> plan=bySku.get(l.sku);if(l.good+l.bad==0)continue;String batch=text(l.batch);if(batch.isEmpty())batch=text(plan.get("batch_code"));
            db.insert("wms_receipt_line",map("company_code",company,"event_id",event,"task_id",task.get("id"),"ticket_line_id",plan.get("id"),"sku_sn",l.sku,"batch_code",batch,"good_qty",l.good,"bad_qty",l.bad));
            db.jdbc.update("UPDATE wms_tickets_goods SET number_zp=number_zp+?,number_cp=number_cp+?,number_actually=number_actually+?,number_dif_actually=number_expected-number_actually,inventory_is_handle=0,wms_actually_time=NOW() WHERE id=? AND UPPER(company_code)=?",l.good,l.bad,l.good+l.bad,plan.get("id"),company);
        }
        boolean complete=receiving&&(receipt.complete||text(current.get("receipt_state")).equals("COMPLETE"));
        String state=complete?"COMPLETE":receiving?"PARTIAL":text(current.get("receipt_state"));
        db.jdbc.update("UPDATE wms_inbound_task SET dispatch_state='ACCEPTED',receipt_state=?,external_order=?,last_error='' WHERE id=?",state,text(receipt.externalOrder).isEmpty()?current.get("external_order"):receipt.externalOrder,task.get("id"));
        db.jdbc.update("UPDATE wms_tickets SET status_notify=1,status_ticket=?,accept_callback_time=NOW(),inventory_status=CASE WHEN ? THEN 0 ELSE inventory_status END WHERE id=? AND UPPER(company_code)=?",complete?2:1,receiving,task.get("ticket_id"),company);
        purchase.recordWmsEvent(company,receipt.ticketSn,receiving?"仓库收货回传":"仓库受理回传","消息 "+receipt.messageId+"，状态 "+receipt.status);
        return null;
    });}
    public boolean post(String company,long ticketId){return db.tx().execute(s->{
        Map<String,Object> ticket=purchase.lockWmsTicket(company,ticketId);Map<String,Object> task=task(company,ticketId);require(task!=null,"没有真实入库任务");
        require(Arrays.asList("PARTIAL","COMPLETE").contains(text(task.get("receipt_state"))),"仓库尚未回传收货，不能入账");
        List<Map<String,Object>> lines=db.jdbc.queryForList("SELECT l.*,g.purchase_price FROM wms_receipt_line l JOIN wms_tickets_goods g ON g.id=l.ticket_line_id AND UPPER(g.company_code)=l.company_code WHERE l.task_id=? AND l.company_code=? AND l.posted<>1 ORDER BY l.id",task.get("id"),company);
        boolean success=true;
        for(Map<String,Object> line:lines){try{
            WmsInventoryBatch batch=new WmsInventoryBatch();batch.setCompanyCode(company);batch.setStoreCode(text(ticket.get("wmsSimulationCode")));batch.setSkuSn(text(line.get("sku_sn")));batch.setBatchCode(text(line.get("batch_code")));batch.setZpActualNumber((int)id(line.get("good_qty")));batch.setZpAvailableNumber((int)id(line.get("good_qty")));batch.setCpActualNumber((int)id(line.get("bad_qty")));batch.setCpAvailableNumber((int)id(line.get("bad_qty")));batch.setTransactionPrice(new BigDecimal(text(line.get("purchase_price"))));
            WmsInventoryBatchDto dto=new WmsInventoryBatchDto();dto.setRelationSn("WMS_RECEIPT_"+line.get("id"));dto.setWmsInventoryBatch(batch);R<Boolean> result=inventory.addInventory(dto,company);require(result!=null&&R.isSuccess(result)&&Boolean.TRUE.equals(result.getData()),"库存服务入账失败");
            db.jdbc.update("UPDATE wms_receipt_line SET posted=1,error_info='' WHERE id=? AND company_code=?",line.get("id"),company);
        }catch(Exception e){success=false;db.jdbc.update("UPDATE wms_receipt_line SET posted=2,error_info='库存服务入账失败，请重试' WHERE id=? AND company_code=?",line.get("id"),company);}}
        boolean finalReceipt="COMPLETE".equals(task.get("receipt_state"));
        for(Map<String,Object> g:db.jdbc.queryForList("SELECT id,number_actually FROM wms_tickets_goods WHERE sn=? AND UPPER(company_code)=?",ticket.get("sn"),company)){
            Map<String,Object> totals=db.one("SELECT COALESCE(SUM(CASE WHEN posted=1 THEN good_qty ELSE 0 END),0) good_qty,COALESCE(SUM(CASE WHEN posted=1 THEN bad_qty ELSE 0 END),0) bad_qty FROM wms_receipt_line WHERE task_id=? AND ticket_line_id=?",task.get("id"),g.get("id"));
            int state=id(totals.get("good_qty"))+id(totals.get("bad_qty"))==id(g.get("number_actually"))?2:3;
            db.jdbc.update("UPDATE wms_tickets_goods SET inventory_is_handle=?,error_info=? WHERE id=? AND UPPER(company_code)=?",state,state==3?"部分收货记录未入账":"",g.get("id"),company);
            db.jdbc.update("UPDATE no_tickets_goods n JOIN wms_tickets_goods g ON g.sku_sn=n.sku_sn AND UPPER(g.company_code)=UPPER(n.company_code) SET n.zp_number_actually=?,n.cp_number_actually=? WHERE g.id=? AND n.no_sn=? AND UPPER(n.company_code)=?",totals.get("good_qty"),totals.get("bad_qty"),g.get("id"),ticket.get("relationSn"),company);
        }
        db.jdbc.update("UPDATE wms_tickets SET inventory_status=? WHERE id=? AND UPPER(company_code)=?",success?(finalReceipt?2:0):3,ticketId,company);
        purchase.refreshWmsReceipt(company,ticket,finalReceipt&&success);
        if(!lines.isEmpty())purchase.recordWmsEvent(company,text(ticket.get("sn")),success?"真实收货入账成功":"真实收货入账异常","收货明细 "+lines.size()+" 条");
        return success;
    });}
    public void pollCompany(String company){
        db.jdbc.update("UPDATE wms_inbound_task SET dispatch_state='UNKNOWN',lease_until=NULL,last_error='下发进程中断，需查询仓库结果' WHERE company_code=? AND dispatch_state='SENDING' AND lease_until<NOW()",company);
        for(Long id:db.jdbc.queryForList("SELECT id FROM wms_inbound_task WHERE company_code=? AND dispatch_state='PENDING' AND next_attempt<=NOW() ORDER BY id LIMIT 10",Long.class,company))dispatch(company,id);
        for(Long ticket:db.jdbc.queryForList("SELECT t.ticket_id FROM wms_inbound_task t JOIN wms_tickets w ON w.id=t.ticket_id AND UPPER(w.company_code)=t.company_code WHERE t.company_code=? AND (EXISTS(SELECT 1 FROM wms_receipt_line l WHERE l.task_id=t.id AND l.posted=0) OR (t.receipt_state='COMPLETE' AND w.inventory_status=0)) ORDER BY t.id LIMIT 20",Long.class,company))post(company,ticket);
    }
}
