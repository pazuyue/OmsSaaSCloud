package com.oms.order.service;

import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import java.util.*;
import static com.oms.order.service.OrderValues.*;
import static com.oms.order.service.RefundValues.*;

/** Read-only shop configuration overview. Constant query count; never calls a vendor API. */
@Service public class RefundConfiguration {
    @Resource RefundService refunds;
    @Resource OrderCatalog catalog;
    @Resource RefundPlatformClient platform;
    @Resource RefundFlowAutomation flow;
    @Resource ConfiguredPushReader reader;
    @Resource RefundParcelPolicy parcelPolicy;

    private Map<Long,Map<String,Object>> batch(String c,String sql){Map<Long,Map<String,Object>> rows=new HashMap<>();for(Map<String,Object> row:refunds.repository.db().query(sql,ROW,c))rows.put(number(row,"channel_id"),row);return rows;}
    public Map<String,Object> list(String c,Map<String,String> query){
        int page,size;try{page=Integer.parseInt(query.getOrDefault("pageNum","1"));size=Integer.parseInt(query.getOrDefault("pageSize","20"));}catch(NumberFormatException bad){throw new IllegalArgumentException("分页参数无效");}
        require(page>=1&&page<=1000000&&size>=1&&size<=50,"分页参数无效");
        String keyword=query.getOrDefault("keyword","").trim().toLowerCase(Locale.ROOT),id=query.getOrDefault("channelId",""),status=query.getOrDefault("status","");
        require(keyword.length()<=100&&id.length()<=64,"搜索条件过长");require(Arrays.asList("","UNCONFIGURED","INCOMPLETE","AUTOMATIC","ERROR").contains(status),"配置状态筛选无效");
        Map<Long,Map<String,Object>> intake=batch(c,"SELECT channel_id,source_key,seller_nick,enabled,auto_convert,revision,last_success,last_error,cursor_time FROM oms_refund_config WHERE company_code=?"),
            policies=batch(c,"SELECT channel_id,enabled,policy_json,revision FROM oms_refund_flow_policy WHERE company_code=?"),
            apis=batch(c,"SELECT channel_id,enabled,last_success,last_error,cursor_time FROM oms_refund_api_cursor WHERE company_code=?"),
            returns=batch(c,"SELECT channel_id,policy,revision FROM oms_refund_parcel_config WHERE company_code=?");
        Map<Long,Map<String,Object>> access=new HashMap<>();boolean unavailable=false;
        try{for(Map<String,Object> row:data(platform.capabilities(c,com.ruoyi.common.core.constant.SecurityConstants.INNER)))access.put(number(row,"channel_id"),row);}catch(RuntimeException failure){unavailable=true;}
        List<Map<String,Object>> matched=new ArrayList<>();long incomplete=0,automatic=0,errors=0,unconfigured=0,total=0;
        for(Map<String,Object> shop:catalog.channels(c)){
            long channel=number(shop,"channel_id");if(!"TM".equals(text(shop,"channel_type"))||(!id.isEmpty()&&!id.equals(String.valueOf(channel)))||(!keyword.isEmpty()&&!text(shop,"channel_name").toLowerCase(Locale.ROOT).contains(keyword)&&!String.valueOf(channel).contains(keyword)))continue;
            Map<String,Object> rule=RefundFlowAutomation.defaults();Map<String,Object> stored=policies.get(channel);if(stored!=null)rule.putAll(obj(stored.get("policy_json")));rule.put("enabled",stored==null?0:stored.get("enabled"));rule.put("revision",stored==null?0:stored.get("revision"));
            Map<String,Object> ret=RefundParcelPolicy.defaults();stored=returns.get(channel);if(stored!=null)ret.putAll(obj(stored.get("policy")));ret.put("revision",stored==null?0:stored.get("revision"));ret.put("merge_mode",RefundParcelPolicy.mergeMode(ret));
            Map<String,Object> cap=access.getOrDefault(channel,m("available",false));Map<String,Object> row=m("channel_id",channel,"channel_name",shop.get("channel_name"),"shop_enabled",shop.get("enabled"),"intake",intake.getOrDefault(channel,m()),"api_sync",apis.getOrDefault(channel,m()),"flow",rule,"returns",ret,"access",cap);
            List<Map<String,Object>> checks=checks(row);row.put("checks",checks);
            boolean configured=number(rule,"revision")>0||number(ret,"revision")>0||intake.containsKey(channel)||apis.containsKey(channel)||number(cap,"revision")>0;
            boolean auto=number(shop,"enabled")==1&&(number(rule,"enabled")==1||number(ret,"auto_create")==1||number(ret,"auto_dispatch")==1||number(ret,"auto_enabled")==1);
            boolean error=!text(intake.getOrDefault(channel,m()),"last_error").isEmpty()||!text(apis.getOrDefault(channel,m()),"last_error").isEmpty();
            row.put("configured",configured);row.put("automatic",auto);row.put("sync_error",error);total++;if(!configured)unconfigured++;if(!checks.isEmpty())incomplete++;if(auto)automatic++;if(error)errors++;
            if("UNCONFIGURED".equals(status)&&configured||"INCOMPLETE".equals(status)&&checks.isEmpty()||"AUTOMATIC".equals(status)&&!auto||"ERROR".equals(status)&&!error)continue;matched.add(row);
        }
        matched.sort(Comparator.comparingLong(row->number(row,"channel_id")));int from=Math.min(matched.size(),(page-1)*size),to=Math.min(matched.size(),from+size);
        return m("rows",new ArrayList<>(matched.subList(from,to)),"total",matched.size(),"summary",m("shops",total,"unconfigured",unconfigured,"incomplete",incomplete,"automatic",automatic,"errors",errors),"capability_unavailable",unavailable);
    }
    public Map<String,Object> detail(String c,long channel){
        Map<String,Object> shop=catalog.channels(c).stream().filter(s->number(s,"channel_id")==channel&&"TM".equals(text(s,"channel_type"))).findFirst().orElseThrow(()->new IllegalArgumentException("店铺不存在或不属于当前公司"));
        List<Map<String,Object>> rows=refunds.repository.db().query("SELECT channel_id,source_key,seller_nick,enabled,auto_convert,initial_time,cursor_time,revision,interval_seconds,last_error,last_success FROM oms_refund_config WHERE company_code=? AND channel_id=?",ROW,c,channel);
        return m("channel_id",channel,"channel_name",shop.get("channel_name"),"shop_enabled",shop.get("enabled"),"intake",rows.isEmpty()?m():rows.get(0),"flow",flow.policy(c,channel),"returns",parcelPolicy.get(c,channel),"sources",reader.choices(c));
    }
    @SuppressWarnings("unchecked") static Map<String,Object> part(Map<String,Object> row,String key){return (Map<String,Object>)row.getOrDefault(key,m());}
    static boolean yes(Map<String,Object> p,String key){return Boolean.TRUE.equals(p.get(key))||"1".equals(String.valueOf(p.get(key)));}
    static List<Map<String,Object>> checks(Map<String,Object> row){
        List<Map<String,Object>> out=new ArrayList<>();Map<String,Object> p=part(row,"flow"),a=part(row,"access"),api=part(row,"api_sync"),r=part(row,"returns");
        boolean known=yes(a,"available"),read=known&&yes(a,"can_query")&&yes(a,"read_enabled"),write=read&&yes(a,"write_enabled"),enabled=yes(p,"enabled");
        if(!yes(row,"shop_enabled")){out.add(m("tab","automation","message","店铺已停用，自动同步与处理暂停"));return out;}
        if(yes(api,"enabled")&&!read)out.add(m("tab","authorization","message",known?"接口补充同步需要有效店铺授权并启用退款查询":"平台授权状态暂不可用，请刷新后核对"));
        boolean platformAction=enabled&&(yes(p,"auto_agree_return")||yes(p,"auto_review")||yes(p,"auto_cancel_feedback")||yes(p,"auto_intercept")||yes(p,"auto_pay"));
        if(platformAction&&!write)out.add(m("tab","authorization","message",known?"已开启的平台自动操作需要有效授权、退款查询和售后操作权限":"平台授权状态暂不可用，请刷新后核对"));
        if(enabled&&yes(p,"auto_return_review")&&money(p.getOrDefault("return_review_limit","0")).signum()<=0)out.add(m("tab","automation","message","退货退款自动审核尚未设置正数限额"));
        if(enabled&&yes(p,"auto_agree_return")&&text(p,"seller_address_id").isEmpty())out.add(m("tab","automation","message","自动同意退货尚未指定平台退回地址"));
        if(enabled&&yes(p,"small_refund_enabled")&&money(p.getOrDefault("small_refund_limit","0")).signum()<=0)out.add(m("tab","automation","message","小额免退货尚未设置正数的子单累计限额"));
        if(enabled&&(yes(p,"auto_shipped_cancel")||yes(p,"auto_return_review"))&&(!yes(r,"auto_create")||text(r,"create_store_code").isEmpty()||text(r,"create_return_address").isEmpty()||text(r,"create_return_contact").isEmpty()))out.add(m("tab","returns","message","自动追回或退货退款自动审核需要开启自动退回并配置退回资料"));
        if(enabled&&yes(p,"auto_pay")){
            if(!"OMS".equals(text(p,"funds_owner"))||!yes(p,"auto_review")||money(p.getOrDefault("payment_limit","0")).signum()<=0)out.add(m("tab","automation","message","自动付款须选择OMS执行、启用退款审核并设置正数限额"));
            if(!known||!yes(a,"payment_enabled")||!yes(a,"payment_authorized")||!yes(a,"ignore_code"))out.add(m("tab","authorization","message","自动付款尚需付款权限、有效子账号授权和平台免验证码条件"));
        }
        if(yes(r,"auto_create")&&(text(r,"create_store_code").isEmpty()||text(r,"create_return_address").isEmpty()||text(r,"create_return_contact").isEmpty()))out.add(m("tab","returns","message","自动创建退回安排需要默认退回仓、接收地址及联系人"));
        if((yes(r,"auto_dispatch")||yes(r,"auto_enabled"))&&text(r,"create_store_code").isEmpty()&&(!(r.get("store_codes") instanceof List)||((List<?>)r.get("store_codes")).isEmpty()))out.add(m("tab","returns","message","自动合包或下发尚未指定适用退回仓"));
        return out;
    }
}
