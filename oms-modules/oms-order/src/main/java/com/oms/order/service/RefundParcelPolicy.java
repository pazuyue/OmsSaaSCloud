package com.oms.order.service;

import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import java.util.*;
import com.ruoyi.common.core.constant.SecurityConstants;
import static com.oms.order.service.OrderValues.*;
import static com.oms.order.service.RefundValues.*;

/** Shop business rules are distinct from warehouse capabilities and JDP configuration. */
@Service public class RefundParcelPolicy {
    @Resource RefundService refunds;
    @Resource RefundReturnClient returns;
    @Resource OrderCatalog catalog;
    static Map<String,Object> defaults(){return m("revision",0,"enabled",1,"manual_enabled",1,"scope","SAME_ORDER","allow_append",0,"max_refunds",100,"max_quantity",0,"auto_enabled",0,"auto_append",0,"auto_dispatch",0,"auto_allocate",0,"collect_seconds",120,"logistics_sources",Arrays.asList("PLATFORM","MANUAL"),"store_codes",Collections.emptyList());}
    Map<String,Object> get(String c,long channel){List<Map<String,Object>> rows=refunds.repository.db().query("SELECT * FROM oms_refund_parcel_config WHERE company_code=? AND channel_id=?",ROW,c,channel);Map<String,Object> p=defaults();if(!rows.isEmpty()){p.putAll(obj(rows.get(0).get("policy")));p.put("revision",rows.get(0).get("revision"));}p.put("channel_id",channel);return p;}
    public Map<String,Object> forRefund(String c,long id){return get(c,number(refunds.business(c,id,false),"channel_id"));}
    public Map<String,Object> detail(String c,long channel){catalog.validateShop(c,channel);return m("policy",get(c,channel),"warehouses",data(returns.parcelCapabilities(c,SecurityConstants.INNER)));}
    static List<String> values(Map<String,Object> p,String key){Object raw=p.get(key);require(raw instanceof List,"配置列表无效："+key);List<String> out=new ArrayList<>();for(Object v:(List<?>)raw){String value=String.valueOf(v);require(!value.isEmpty()&&value.length()<=64&&!out.contains(value),"配置列表重复或无效："+key);out.add(value);}require(out.size()<=200,"配置列表过多");return out;}
    static long flag(Map<String,Object> p,String key){long n=number(p,key);require(n==0||n==1,"开关值无效："+key);return n;}
    public void save(String c,long channel,Map<String,Object> input,String actor){catalog.validateShop(c,channel);Map<String,Object> p=defaults();p.putAll(input);p.put("channel_id",channel);
        for(String k:Arrays.asList("enabled","manual_enabled","allow_append","auto_enabled","auto_append","auto_dispatch","auto_allocate"))p.put(k,flag(p,k));
        require(number(p,"collect_seconds")>=0&&number(p,"collect_seconds")<=3600,"合包等待须为0至3600秒");
        require(number(p,"allow_append")==0&&number(p,"auto_append")==0,"当前适配未支持共同THK改单，不能开启下发后追加");
        require(Arrays.asList("SAME_ORDER","CROSS_ORDER_MANUAL").contains(text(p,"scope")),"合包范围无效");
        require(number(p,"max_refunds")>=1&&number(p,"max_refunds")<=100&&number(p,"max_quantity")>=0&&number(p,"max_quantity")<=100000000,"合包数量上限无效");
        require(number(p,"auto_enabled")==0||number(p,"enabled")==1,"自动合包依赖允许合包退回");
        require(number(p,"auto_append")==0||(number(p,"auto_enabled")==1&&number(p,"allow_append")==1),"自动追加依赖自动合包和允许下发后追加");
        require(number(p,"auto_dispatch")==0||number(p,"auto_enabled")==1,"自动下发依赖自动合包");
        List<String> sources=values(p,"logistics_sources"),stores=values(p,"store_codes");require(Arrays.asList("PLATFORM","MANUAL").containsAll(sources),"物流来源无效");
        require(number(p,"auto_enabled")==0||(!sources.isEmpty()&&!stores.isEmpty()),"自动合包须选择物流来源和退回仓");
        List<Map<String,Object>> warehouses=data(returns.parcelCapabilities(c,SecurityConstants.INNER));
        for(String store:stores){Map<String,Object> w=warehouses.stream().filter(x->store.equals(text(x,"store_code"))).findFirst().orElseThrow(()->new IllegalArgumentException("退回仓不属于当前公司"));
            require(number(w,"usable")==1,"退回仓 "+store+" 未启用或不支持同运单多退回单");
            if(number(p,"allow_append")==1)require(number(w,"shared_return_append")==1,"退回仓 "+store+" 未确认支持已有包裹追加THK");}
        refunds.tx().execute(tx->{List<Map<String,Object>> old=refunds.repository.db().query("SELECT revision FROM oms_refund_parcel_config WHERE company_code=? AND channel_id=? FOR UPDATE",ROW,c,channel);long revision=old.isEmpty()?0:number(old.get(0),"revision");require(revision==number(input,"revision"),"合包配置已变化，请刷新后重试");
            Map<String,Object> clean=new LinkedHashMap<>();for(String key:defaults().keySet())if(!"revision".equals(key))clean.put(key,p.get(key));
            if(old.isEmpty())refunds.repository.db().update("INSERT INTO oms_refund_parcel_config(company_code,channel_id,enabled,auto_enabled,policy,updated_by) VALUES (?,?,?,?,?,?)",c,channel,p.get("enabled"),p.get("auto_enabled"),encode(clean),actor);
            else refunds.repository.db().update("UPDATE oms_refund_parcel_config SET enabled=?,auto_enabled=?,policy=?,revision=revision+1,updated_by=? WHERE company_code=? AND channel_id=?",p.get("enabled"),p.get("auto_enabled"),encode(clean),actor,c,channel);
            return null;});
    }
}
