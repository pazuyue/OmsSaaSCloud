package com.oms.order.service;
import com.oms.common.api.OrderStockClient;
import com.oms.common.model.dto.OrderStockCommand;
import com.ruoyi.common.core.constant.SecurityConstants;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.annotation.Resource;
import java.util.*;
import static com.oms.order.service.OrderValues.*;
import static com.oms.order.service.ExchangeService.*;

/** Exchange quantities never overwrite original trade lines or original outbound facts. */
@Service public class ExchangeFulfillment {
    @Resource private OrderRepository repository;
    @Resource private ExchangeService exchanges;
    @Resource private OrderCatalog catalog;
    @Resource private OrderStockClient inventory;
    @Resource private OrderWarehouseClient warehouse;
    @Resource private OrderCutoffService cutoff;
    @Resource private OrderImmediateWork immediate;
    private TransactionTemplate tx(){return new TransactionTemplate(new DataSourceTransactionManager(repository.db().getDataSource()));}
    static boolean unshipped(Map<String,Object> e){return "UNSHIPPED".equals(text(e,"fulfillment_mode"));}
    static final String MODE_SQL="fulfillment_mode";
    static void planUnchanged(Map<String,Object> e){require(number(e,"fulfillment_hold")==0&&!ExchangeControlService.terminal(e),"换货已挂起、正在截单或已结束，禁止新增履约");require(e.get("linked_exchange_id")==null,"本申请已关联协商换货，请在关联处理单查看履约，平台操作需人工核对");require(e.get("approved_snapshot")!=null,"请先人工审核方案");Map<String,Object> p=object(e.get("approved_snapshot"));require(number(p,"quantity")==number(e,"requested_quantity")&&text(p,"platform_oid").equals(text(e,"platform_oid"))&&text(p,"target_description").equals(text(e,"target_description"))&&text(p,"target_sku").equals(text(e,"target_sku")),"平台申请内容与审核方案不一致，请人工核对");require(!text(e,"problem_codes").contains("SOURCE_CONFLICT")&&!text(e,"problem_codes").contains("APPLICATION_CHANGED")&&!text(e,"problem_codes").contains("LINKED_PLATFORM_REVIEW"),"平台申请已变化，请人工核对原执行结果");}
    private void sourceReady(String company,Map<String,Object> e){if(unshipped(e)){Map<String,Object> source=repository.raw(company,number(e,"original_platform_order_id"));require(text(object(e.get("approved_snapshot")),"original_source_hash").equals(text(source,"payload_hash")),"原订单已有新版本，请重新核对换品方案与订单更新，保留已执行事实");}}
    private OrderStockCommand command(Map<String,Object> e){OrderStockCommand c=new OrderStockCommand();c.setChannelId(number(e,"channel_id"));c.setPlatformTid(ExchangeRounds.reference(e));Map<String,Object> frozen=object(e.get("stock_request"));c.setAllowPublicStock(number(frozen,"allow_public_stock")==1);OrderStockCommand.Line line=new OrderStockCommand.Line();line.setPlatformOid(text(frozen,"platform_oid"));line.setSkuSn(text(frozen,"sku_sn"));line.setQuantity(Math.toIntExact(number(frozen,"quantity")));c.getLines().add(line);return c;}
    void release(String company,long id){Map<String,Object> e=exchanges.row(company,id,false);if(e.get("stock_request")==null)return;require(e.get("fulfillment_order_id")==null,"已有换出履约，请先核对仓库与换出占用，不能直接释放");data(inventory.execute("cancel",company,command(e),SecurityConstants.INNER));}
    boolean reserve(String company,long id){Map<String,Object> e=tx().execute(s->{Map<String,Object> row=exchanges.row(company,id,true);planUnchanged(row);sourceReady(company,row);if(row.get("stock_request")==null){Map<String,Object> config=repository.config(company,number(row,"channel_id"));Map<String,Object> request=map("reference",ExchangeRounds.reference(row),"quantity",ExchangeRounds.quantityOf(row),"sku_sn",row.get("target_sku"),"platform_oid",row.get("platform_oid"),"allow_public_stock",config.get("allow_public_stock"));repository.db().update("UPDATE oms_exchange SET stock_request=? WHERE company_code=? AND id=?",encode(request),company,id);row.put("stock_request",encode(request));}return row;});OrderStockCommand c=command(e);data(inventory.execute("register",company,c,SecurityConstants.INNER));List<Map<String,Object>> receipts=data(inventory.execute("reserve",company,c,SecurityConstants.INNER));require(receipts.size()==1&&text(receipts.get(0),"sku_sn").equals(text(e,"target_sku")),"换出库存回执身份不一致");boolean full=number(receipts.get(0),"reserved_quantity")==ExchangeRounds.quantityOf(e);repository.db().update("UPDATE oms_exchange SET stock_receipt=?,state=?,problem_codes=?,problem_message=? WHERE company_code=? AND id=?",encode(receipts.get(0)),full?"APPROVED":"WAIT_STOCK",full?"":"STOCK_SHORTAGE",full?"":"换出库存不足；申请数量保持不变，请关注平台处理期限",company,id);repository.db().update("UPDATE oms_exchange_round SET state=? WHERE company_code=? AND exchange_id=? AND round_no=?",full?"APPROVED":"WAIT_STOCK",company,id,ExchangeRounds.numberOf(e));return full;}
    public Map<String,Object> create(String company,Map<String,Object> input,String actor){
        String reason=text(input,"reason").trim(),request=text(input,"request_key"),mode=text(input,"fulfillment_mode").isEmpty()?"UNSHIPPED":text(input,"fulfillment_mode"),phase=text(input,"source_phase");
        require(Arrays.asList("UNSHIPPED","RETURN").contains(mode),"请选择未发货替换或退回后换出");if(phase.isEmpty()&&"UNSHIPPED".equals(mode))phase="DURING_SALE";require(Arrays.asList("DURING_SALE","AFTER_SALE").contains(phase),"请选择售中或售后换货");require(!"UNSHIPPED".equals(mode)||"DURING_SALE".equals(phase),"未发货替换属于售中换品");
        require(!reason.isEmpty()&&reason.length()<=500&&request.matches("[A-Za-z0-9_-]{8,64}"),"请填写协商依据并使用有效请求标识");long orderId=number(input,"order_id"),qty=number(input,"quantity"),selectedLine=number(input,"order_line_id"),selectedShipment=number(input,"source_shipment_line_id");String oid=text(input,"platform_oid"),sku=text(input,"target_sku").trim();require(qty>0&&qty<=100000000&&!sku.isEmpty(),"换货 SKU 或数量无效");
        List<Map<String,Object>> chosen=catalog.goods(company,Collections.singletonList(sku)).getOrDefault(sku,Collections.emptyList());require(chosen.size()==1,"目标 SKU 不存在或不唯一");require(number(chosen.get(0),"is_fd")==0&&number(chosen.get(0),"is_package")==0,"暂不支持福袋或套装换出，请选择普通 SKU");
        String sn="HHI"+hash(company+"/"+request).substring(0,32),digest=hash(canonical(input)),selectedPhase=phase;
        long id=tx().execute(s->{List<Map<String,Object>> replay=repository.db().query("SELECT id,payload_hash FROM oms_exchange WHERE company_code=? AND exchange_sn=? FOR UPDATE",ROW,company,sn);if(!replay.isEmpty()){require(digest.equals(text(replay.get(0),"payload_hash")),"同一创建请求内容不一致");return number(replay.get(0),"id");}
            Map<String,Object> o=repository.db().query("SELECT * FROM oms_order WHERE company_code=? AND id=? FOR UPDATE",ROW,company,orderId).stream().findFirst().orElseThrow(()->new IllegalArgumentException("原订单不存在或无权访问"));require("SALE".equals(text(o,"business_type"))&&!Arrays.asList("VOIDED","VOIDING").contains(text(o,"status")),"原订单不支持创建协商换货");
            Map<String,Object> l;
            if("RETURN".equals(mode)){
                require(selectedShipment>0,"请选择已实际出库的发货明细");
                List<Map<String,Object>> rows=repository.db().query("SELECT l.* FROM oms_order_shipment_line sl JOIN oms_order_shipment sh ON sh.company_code=sl.company_code AND sh.id=sl.shipment_id JOIN oms_order_line l ON l.company_code=sl.company_code AND l.id=sl.order_line_id WHERE sl.company_code=? AND sh.order_id=? AND sl.id=? AND sl.shipped_quantity>=? FOR UPDATE",ROW,company,orderId,selectedShipment,qty);require(rows.size()==1,"所选发货明细不存在、未出库或数量不足");l=rows.get(0);require(oid.equals(text(l,"platform_oid"))&&(selectedLine==0||selectedLine==number(l,"id")),"所选退回来源与原商品不一致");
                require(!text(input,"return_store").isEmpty()&&input.get("replacement_recipient") instanceof Map,"请填写退回仓及换出收货信息");
            }else{
                require("WAIT_ALLOCATION".equals(text(o,"status")),"原订单不支持创建未发货换品");List<Map<String,Object>> rows=repository.db().query("SELECT * FROM oms_order_line WHERE company_code=? AND order_id=? AND platform_oid=? AND business_type='SALE' AND (?=0 OR id=?) AND quantity>cancelled_quantity FOR UPDATE",ROW,company,orderId,oid,selectedLine,selectedLine);require(rows.size()==1,"请选择唯一的未发货商品履约明细");l=rows.get(0);
                long shipped=repository.db().queryForObject("SELECT COALESCE(SUM(shipped_quantity),0) FROM oms_order_shipment_line WHERE company_code=? AND order_line_id=?",Long.class,company,l.get("id"));require(qty<=number(l,"quantity")-number(l,"cancelled_quantity")-shipped,"换品数量超过原子单未发数量");
                long active=repository.db().queryForObject("SELECT COUNT(*) FROM oms_exchange WHERE company_code=? AND order_id=? AND ("+MODE_SQL+")='UNSHIPPED' AND state NOT IN ('CANCELED','REJECTED','COMPLETED') AND fulfillment_line_id IS NULL",Long.class,company,orderId);require(active==0,"原订单已有未完成协商换品，请先核对原申请");
            }
            long created=repository.insert("INSERT INTO oms_exchange(company_code,channel_id,exchange_sn,source_kind,platform_tid,platform_oid,source_modified,payload_hash,payload,requested_quantity,original_sku,target_sku,target_description,order_id,order_line_id,original_platform_order_id,actor,fulfillment_mode,source_phase,source_shipment_line_id,return_store) VALUES (?,?,?,'INTERNAL',?,?,NOW(),?,?,?,?,?,?,?,?,?,?,?,?,?,?)",company,o.get("channel_id"),sn,o.get("platform_tid"),oid,digest,encode(input),qty,l.get("sku_sn"),sku,sku,orderId,l.get("id"),o.get("platform_order_id"),actor,mode,selectedPhase,"RETURN".equals(mode)?selectedShipment:null,text(input,"return_store"));
            if("UNSHIPPED".equals(mode))repository.db().update("UPDATE oms_order SET manual_hold=1,hold_by=IF(hold_reason='',?,hold_by),hold_reason=IF(hold_reason='',?,hold_reason),hold_at=COALESCE(hold_at,NOW()),control_version=control_version+1 WHERE company_code=? AND id=?",actor,"协商换品#"+created,company,orderId);
            exchanges.event(company,created,"CREATE","NEEDS_REVIEW",1,"UNSHIPPED".equals(mode)?"协商换品已创建，原订单暂停新增履约":"协商换货已创建，待审核退回与换出方案；未调整原销售履约或库存",map("reason",reason,"fulfillment_mode",mode,"source_phase",selectedPhase),actor);return created;
        });return map("id",id);
    }
    void internal(String company,long id){Map<String,Object> e=exchanges.row(company,id,false);planUnchanged(e);require("INTERNAL".equals(text(e,"source_kind")),"不是协商换品");if(e.get("fulfillment_line_id")!=null){
        long shipped=repository.db().queryForObject("SELECT COALESCE(SUM(l.shipped_quantity),0) FROM oms_order_shipment_line l JOIN oms_order_shipment s ON s.company_code=l.company_code AND s.id=l.shipment_id WHERE s.company_code=? AND s.exchange_id=?",Long.class,company,id);
        long packed=repository.db().queryForObject("SELECT COALESCE(SUM(p.quantity),0) FROM oms_order_shipment_package p JOIN oms_order_shipment s ON s.company_code=p.company_code AND s.id=p.shipment_id WHERE s.company_code=? AND s.exchange_id=?",Long.class,company,id);
        long effective=repository.db().queryForObject("SELECT quantity-cancelled_quantity FROM oms_order_line WHERE company_code=? AND id=?",Long.class,company,e.get("fulfillment_line_id"));if((effective==0||shipped==effective&&packed==shipped)&&repository.db().update("UPDATE oms_exchange SET state='COMPLETED',stage='FINISHED',next_attempt=NULL,control_version=control_version+1 WHERE company_code=? AND id=? AND state<>'COMPLETED'",company,id)==1)exchanges.event(company,id,"INTERNAL_COMPLETED","COMPLETED",number(e,"control_version")+1,"协商换品已出库并完整关联包裹，或已被后续换品替代；平台发货沿用原订单",map("shipped_quantity",shipped,"packaged_quantity",packed),"履约核对");return;}
        sourceReady(company,e);if(e.get("cutoff_id")==null){Map<String,Object> o=repository.db().query("SELECT * FROM oms_order WHERE company_code=? AND id=?",ROW,company,e.get("order_id")).get(0);cutoff.request(company,number(e,"order_id"),map("control_version",o.get("control_version"),"mode","REPLAN","reason","协商换品#"+id+"撤回原未发货商品"),text(e,"review_by"));Long cutoffId=repository.db().queryForObject("SELECT cutoff_id FROM oms_order WHERE company_code=? AND id=?",Long.class,company,e.get("order_id"));repository.db().update("UPDATE oms_exchange SET cutoff_id=?,stage='WITHDRAW' WHERE company_code=? AND id=?",cutoffId,company,id);immediate.cutoffReady(company,number(e,"order_id"));return;}
        cutoff.process(company,number(e,"order_id"));Map<String,Object> operation=repository.db().query("SELECT state FROM oms_order_cutoff WHERE company_code=? AND id=?",ROW,company,e.get("cutoff_id")).get(0);if(!"COMPLETED".equals(text(operation,"state")))return;
        Map<String,Object> reduction=map("request_id","CFEX"+id,"mode","REDUCE","channel_id",e.get("channel_id"),"platform_tid",sourceReference(company,e),"allocations",Collections.emptyList(),"demands",Collections.singletonList(map("platform_oid",e.get("platform_oid"),"quantity",e.get("requested_quantity"))));Map<String,Object> receipt=data(warehouse.adjust(company,reduction,SecurityConstants.INNER));
        tx().execute(s->{Map<String,Object> locked=exchanges.row(company,id,true);planUnchanged(locked);for(Object line:(List<?>)receipt.get("lines")){Map<String,Object> l=object(line);if(!text(l,"platform_oid").equals(text(e,"platform_oid")))continue;repository.db().update("UPDATE oms_order_line SET cancelled_quantity=?,reserved_quantity=?,inventory_reason='协商换品终止原未发需求' WHERE company_code=? AND id=?",l.get("cancelled_quantity"),l.get("reserved_quantity"),company,e.get("order_line_id"));}repository.db().update("UPDATE oms_order SET review_status='PENDING',review_decision='MANUAL_REQUIRED',review_reason='协商换品后剩余需求待审核',review_by='',review_at=NULL,review_snapshot=NULL,control_version=control_version+1 WHERE company_code=? AND id=?",company,e.get("order_id"));return null;});
        if(reserve(company,id))materialize(company,id);
    }
    private String sourceReference(String company,Map<String,Object> e) {
        Map<String,Object> line=repository.db().query("SELECT stock_reference FROM oms_order_line WHERE company_code=? AND id=?",ROW,company,e.get("order_line_id")).get(0);
        return text(line,"stock_reference").isEmpty()?text(e,"platform_tid"):text(line,"stock_reference");
    }

    /** Creates immutable fulfillment lines under the original SO, never another SO or a platform trade. */
    long materialize(String company,long id) {
        Map<String,Object> before=exchanges.row(company,id,false);
        if(before.get("fulfillment_line_id")!=null)return number(before,"order_id");
        String sku=text(before,"target_sku");
        List<Map<String,Object>> goods=catalog.goods(company,Collections.singletonList(sku)).getOrDefault(sku,Collections.emptyList());
        require(goods.size()==1,"目标商品已失效，请核对原库存需求");require(number(goods.get(0),"is_fd")==0&&number(goods.get(0),"is_package")==0,"换出商品已变为套装或福袋，请核对原方案");
        return tx().execute(s->{
            Map<String,Object> e=exchanges.row(company,id,true);planUnchanged(e);sourceReady(company,e);
            if(e.get("fulfillment_line_id")!=null)return number(e,"order_id");
            Map<String,Object> stock=object(e.get("stock_receipt"));
            require(number(stock,"reserved_quantity")==ExchangeRounds.quantityOf(e),"换出商品尚未完成逻辑预占");
            Map<String,Object> original=repository.db().query("SELECT * FROM oms_order WHERE company_code=? AND id=? FOR UPDATE",ROW,company,e.get("order_id")).get(0);
            boolean internal=unshipped(e);
            String reference=ExchangeRounds.reference(e);
            long line=repository.insert("INSERT INTO oms_order_line(company_code,order_id,platform_oid,sku_sn,goods_name,quantity,payment,reserved_quantity,business_type,exchange_id,stock_reference,source_line_id,source_shipment_line_id,fulfillment_round) VALUES (?,?,?,?,?,?,0,?,?,?,?,?,?,?)",company,e.get("order_id"),e.get("platform_oid"),sku,text(goods.get(0),"goods_name"),ExchangeRounds.quantityOf(e),stock.get("reserved_quantity"),internal?"SALE":"EXCHANGE",id,reference,e.get("order_line_id"),e.get("source_shipment_line_id"),ExchangeRounds.numberOf(e));
            repository.db().update("UPDATE oms_exchange SET fulfillment_order_id=order_id,fulfillment_line_id=?,stage='REPLACEMENT',state='PROCESSING',control_version=control_version+1 WHERE company_code=? AND id=?",line,company,id);
            repository.db().update("UPDATE oms_exchange_round SET fulfillment_line_id=?,state='PROCESSING' WHERE company_code=? AND exchange_id=? AND round_no=?",line,company,id,ExchangeRounds.numberOf(e));
            if(internal) {
                repository.db().update("UPDATE oms_order SET manual_hold=IF(hold_reason=?,0,manual_hold),hold_by=IF(hold_reason=?,'',hold_by),hold_at=IF(hold_reason=?,NULL,hold_at),hold_reason=IF(hold_reason=?,'',hold_reason),status='WAIT_ALLOCATION',inventory_status='RESERVED',allocation_status=IF(allocation_quantity>0,'PART','NONE'),control_version=control_version+1 WHERE company_code=? AND id=?","协商换品#"+id,"协商换品#"+id,"协商换品#"+id,"协商换品#"+id,company,e.get("order_id"));
                repository.db().update("UPDATE oms_exchange_update_link SET state='APPLIED',applied_at=NOW() WHERE company_code=? AND exchange_id=? AND state='LINKED'",company,id);
                new OrderUpdateService(repository).refreshProjection(company,number(e,"original_platform_order_id"));
                Map<String,Object> totals=repository.db().query("SELECT SUM(quantity-cancelled_quantity) required,SUM(reserved_quantity) reserved FROM oms_order_line WHERE company_code=? AND order_id=? AND business_type='SALE'",ROW,company,e.get("order_id")).get(0);
                repository.db().update("UPDATE oms_order SET inventory_status=? WHERE company_code=? AND id=?",number(totals,"required")==number(totals,"reserved")?"RESERVED":number(totals,"reserved")>0?"PART_RESERVED":"WAIT_RESERVE",company,e.get("order_id"));
                repository.autoAllocationChanged(company,number(e,"order_id"),"未发货换品方案生效，原 SO 继续发货");
            }
            repository.log(company,number(e,"original_platform_order_id"),"EXCHANGE_FULFILLMENT","SUCCESS","换货#"+id+(internal?"新增原订单履约明细，原 SO 继续发货":"新增换货单换出明细，出入库归本次换货单"),text(e,"review_by"));
            exchanges.event(company,id,"REPLACEMENT_CREATE","PROCESSING",number(e,"control_version")+1,internal?"未发货换品生效，请从原订单审核及分仓发货":"换出方案生效，请在换货单分仓，出入库单据归本次换货单",map("order_id",e.get("order_id"),"fulfillment_line_id",line,"stock_reference",reference),text(e,"review_by"));
            return number(e,"order_id");
        });
    }
}
