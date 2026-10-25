package com.oms.order.service;

import javax.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.ruoyi.common.core.constant.SecurityConstants;
import java.time.LocalDateTime;
import java.util.*;
import static com.oms.order.service.OrderValues.*;
import static com.oms.order.service.ExchangeService.*;

/** Explicit return UI commands; logistics and review records never create inventory facts. */
@Service public class ExchangeReturnWorkspace {
    @Resource private OrderRepository repository;
    @Resource private ExchangeService exchanges;
    @Resource private ExchangeReturnClient returns;
    private TransactionTemplate tx(){return new TransactionTemplate(new DataSourceTransactionManager(repository.db().getDataSource()));}
    public Map<String,Object> report(String company,long id){Map<String,Object> e=exchanges.row(company,id,false);Map<String,Object> result=map("required",!ExchangeFulfillment.unshipped(e),"logistics",parseList(e.get("return_logistics")),"review",e.get("return_review")==null?Collections.emptyMap():object(e.get("return_review")),"platform_logistics",map("carrier",text(object(e.get("payload")),"buyer_logistic_name"),"tracking_no",text(object(e.get("payload")),"buyer_logistic_no")));if(!ExchangeFulfillment.unshipped(e)&&!text(e,"return_sn").isEmpty())result.put("actual",data(returns.detail(company,id,SecurityConstants.INNER)));return result;}
    @SuppressWarnings("unchecked") private List<Map<String,Object>> parseList(Object raw){if(raw==null)return Collections.emptyList();try{return raw instanceof List?(List<Map<String,Object>>)raw:JSON.readValue(raw.toString(),List.class);}catch(Exception failure){throw new IllegalArgumentException("寄回物流记录读取失败，请核对");}}
    private Map<String,Object> editable(String company,long id,Map<String,Object> input){Map<String,Object> e=exchanges.row(company,id,true);require(number(e,"control_version")==number(input,"control_version"),"申请已变化，请刷新");require(!ExchangeFulfillment.unshipped(e)&&e.get("linked_exchange_id")==null,"当前申请无需单独退回处理");require(!Arrays.asList("CANCELED","CLOSED","REJECTED","COMPLETED").contains(text(e,"state")),"申请已结束，不能改写记录");return e;}
    public Map<String,Object> logistics(String company,long id,Map<String,Object> input,String actor){
        require(input.get("items") instanceof List&&((List<?>)input.get("items")).size()<=20,"最多登记20条寄回物流");List<Map<String,Object>> items=new ArrayList<>();Set<String> keys=new HashSet<>();
        for(Object value:(List<?>)input.get("items")){Map<String,Object> row=object(value);String carrier=text(row,"carrier").trim(),tracking=text(row,"tracking_no").trim(),at=text(row,"shipped_at"),note=text(row,"note");require(!carrier.isEmpty()&&carrier.length()<=100&&!tracking.isEmpty()&&tracking.length()<=100&&note.length()<=500,"请填写承运商和运单号，备注最多500字");require(keys.add(carrier+"/"+tracking),"寄回运单重复");if(!at.isEmpty())time(at);items.add(map("carrier",carrier,"tracking_no",tracking,"shipped_at",at,"note",note,"source","MANUAL","actor",actor));}
        tx().execute(s->{Map<String,Object> e=editable(company,id,input);repository.db().update("UPDATE oms_exchange SET return_logistics=?,control_version=control_version+1 WHERE company_code=? AND id=?",encode(items),company,id);exchanges.event(company,id,"RETURN_LOGISTICS",text(e,"state"),number(e,"control_version")+1,"客服登记寄回物流；不代表仓库收货，不改变库存",map("items",items),actor);return null;});return exchanges.detail(company,id);
    }
    public Map<String,Object> action(String company,long id,String action,Map<String,Object> input,String actor){
        require(Arrays.asList("query","retry","posting").contains(action),"退回操作无效");tx().execute(s->{Map<String,Object> e=editable(company,id,input);require(!text(e,"return_sn").isEmpty(),"退回单尚未生成");return null;});Map<String,Object> existing=data(returns.detail(company,id,SecurityConstants.INNER));require("query".equals(action)||!Boolean.TRUE.equals(existing.get("shared_after_sale")),"请从共同退回包裹核对全部关联退款和换货后处理");Map<String,Object> actual=data(returns.action(company,id,action,SecurityConstants.INNER));
        tx().execute(s->{Map<String,Object> e=exchanges.row(company,id,true);repository.db().update("UPDATE oms_exchange SET return_result=?,control_version=control_version+1 WHERE company_code=? AND id=?",encode(returnSnapshot(actual)),company,id);exchanges.event(company,id,"RETURN_ACTION",text(e,"state"),number(e,"control_version")+1,"已核对原退回任务："+action+"；不会新建退回单",Collections.emptyMap(),actor);return null;});return exchanges.detail(company,id);
    }
    public Map<String,Object> document(String company,long id,long document,String action,Map<String,Object> input,String actor){
        require(Arrays.asList("query","retry","posting","rearrange","review").contains(action),"退回单操作无效");
        String lease=token();tx().execute(s->{Map<String,Object> e=exchanges.row(company,id,true);require(number(e,"control_version")==number(input,"control_version")&&!ExchangeFulfillment.unshipped(e)&&e.get("linked_exchange_id")==null,"申请已变化或不支持此退回操作");require(e.get("lease_until")==null||!time(text(e,"lease_until")).isAfter(LocalDateTime.now()),"换货任务正在执行，请稍后核对");
            if("retry".equals(action))require(number(e,"fulfillment_hold")==0&&!ExchangeControlService.terminal(e),"换货已挂起或结束，禁止重发创建");
            if("rearrange".equals(action)){require(!text(input,"reason").trim().isEmpty()&&text(input,"reason").length()<=500,"请填写重新安排依据");require(number(e,"fulfillment_hold")==1&&"HOLD".equals(text(e,"control_state"))&&!ExchangeControlService.terminal(e),"请先完成撤回并保持挂起，再重新安排退回");}
            if("review".equals(action)&&"ACCEPT".equals(text(input,"decision")))require(exchanges.eligible(company,e)>=number(e,"requested_quantity"),"原发货来源额度已被其他换货占用，请先核对来源，不能重复接受退回");repository.db().update("UPDATE oms_exchange SET lease_token=?,lease_until=DATE_ADD(NOW(),INTERVAL 180 SECOND) WHERE company_code=? AND id=?",lease,company,id);return null;
        });
        try{
        Map<String,Object> existing=data(returns.detail(company,id,SecurityConstants.INNER));require("query".equals(action)||!Boolean.TRUE.equals(existing.get("shared_after_sale")),"请从共同退回包裹核对全部关联退款和换货后处理");
        Map<String,Object> body=new LinkedHashMap<>(input);body.put("actor",actor);Map<String,Object> actual=data(returns.document(company,id,document,action,body,SecurityConstants.INNER));
        tx().execute(s->{Map<String,Object> e=exchanges.row(company,id,true);require(lease.equals(text(e,"lease_token")),"单据操作租约已变化，请核对原结果");repository.db().update("UPDATE oms_exchange SET return_result=?,return_sn=?,state=IF(state='CANCELED' AND ?>0,'CLOSED',state),control_state=IF(control_state='CANCELED' AND ?>0,'CLOSED',control_state),control_version=control_version+1 WHERE company_code=? AND id=?",encode(returnSnapshot(actual)),actual.get("return_sn"),number(actual,"accepted_quantity"),number(actual,"accepted_quantity"),company,id);exchanges.event(company,id,"RETURN_DOCUMENT",text(e,"state"),number(e,"control_version")+1,"退回单操作："+action+"；原单、实收及库存事实保留",map("document_id",document,"review_id",input.get("review_id"),"decision",input.get("decision"),"reason",input.get("reason"),"evidence",input.get("evidence")),actor);return null;});return exchanges.detail(company,id);
        }finally{repository.db().update("UPDATE oms_exchange SET lease_token=NULL,lease_until=NULL WHERE company_code=? AND id=? AND lease_token=?",company,id,lease);}
    }
    public Map<String,Object> link(String company,long id,Map<String,Object> input,String actor){
        long platformId=number(input,"platform_exchange_id");String reason=text(input,"reason").trim();require(id!=platformId&&platformId>0&&!reason.isEmpty()&&reason.length()<=500,"请选择平台换货申请并填写核对依据");
        require(Boolean.TRUE.equals(input.get("confirmed")),"请明确确认买家、实际来源、目标商品和数量均已核对一致");
        tx().execute(s->{List<Map<String,Object>> rows=repository.db().query("SELECT * FROM oms_exchange WHERE company_code=? AND id IN (?,?) ORDER BY id FOR UPDATE",ROW,company,id,platformId);require(rows.size()==2,"申请不存在或不属于当前公司");Map<String,Object> manual=rows.get(0),platform=rows.get(1);if(number(manual,"id")!=id){Map<String,Object> temp=manual;manual=platform;platform=temp;}
            require("INTERNAL".equals(text(manual,"source_kind"))&&!ExchangeFulfillment.unshipped(manual)&&"PLATFORM".equals(text(platform,"source_kind")),"只能将平台申请关联到退回换出的协商换货单");require(number(manual,"control_version")==number(input,"control_version"),"协商换货单已变化，请刷新");require(manual.get("approved_version")!=null&&!Arrays.asList("CANCELED","REJECTED").contains(text(manual,"state")),"请先审核有效协商方案");
            require(number(manual,"order_id")==number(platform,"order_id")&&number(manual,"channel_id")==number(platform,"channel_id")&&text(manual,"platform_oid").equals(text(platform,"platform_oid"))&&number(manual,"requested_quantity")==number(platform,"requested_quantity"),"原订单、店铺、平台子单或申请数量不一致");require(number(platform,"source_shipment_line_id")==0||number(platform,"source_shipment_line_id")==number(manual,"source_shipment_line_id"),"实际退回来源不一致");
            require(!Arrays.asList("COMPLETED","CANCELED","CLOSED","REJECTED").contains(text(platform,"state"))&&!text(platform,"problem_codes").contains("SOURCE_CONFLICT"),"平台申请已结束或存在来源冲突，不能直接关联");require(text(platform,"target_sku").isEmpty()||text(platform,"target_sku").equals(text(manual,"target_sku")),"目标商品不一致");require(platform.get("approved_version")==null&&platform.get("stock_request")==null&&platform.get("fulfillment_line_id")==null&&text(platform,"return_sn").isEmpty()&&"NONE".equals(text(platform,"platform_operation"))&&platform.get("lease_token")==null,"平台申请已执行或正在处理，不能直接关联");require(platform.get("linked_exchange_id")==null||number(platform,"linked_exchange_id")==id,"平台申请已关联其他处理单");
            require(repository.db().queryForObject("SELECT COUNT(*) FROM oms_exchange WHERE company_code=? AND linked_exchange_id=? AND id<>?",Long.class,company,id,platformId)==0,"该协商单已关联其他平台申请");
            repository.db().update("UPDATE oms_exchange SET linked_exchange_id=?,state='BLOCKED',problem_codes='LINKED_HANDLING',problem_message='已关联协商换货，平台操作需人工核对；OMS不重复履约',next_attempt=NULL,control_version=control_version+1 WHERE company_code=? AND id=?",id,company,platformId);repository.db().update("UPDATE oms_exchange SET control_version=control_version+1 WHERE company_code=? AND id=?",company,id);
            Map<String,Object> evidence=map("manual_exchange_id",id,"platform_exchange_id",platformId,"platform_dispute_id",platform.get("dispute_id"),"reason",reason,"actor",actor);exchanges.event(company,id,"LINK_PLATFORM",text(manual,"state"),number(manual,"control_version")+1,"平台申请已核对关联，沿用协商单实际履约；平台操作由人工在平台核对",evidence,actor);exchanges.event(company,platformId,"LINK_PLATFORM","BLOCKED",number(platform,"control_version")+1,"已关联协商换货，不重复预占、入库或发货",evidence,actor);return null;
        });return exchanges.detail(company,id);
    }
}
