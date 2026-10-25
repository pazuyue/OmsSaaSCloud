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
    static Map<String,Object> defaults(){return m("allow_exchange_merge",0,"auto_create",0,"create_store_code","","create_return_address","","create_return_contact","","revision",0,"enabled",1,"manual_enabled",1,"scope","SAME_ORDER","allow_append",0,"max_refunds",100,"max_quantity",0,"auto_enabled",0,"auto_append",0,"auto_dispatch",0,"auto_allocate",0,"allocation_mode","STRICT","allow_partial_allocation",0,"allocation_order","REFUND_CREATED","collect_seconds",120,"logistics_sources",Arrays.asList("PLATFORM","MANUAL"),"store_codes",Collections.emptyList());}
    Map<String,Object> get(String c,long channel){return get(c,channel,false);}
    Map<String,Object> get(String c,long channel,boolean lock){List<Map<String,Object>> rows=refunds.repository.db().query("SELECT * FROM oms_refund_parcel_config WHERE company_code=? AND channel_id=?"+(lock?" FOR UPDATE":""),ROW,c,channel);Map<String,Object> p=defaults();if(!rows.isEmpty()){p.putAll(obj(rows.get(0).get("policy")));p.put("revision",rows.get(0).get("revision"));}p.put("channel_id",channel);return p;}
    static String mergeMode(Map<String,Object> p){return number(p,"enabled")!=1?"NONE":number(p,"auto_enabled")==1?"AUTO":"MANUAL";}
    public Map<String,Object> forRefund(String c,long id){return get(c,number(refunds.business(c,id,false),"channel_id"));}
    public Map<String,Object> detail(String c,long channel){
        Map<String,Object> shop=catalog.channels(c).stream().filter(s->number(s,"channel_id")==channel&&"TM".equals(text(s,"channel_type"))).findFirst().orElseThrow(()->new IllegalArgumentException("店铺不存在或不属于当前公司"));
        return m("policy",get(c,channel),"warehouses",data(returns.parcelCapabilities(c,SecurityConstants.INNER)),"editable",number(shop,"enabled")==1);
    }
    /** One company catalog and one policy batch; warehouse names are resolved for the current page only. */
    public Map<String,Object> list(String c,Map<String,String> query){
        int page,size;
        try{page=Integer.parseInt(query.getOrDefault("pageNum","1"));size=Integer.parseInt(query.getOrDefault("pageSize","20"));}
        catch(NumberFormatException bad){throw new IllegalArgumentException("分页参数无效");}
        require(page>=1&&page<=1000000&&size>=1&&size<=50,"分页参数无效");
        String keyword=query.getOrDefault("keyword","").trim().toLowerCase(Locale.ROOT),channel=query.getOrDefault("channelId","");
        String mode=query.getOrDefault("mergeMode","");require(Arrays.asList("","NONE","MANUAL","AUTO").contains(mode),"合包方式筛选无效");
        String auto=query.getOrDefault("autoEnabled",""),dispatch=query.getOrDefault("autoDispatch",""),create=query.getOrDefault("autoCreate",""),configured=query.getOrDefault("configState","");
        require(keyword.length()<=100&&channel.length()<=64,"店铺搜索条件过长");
        require(Arrays.asList("","0","1").contains(auto)&&Arrays.asList("","0","1").contains(dispatch)&&Arrays.asList("","0","1").contains(create),"自动处理筛选无效");
        require(Arrays.asList("","DEFAULT","CUSTOM").contains(configured),"配置情况筛选无效");
        Map<Long,Map<String,Object>> policies=new HashMap<>();
        for(Map<String,Object> row:refunds.repository.db().query("SELECT channel_id,revision,policy,updated_by,update_time FROM oms_refund_parcel_config WHERE company_code=?",ROW,c))policies.put(number(row,"channel_id"),row);
        List<Map<String,Object>> base=new ArrayList<>();
        for(Map<String,Object> shop:catalog.channels(c)){
            long id=number(shop,"channel_id");String name=text(shop,"channel_name");
            if(!"TM".equals(text(shop,"channel_type"))||(!channel.isEmpty()&&!channel.equals(String.valueOf(id)))||(!keyword.isEmpty()&&!name.toLowerCase(Locale.ROOT).contains(keyword)&&!String.valueOf(id).contains(keyword)))continue;
            Map<String,Object> stored=policies.get(id),policy=defaults();if(stored!=null)policy.putAll(obj(stored.get("policy")));
            Map<String,Object> row=m("channel_id",id,"channel_name",name,"channel_type","TM","shop_enabled",shop.get("enabled"),"configured",stored!=null,"revision",stored==null?0:stored.get("revision"),"updated_by",stored==null?"":stored.get("updated_by"),"update_time",stored==null?null:stored.get("update_time"));
            for(String key:Arrays.asList("allow_exchange_merge","auto_create","create_store_code","enabled","manual_enabled","scope","auto_enabled","auto_dispatch","collect_seconds","max_refunds","max_quantity","store_codes","allocation_mode","allow_partial_allocation","allocation_order"))row.put(key,policy.get(key));
            row.put("merge_mode",mergeMode(policy));
            base.add(row);
        }
        base.sort(Comparator.comparingLong(row->number(row,"channel_id")));
        Map<String,Object> summary=m("shops",base.size(),"configured",base.stream().filter(row->Boolean.TRUE.equals(row.get("configured"))).count(),"auto_dispatch",base.stream().filter(row->number(row,"auto_dispatch")==1).count(),"auto_create",base.stream().filter(row->number(row,"auto_create")==1).count(),"auto_enabled",base.stream().filter(row->number(row,"auto_enabled")==1).count());
        List<Map<String,Object>> matched=new ArrayList<>();
        for(Map<String,Object> row:base){
            if(!mode.isEmpty()&&!mode.equals(text(row,"merge_mode")))continue;
            if(!auto.isEmpty()&&number(row,"auto_enabled")!=Long.parseLong(auto))continue;
            if(!create.isEmpty()&&number(row,"auto_create")!=Long.parseLong(create))continue;
            if(!dispatch.isEmpty()&&number(row,"auto_dispatch")!=Long.parseLong(dispatch))continue;
            if("DEFAULT".equals(configured)&&Boolean.TRUE.equals(row.get("configured")))continue;
            if("CUSTOM".equals(configured)&&!Boolean.TRUE.equals(row.get("configured")))continue;
            matched.add(row);
        }
        int from=Math.min(matched.size(),(page-1)*size),to=Math.min(matched.size(),from+size);
        List<Map<String,Object>> rows=new ArrayList<>(matched.subList(from,to));Set<String> codes=new HashSet<>();
        for(Map<String,Object> row:rows){codes.addAll(values(row,"store_codes"));if(!text(row,"create_store_code").isEmpty())codes.add(text(row,"create_store_code"));}
        Map<String,String> names=new HashMap<>();boolean unavailable=false;
        if(!codes.isEmpty())try{for(Map<String,Object> store:catalog.warehouses(c)){String code=text(store,"wms_simulation_code");if(codes.contains(code))names.put(code,text(store,"wms_simulation_name"));}}catch(RuntimeException failure){unavailable=true;}
        for(Map<String,Object> row:rows){List<Map<String,Object>> stores=new ArrayList<>();for(String code:values(row,"store_codes"))stores.add(m("code",code,"name",names.getOrDefault(code,code)));row.put("warehouses",stores);row.put("create_store_name",names.getOrDefault(text(row,"create_store_code"),text(row,"create_store_code")));}
        return m("rows",rows,"total",matched.size(),"summary",summary,"warehouse_names_unavailable",unavailable);
    }
    static List<String> values(Map<String,Object> p,String key){Object raw=p.get(key);require(raw instanceof List,"配置列表无效："+key);List<String> out=new ArrayList<>();for(Object v:(List<?>)raw){String value=String.valueOf(v);require(!value.isEmpty()&&value.length()<=64&&!out.contains(value),"配置列表重复或无效："+key);out.add(value);}require(out.size()<=200,"配置列表过多");return out;}
    static long flag(Map<String,Object> p,String key){long n=number(p,key);require(n==0||n==1,"开关值无效："+key);return n;}
    public void save(String c,long channel,Map<String,Object> input,String actor){catalog.validateShop(c,channel);Map<String,Object> p=defaults();p.putAll(input);p.put("channel_id",channel);if(input.containsKey("merge_mode")){String mode=text(input,"merge_mode");require(Arrays.asList("NONE","MANUAL","AUTO").contains(mode),"合包方式无效");p.put("enabled",mode.equals("NONE")?0:1);p.put("manual_enabled",mode.equals("NONE")?0:1);p.put("auto_enabled",mode.equals("AUTO")?1:0);}
        for(String k:Arrays.asList("allow_exchange_merge","auto_create","enabled","manual_enabled","allow_append","auto_enabled","auto_append","auto_dispatch","auto_allocate","allow_partial_allocation"))p.put(k,flag(p,k));
        require(Arrays.asList("MANUAL","STRICT","RULE").contains(text(p,"allocation_mode")),"实收分配方式无效");
        require("REFUND_CREATED".equals(text(p,"allocation_order")),"实收分配顺序无效");
        require(number(p,"allow_partial_allocation")==0||"RULE".equals(text(p,"allocation_mode")),"部分实收自动分配须选择按规则自动分配");
        require(number(p,"collect_seconds")>=0&&number(p,"collect_seconds")<=3600,"合包等待须为0至3600秒");
        require(number(p,"allow_append")==0&&number(p,"auto_append")==0,"当前适配未支持共同THK改单，不能开启下发后追加");
        require(Arrays.asList("SAME_ORDER","CROSS_ORDER_MANUAL").contains(text(p,"scope")),"合包范围无效");
        require(number(p,"max_refunds")>=1&&number(p,"max_refunds")<=100&&number(p,"max_quantity")>=0&&number(p,"max_quantity")<=100000000,"合包数量上限无效");
        require(number(p,"allow_exchange_merge")==0||number(p,"auto_enabled")==1&&"SAME_ORDER".equals(text(p,"scope")),"与换货合包须启用同原订单自动合包");
        require(number(p,"auto_enabled")==0||number(p,"enabled")==1,"自动合包依赖允许合包退回");
        require(number(p,"auto_append")==0||(number(p,"auto_enabled")==1&&number(p,"allow_append")==1),"自动追加依赖自动合包和允许下发后追加");
        List<String> sources=values(p,"logistics_sources"),stores=values(p,"store_codes");if(stores.isEmpty()&&!text(p,"create_store_code").isEmpty()){stores=Collections.singletonList(text(p,"create_store_code"));p.put("store_codes",stores);}require(Arrays.asList("PLATFORM","MANUAL").containsAll(sources),"物流来源无效");
        require(number(p,"auto_enabled")==0||(!sources.isEmpty()&&!stores.isEmpty()),"自动合包须选择物流来源和退回仓");
        require(number(p,"auto_dispatch")==0||!stores.isEmpty(),"自动下发须选择适用退回仓");
        if(number(p,"auto_create")==1){
            for(String key:Arrays.asList("create_store_code","create_return_address","create_return_contact")){String value=text(p,key).trim();int max=key.equals("create_store_code")?64:key.equals("create_return_address")?500:200;require(!value.isEmpty()&&value.length()<=max,"请填写有效的商品退回仓、地址和联系人");p.put(key,value);}
            require(number(p,"auto_dispatch")==0||stores.contains(text(p,"create_store_code")),"默认退回仓须纳入自动下发范围");
        }
        List<Map<String,Object>> warehouses=data(returns.parcelCapabilities(c,SecurityConstants.INNER));
        if(number(p,"auto_create")==1)require(warehouses.stream().anyMatch(w->text(p,"create_store_code").equals(text(w,"store_code"))&&number(w,"usable")==1),"自动创建退回仓不属于当前公司或仓库能力不可用");
        for(String store:(number(p,"auto_dispatch")==1||number(p,"auto_enabled")==1)?stores:Collections.<String>emptyList()){Map<String,Object> w=warehouses.stream().filter(x->store.equals(text(x,"store_code"))).findFirst().orElseThrow(()->new IllegalArgumentException("退回仓不属于当前公司"));
            require(number(w,"usable")==1,"退回仓 "+store+" 未启用或不支持退货入库");
            if(number(p,"allow_append")==1)require(number(w,"shared_return_append")==1,"退回仓 "+store+" 未确认支持已有包裹追加THK");}
        refunds.tx().execute(tx->{List<Map<String,Object>> old=refunds.repository.db().query("SELECT revision FROM oms_refund_parcel_config WHERE company_code=? AND channel_id=? FOR UPDATE",ROW,c,channel);long revision=old.isEmpty()?0:number(old.get(0),"revision");require(revision==number(input,"revision"),"合包配置已变化，请刷新后重试");
            Map<String,Object> clean=new LinkedHashMap<>();for(String key:defaults().keySet())if(!"revision".equals(key))clean.put(key,p.get(key));
            if(old.isEmpty())refunds.repository.db().update("INSERT INTO oms_refund_parcel_config(company_code,channel_id,enabled,auto_enabled,auto_dispatch,policy,updated_by) VALUES (?,?,?,?,?,?,?)",c,channel,p.get("enabled"),p.get("auto_enabled"),p.get("auto_dispatch"),encode(clean),actor);
            else refunds.repository.db().update("UPDATE oms_refund_parcel_config SET enabled=?,auto_enabled=?,auto_dispatch=?,policy=?,revision=revision+1,updated_by=? WHERE company_code=? AND channel_id=?",p.get("enabled"),p.get("auto_enabled"),p.get("auto_dispatch"),encode(clean),actor,c,channel);
            return null;});
    }
}
