package com.oms.supplychain.service.wms;
import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import java.util.*;
@Service public class RefundReturnService extends WarehouseReturnService {
    @Resource private WmsStore store;
    @Resource private RefundParcels parcels;
    @Resource private SharedRefundDocuments shared;
    @Resource private SharedReturnParcels common;
    Map<String,Object> sourceDetail(String c,long plan){return super.detail(c,plan);}
    @Override public Map<String,Object> detail(String c,long plan){Map<String,Object> result=super.detail(c,plan);if(result.get("shared_member_id")!=null)return common.decorate(c,"REFUND",plan,result);return shared==null?result:shared.decorate(c,plan,result);}
    @Override public Map<String,Object> prepare(String c,long plan,Map<String,Object> input){List<Map<String,Object>> old=store.jdbc.queryForList("SELECT shared_member_id FROM wms_refund_return WHERE company_code=? AND plan_id=? ORDER BY generation DESC LIMIT 1",c,plan);if(!old.isEmpty()&&old.get(0).get("shared_member_id")!=null)return detail(c,plan);return shared!=null&&shared.bound(c,plan)!=null?shared.prepare(c,plan,input,this):super.prepare(c,plan,input);}
    @Override public Map<String,Object> documentAction(String c,long plan,long document,String action,Map<String,Object> input){
        Map<String,Object> current=store.one("SELECT shared_member_id FROM wms_refund_return WHERE company_code=? AND id=? AND plan_id=?",c,document,plan);if(current.get("shared_member_id")!=null){common.action(c,"REFUND",plan,action,input);return detail(c,plan);}
        if(shared!=null&&shared.bound(c,plan)!=null){Map<String,Object> source=store.one("SELECT * FROM wms_refund_return WHERE company_code=? AND id=? AND plan_id=?",c,document,plan);return shared.action(c,plan,action,input,this);}return super.documentAction(c,plan,document,action,input);
    }
    @Override void accept(String c,Map<String,Object> task,WmsProtocol.Receipt r,long log){if(parcels==null){super.accept(c,task,r,log);return;}parcels.direct(c,task,r,log,()->super.accept(c,task,r,log));}
    void acceptAssigned(String c,Map<String,Object> task,WmsProtocol.Receipt r,long log){acceptLocked(c,store.one("SELECT * FROM wms_refund_return WHERE company_code=? AND id=? FOR UPDATE",c,task.get("id")),r,log,true);}
    void postAssigned(String c,long plan){post(c,plan);}
    @Override public String callback(String c,String key,Map<String,String> params,String body){String sn=QimenReturnProtocol.documentSn(body);if(sn.startsWith("THKX"))return common.callback(c,key,params,body);return (sn.startsWith("RPK")||(shared!=null&&shared.owns(c,sn)))?parcels.callback(c,key,params,body,this):super.callback(c,key,params,body);}
    public RefundReturnService(){super(Kind.REFUND);}
    public List<Map<String,Object>> pending(String company){return store.jdbc.queryForList("SELECT id,plan_id,progress_revision FROM wms_refund_return WHERE company_code=? AND notify_pending=1 ORDER BY id LIMIT 20",company);}
    public void acknowledge(String company,long plan,Map<String,Object> input){long id=WmsStore.id(input.get("id")),revision=WmsStore.id(input.get("progress_revision"));WmsStore.require(id>0&&revision>=0,"进度标识无效");store.jdbc.update("UPDATE wms_refund_return SET progress_ack=GREATEST(progress_ack,?),notify_pending=IF(progress_revision<=?,0,1) WHERE company_code=? AND plan_id=? AND id=? AND progress_revision>=?",revision,revision,company,plan,id,revision);}
}
