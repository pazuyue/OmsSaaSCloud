package com.oms.common.carrier;

import java.util.*;
import java.util.stream.Collectors;

/** Pure, deterministic carrier conversion. Callers supply tenant-scoped, bounded rows. */
public final class CarrierResolver {
    private CarrierResolver() {}
    public static String s(Map<String,?> row,String key){Object v=row.get(key);return v==null?"":String.valueOf(v);}
    public static long n(Map<String,?> row,String key){String v=s(row,key);return v.isEmpty()?0:Long.parseLong(v);}
    public static Map<String,Object> result(String state,String message){Map<String,Object> r=new LinkedHashMap<>();r.put("state",state);r.put("message",message);r.put("available","RESOLVED".equals(state));return r;}
    private static boolean eq(Map<String,?> a,Map<String,?> b,String key){return s(a,key).equals(s(b,key));}
    private static int scope(Map<String,?> row,Map<String,?> ctx){
        if(!eq(row,ctx,"system_type")||!eq(row,ctx,"provider")||!eq(row,ctx,"purpose"))return -1;
        if("WMS".equals(s(row,"system_type"))){
            if(s(ctx,"connection_id").isEmpty()||!eq(row,ctx,"connection_id"))return -1;
            if(!s(row,"real_store_code").isEmpty())return !s(row,"owner_code").isEmpty()&&eq(row,ctx,"owner_code")&&eq(row,ctx,"real_store_code")?3:-1;
            return s(row,"owner_code").isEmpty()?1:eq(row,ctx,"owner_code")?2:-1;
        }
        if(!s(row,"shop_id").isEmpty())return eq(row,ctx,"shop_id")?3:-1;
        if(!s(row,"connection_id").isEmpty())return eq(row,ctx,"connection_id")?2:-1;
        return 1;
    }
    /** Scope is selected before product/status checks: a broken specific row must never fall back. */
    public static Map<String,Object> convert(Map<String,Object> ctx,List<Map<String,Object>> rows){
        String direction=s(ctx,"direction");if(!Arrays.asList("IN","OUT").contains(direction))return result("INVALID_CONTEXT","请选择识别或目标转换方向");
        if(!direction.equals(CarrierPurpose.direction(s(ctx,"purpose"))))return result("INVALID_CONTEXT","该业务用途的处理方向由系统确定，请核对具体业务动作");
        if("WMS".equals(s(ctx,"system_type"))&&s(ctx,"connection_id").isEmpty())return result("INVALID_CONTEXT","WMS 转换必须指定连接实例");
        String identity="IN".equals(direction)?"external_code":"standard_id";
        if(s(ctx,identity).isEmpty())return result("INVALID_CONTEXT","缺少原始编码或标准快递身份");
        List<Map<String,Object>> candidates=new ArrayList<>();int best=-1;
        for(Map<String,Object> row:rows){if(!eq(row,ctx,identity)||!(direction.equals(s(row,"direction"))||"BOTH".equals(s(row,"direction"))))continue;int level=scope(row,ctx);if(level<0||level<best)continue;if(level>best){candidates.clear();best=level;}candidates.add(row);}
        if(candidates.isEmpty())return result("MISSING","当前用途及作用域缺少快递关联");
        String product=s(ctx,"product_code");List<Map<String,Object>> exact=candidates.stream().filter(r->!product.isEmpty()&&product.equals(s(r,"product_code"))).collect(Collectors.toList());
        List<Map<String,Object>> selected=!exact.isEmpty()?exact:candidates.stream().filter(r->s(r,"product_code").isEmpty()).collect(Collectors.toList());
        if(selected.size()>1&&"OUT".equals(direction))selected=selected.stream().filter(r->n(r,"is_default")==1).collect(Collectors.toList());
        if(selected.isEmpty())return result("CONFLICT","命中作用域未配置该产品或唯一默认输出，不能回退上级");
        if(selected.size()!=1)return result("CONFLICT","命中作用域存在多条有效候选，请明确产品条件或唯一默认输出");
        Map<String,Object> row=selected.get(0);Map<String,Object> out;
        if(n(row,"enabled")!=1)out=result("DISABLED","命中的快递关联已停用："+s(row,"disabled_reason"));
        else if(n(row,"directory_enabled")!=1)out=result("DIRECTORY_UNAVAILABLE","快递清单已停用或不可用");
        else if(n(row,"standard_enabled")!=1)out=result("DISABLED","标准快递已停用："+s(row,"standard_disabled_reason"));
        else out=result("RESOLVED","标准快递关联通过");
        for(String key:Arrays.asList("id","revision","standard_id","standard_code","standard_name","external_code","external_name","directory_id","product_code","evidence","connection_id","shop_id","owner_code","real_store_code","purpose","system_type","provider"))out.put("id".equals(key)?"mapping_id":key,row.get(key));
        out.put("scope_level",best);return out;
    }
    /** Inbound capabilities deliberately ignore outbound destination restrictions. */
    @SuppressWarnings("unchecked") public static Map<String,Object> capability(Map<String,Object> ctx,List<Map<String,Object>> rows){
        List<Map<String,Object>> candidates=new ArrayList<>();
        for(Map<String,Object> r:rows){boolean match=true;for(String k:Arrays.asList("connection_id","owner_code","real_store_code","standard_id","flow"))if(!eq(r,ctx,k))match=false;if(match)candidates.add(r);}
        String product=s(ctx,"product_code");List<Map<String,Object>> exact=candidates.stream().filter(r->!product.isEmpty()&&product.equals(s(r,"product_code"))).collect(Collectors.toList());
        List<Map<String,Object>> selected=!exact.isEmpty()?exact:candidates.stream().filter(r->s(r,"product_code").isEmpty()).collect(Collectors.toList());
        if(selected.size()!=1)return result("CAPABILITY_UNSUPPORTED",selected.isEmpty()?"该连接、货主、实仓尚未配置本用途服务能力":"仓库服务能力配置冲突");
        Map<String,Object> r=selected.get(0);if(n(r,"enabled")!=1)return result("CAPABILITY_UNSUPPORTED","仓库服务已停用："+s(r,"disabled_reason"));
        if("OUTBOUND".equals(s(ctx,"flow"))){String province=s(ctx,"province");if(province.isEmpty())return result("CAPABILITY_UNSUPPORTED","出库缺少收货省份，无法检查配送范围");List<String> allow=(List<String>)r.getOrDefault("provinces",Collections.emptyList()),deny=(List<String>)r.getOrDefault("excluded_provinces",Collections.emptyList());if(deny.contains(province)||!allow.isEmpty()&&!allow.contains(province))return result("CAPABILITY_UNSUPPORTED","该仓快递不支持当前收货省份");}
        Map<String,Object> out=result("RESOLVED","仓库服务能力检查通过");out.put("capability_id",r.get("id"));out.put("capability_revision",r.get("revision"));out.put("capability_evidence",r.get("evidence"));return out;
    }
}
