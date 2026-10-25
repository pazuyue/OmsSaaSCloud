package com.oms.order.service;

import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import java.util.*;
import com.ruoyi.common.core.constant.SecurityConstants;
import static com.oms.order.service.OrderValues.*;
import static com.oms.order.service.RefundValues.*;

/** Read-only logistics workspace. One parcel lookup and one local channel capability lookup, never courier calls. */
@Service public class RefundLogisticsView {
    @Resource RefundService refunds;
    @Resource RefundReturnClient returns;
    @Resource RefundPlatformClient channel;
    private OrderRepository r(){return refunds.repository;}
    private static List<Map<String,Object>> rows(Object v){return RefundDispositionService.rows(v);}
    Map<String,Object> read(String c,Map<String,Object> b,List<Map<String,Object>> plans,List<Map<String,Object>> tasks,List<Map<String,Object>> displayReturns){
        List<String> issues=new ArrayList<>();
        Map<String,Object> platform=refunds.platform(c,number(b,"platform_refund_id"),false),raw=m();
        try{raw=parse(text(platform,"payload"));}catch(RuntimeException invalid){issues.add("平台退回物流资料未能解析，已关联包裹与仓库实收结果仍独立保留");}
        boolean intercept="INTERCEPT".equals(text(b,"goods_action"))||plans.stream().anyMatch(p->"INTERCEPT".equals(text(p,"return_mode"))&&!Arrays.asList("STOPPED","RELEASED","CANCELED").contains(text(p,"state")))||tasks.stream().anyMatch(t->"INTERCEPT".equals(text(t,"action"))&&!"CANCELED".equals(text(t,"state")));
        Map<String,Object> result=RefundLogisticsSummary.project(intercept,RefundLogisticsSummary.operations(r(),c,Collections.singletonList(number(b,"id"))));
        String mode="UNKNOWN";
        if(intercept)try{Map<String,Object> capability=data(channel.capability(c,number(b,"channel_id"),SecurityConstants.INNER));String value=text(capability,"execution_mode");if(Arrays.asList("SIMULATION","REAL").contains(value))mode=value;}catch(RuntimeException unavailable){issues.add("平台当前接入模式暂未读取，保留已有申请记录");}
        List<Map<String,Object>> parcels=new ArrayList<>();boolean parcelAvailable=true;
        if(!plans.isEmpty())try{parcels=data(returns.parcels(c,number(b,"id"),SecurityConstants.INNER));}catch(RuntimeException unavailable){parcelAvailable=false;issues.add("关联退回包裹暂未读取，运单及最新物流状态待核对");}
        Map<Long,Map<String,Object>> parcelByPlan=new HashMap<>();for(Map<String,Object> parcel:parcels)for(Map<String,Object> member:rows(parcel.get("members")))if(number(member,"refund_id")==number(b,"id")&&(member.get("active")==null||number(member,"active")==1)){long plan=number(member,"plan_id");Map<String,Object> old=parcelByPlan.get(plan);if(old==null||number(parcel,"id")>number(old,"id"))parcelByPlan.put(plan,parcel);}
        Map<Long,Map<String,Object>> byDisplay=new HashMap<>();for(Map<String,Object> row:displayReturns)byDisplay.put(number(row,"plan_id"),row);
        List<Map<String,Object>> incoming=new ArrayList<>();
        for(Map<String,Object> plan:plans){Map<String,Object> d=byDisplay.get(number(plan,"id"));if(d==null)continue;
            Map<String,Object> info=returnLogistics(plan,parcelByPlan.get(number(plan,"id")),platform,raw,parcelAvailable);
            d.put("return_logistics",info);d.put("transport_display",transportDisplay(info,number(plan,"accepted_quantity")));
            incoming.add(m("plan_id",plan.get("id"),"mode",plan.get("return_mode"),"item_kind",d.get("item_kind"),"sku_sn",d.get("sku_sn"),"goods_name",d.get("goods_name"),"stock_unit",d.get("stock_unit"),"quantity",d.get("quantity"),"accepted_quantity",d.get("accepted_quantity"),"posted_quantity",d.get("posted_quantity"),"documents",d.get("documents"),"logistics",info,"transport_display",d.get("transport_display"),"state",plan.get("state"),"dispatch_state",plan.get("auto_dispatch_state"),"dispatch_message",plan.get("auto_dispatch_message")));
        }
        Map<String,Object> original=outgoing(c,b,plans);
        result.putAll(m("original_packages",original.get("packages"),"packages_truncated",original.get("truncated"),"returns",incoming,"issues",issues,"platform_mode",mode,"mode_basis","CURRENT_CONFIGURATION","goods_done","DONE".equals(text(b,"goods_state")),"funds_state",b.get("funds_state"),"parcel_lookup_available",parcelAvailable));
        return result;
    }
    static Map<String,Object> returnLogistics(Map<String,Object> plan,Map<String,Object> parcel,Map<String,Object> platform,Map<String,Object> raw,boolean available){
        Map<String,Object> a=obj(plan.get("result_json"));
        if(parcel!=null){
            Set<Long> refunds=new HashSet<>();for(Map<String,Object> member:rows(parcel.get("members")))refunds.add(number(member,"refund_id"));
            boolean manual="MANUAL".equals(text(plan,"logistics_source"));String source=manual?"MANUAL":"PLATFORM";
            // Preserve the confirmed parcel when later platform/manual information differs.
            String claimed=manual?text(plan,"return_waybill"):text(raw,"sid"),carrier=manual?text(plan,"return_carrier"):text(raw,"company_name");
            boolean carrierConflict=false;if(!carrier.isEmpty())try{carrierConflict=!RefundParcelAutomation.carrier(carrier).equalsIgnoreCase(text(parcel,"carrier_code"));}catch(IllegalArgumentException unknown){carrierConflict=!carrier.equalsIgnoreCase(text(parcel,"carrier_code"));}
            boolean conflict=!claimed.isEmpty()&&!claimed.equals(text(parcel,"waybill"))||carrierConflict;if(conflict||claimed.isEmpty()||!manual&&!"VALID".equals(text(platform,"source_state")))source="CONFIRMED_PARCEL";
            return m("state","BOUND","carrier",parcel.get("carrier_code"),"waybill",parcel.get("waybill"),"source",source,"registered_at",parcel.get("create_time"),"time_kind","PARCEL_REGISTERED","transport_state",parcel.get("transport_state"),"parcel_id",parcel.get("id"),"parcel_sn",parcel.get("parcel_sn"),"refund_count",refunds.size(),"warehouse_environment",parcel.get("warehouse_environment"),"source_conflict",conflict,"latest_claimed_waybill",conflict?claimed:null,"latest_claimed_carrier",conflict?carrier:null,"evidence",parcel.get("evidence"),"store_code",parcel.get("store_code"),"shared",rows(parcel.get("members")).size()>1);
        }
        if(!available)return m("state","UNAVAILABLE","parcel_id",a.get("parcel_id"),"parcel_sn",a.get("parcel_sn"),"carrier","","waybill","","source","UNKNOWN","transport_state","UNKNOWN");
        boolean manual="MANUAL".equals(text(plan,"logistics_source")),trusted=manual||"VALID".equals(text(platform,"source_state"));
        String waybill=trusted?(manual?text(plan,"return_waybill"):text(raw,"sid")):"",carrier=trusted?(manual?text(plan,"return_carrier"):text(raw,"company_name")):"";
        return m("state",waybill.isEmpty()||carrier.isEmpty()?"MISSING":"PROVIDED","carrier",carrier,"waybill",waybill,"source",manual?"MANUAL":"PLATFORM","registered_at",manual?null:platform.get("source_modified"),"time_kind",manual?"NOT_RECORDED":"PLATFORM_UPDATED","transport_state",plan.get("transport_state"),"parcel_id",a.get("parcel_id"),"parcel_sn",a.get("parcel_sn"),"evidence",manual?plan.get("transport_evidence"):null,"store_code",plan.get("store_code"));
    }
    static String transportDisplay(Map<String,Object> info,long accepted){
        if("UNAVAILABLE".equals(text(info,"state")))return "包裹物流资料暂未读取，仓库实收结果独立保留";
        String state=text(info,"transport_state");
        if(state.isEmpty()||Arrays.asList("WAITING","UNKNOWN").contains(state))return accepted>0?"物流状态尚未更新，仓库已确认实收":"尚未取得退回物流进度";
        return text(m("IN_TRANSIT","物流记录：退回在途","DELIVERED","物流记录：显示签收，实收以仓库为准","REFUSED","物流记录：已拒签","INTERCEPTED","物流记录：已登记拦截退回","EXCEPTION","物流记录：存在异常"),state).isEmpty()?"退回物流状态待核对":text(m("IN_TRANSIT","物流记录：退回在途","DELIVERED","物流记录：显示签收，实收以仓库为准","REFUSED","物流记录：已拒签","INTERCEPTED","物流记录：已登记拦截退回","EXCEPTION","物流记录：存在异常"),state);
    }
    private Map<String,Object> outgoing(String c,Map<String,Object> b,List<Map<String,Object>> plans){
        Set<Long> sourceIds=new LinkedHashSet<>();for(Map<String,Object> p:plans)if(number(p,"shipment_line_id")>0)sourceIds.add(number(p,"shipment_line_id"));
        List<Object> args=new ArrayList<>(Arrays.asList(c,b.get("order_id"),b.get("platform_oid")));
        String own="ol.platform_oid=?";if(!sourceIds.isEmpty()){own+=" OR sl.id IN ("+String.join(",",Collections.nCopies(sourceIds.size(),"?"))+")";args.addAll(sourceIds);}
        List<Map<String,Object>> packages=r().db().query("SELECT p.id,p.shipment_id,p.express_code,p.create_time registered_at,sh.shipment_sn,COALESCE(NULLIF(p.carrier_code,''),sh.carrier_code) carrier_code,COALESCE(NULLIF(p.carrier_name,''),sh.carrier_name) carrier_name FROM oms_order_shipment_package p JOIN oms_order_shipment sh ON sh.company_code=p.company_code AND sh.id=p.shipment_id WHERE p.company_code=? AND sh.order_id=? AND EXISTS(SELECT 1 FROM oms_order_shipment_package_line pl JOIN oms_order_shipment_line sl ON sl.company_code=pl.company_code AND sl.id=pl.shipment_line_id JOIN oms_order_line ol ON ol.company_code=sl.company_code AND ol.id=sl.order_line_id WHERE pl.company_code=p.company_code AND pl.package_id=p.id AND ("+own+")) ORDER BY p.id LIMIT 101",ROW,args.toArray());
        boolean truncated=packages.size()>100;if(truncated)packages.remove(packages.size()-1);if(packages.isEmpty())return m("packages",packages,"truncated",truncated);
        args=new ArrayList<>();args.add(c);Map<Long,Map<String,Object>> byId=new HashMap<>();for(Map<String,Object> p:packages){args.add(p.get("id"));p.put("lines",new ArrayList<Map<String,Object>>());byId.put(number(p,"id"),p);}
        List<Map<String,Object>> lines=r().db().query("SELECT pl.package_id,pl.quantity,sl.id shipment_line_id,sl.sku_sn,sl.goods_name,sl.store_code,ol.platform_oid,ol.gift_plan_id,IF(ol.stocked_bundle_snapshot IS NULL,'件','套') stock_unit FROM oms_order_shipment_package_line pl JOIN oms_order_shipment_line sl ON sl.company_code=pl.company_code AND sl.id=pl.shipment_line_id JOIN oms_order_line ol ON ol.company_code=sl.company_code AND ol.id=sl.order_line_id WHERE pl.company_code=? AND pl.package_id IN ("+String.join(",",Collections.nCopies(packages.size(),"?"))+") ORDER BY pl.id LIMIT 5001",ROW,args.toArray());
        if(lines.size()>5000){lines.remove(lines.size()-1);truncated=true;}
        Map<Long,Long> packed=new HashMap<>(),intercepted=new HashMap<>();for(Map<String,Object> l:lines)packed.merge(number(l,"shipment_line_id"),number(l,"quantity"),Long::sum);
        for(Map<String,Object> p:plans)if("INTERCEPT".equals(text(p,"return_mode"))&&!Arrays.asList("STOPPED","RELEASED","CANCELED").contains(text(p,"state")))intercepted.merge(number(p,"shipment_line_id"),number(p,"quantity"),Long::sum);
        for(Map<String,Object> l:lines){l.put("item_kind",number(l,"gift_plan_id")>0?"GIFT":"PURCHASE");l.put("in_refund_scope",text(b,"platform_oid").equals(text(l,"platform_oid"))||sourceIds.contains(number(l,"shipment_line_id")));l.put("intercept_covered",intercepted.getOrDefault(number(l,"shipment_line_id"),0L)>=packed.get(number(l,"shipment_line_id")));((List<Map<String,Object>>)byId.get(number(l,"package_id")).get("lines")).add(l);}
        for(Map<String,Object> p:packages)p.put("whole_package_covered",!truncated&&!rows(p.get("lines")).isEmpty()&&rows(p.get("lines")).stream().allMatch(l->Boolean.TRUE.equals(l.get("intercept_covered"))));
        return m("packages",packages,"truncated",truncated);
    }
}
