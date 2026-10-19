package com.oms.channel.platform;
import com.oms.messaging.MessageOutbox;
import java.util.*;
/** Credential-owner projection: only opaque rate identities leave the channel service. */
final class PlatformRateIdentity {
 private static String s(Map<String,?> r,String k){return r.get(k)==null?"":String.valueOf(r.get(k));}
 static List<String> keys(Map<String,?> r){String app=s(r,"app_key"),shop=s(r,"expected_shop_id");if(app.isEmpty()&&(Boolean.TRUE.equals(r.get("simulation_mode"))||s(r,"simulation_mode").equals("1")))app="SIM:"+s(r,"company_code").toUpperCase(Locale.ROOT)+":"+s(r,"app_id");if(app.isEmpty()||shop.isEmpty())throw new IllegalArgumentException("平台应用或店铺限流身份未配置");return Arrays.asList(MessageOutbox.hash("APP:TM:"+app),MessageOutbox.hash("SHOP:TM:"+shop));}
}
