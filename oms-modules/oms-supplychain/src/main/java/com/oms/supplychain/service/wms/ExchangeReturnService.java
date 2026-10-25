package com.oms.supplychain.service.wms;
import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import java.util.*;
import static com.oms.supplychain.service.wms.WmsStore.*;
@Service public class ExchangeReturnService extends WarehouseReturnService {
    @Resource SharedReturnParcels shared;
    public ExchangeReturnService(){super(Kind.EXCHANGE);}
    Map<String,Object> sourceDetail(String c,long id){return super.detail(c,id);}
    @Override public Map<String,Object> detail(String c,long id){Map<String,Object> r=super.detail(c,id);return r.get("shared_member_id")!=null?shared.decorate(c,"EXCHANGE",id,r):r;}
    @Override public Map<String,Object> prepare(String c,long id,Map<String,Object> input){List<Map<String,Object>> old=db.jdbc.queryForList("SELECT shared_member_id FROM wms_exchange_return WHERE company_code=? AND exchange_id=? ORDER BY generation DESC LIMIT 1",c,id);if(!old.isEmpty()&&old.get(0).get("shared_member_id")!=null)return detail(c,id);return super.prepare(c,id,input);}
    @Override public Map<String,Object> documentAction(String c,long owner,long document,String action,Map<String,Object> input){Map<String,Object> row=db.one("SELECT exchange_id,shared_member_id FROM wms_exchange_return WHERE company_code=? AND id=?",c,document);require(id(row.get("exchange_id"))==owner,"??????????");if(row.get("shared_member_id")!=null){shared.action(c,"EXCHANGE",owner,action,input);return detail(c,owner);}return super.documentAction(c,owner,document,action,input);}
    @Override public String callback(String c,String key,Map<String,String> params,String body){return QimenReturnProtocol.documentSn(body).startsWith("THKX")?shared.callback(c,key,params,body):super.callback(c,key,params,body);}
}
