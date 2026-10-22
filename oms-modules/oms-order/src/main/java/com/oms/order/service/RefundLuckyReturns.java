package com.oms.order.service;
import java.util.*;
import static com.oms.order.service.OrderValues.*;
import static com.oms.order.service.RefundValues.*;

/** Select explicit original bag instances and expand only their actual shipped stock sources. */
final class RefundLuckyReturns {
    private RefundLuckyReturns(){}
    static List<Long> unitIds(Map<String,Object> input){List<Long> ids=new ArrayList<>();Object raw=input.get("unit_ids");if(raw==null)return ids;require(raw instanceof List&&((List<?>)raw).size()<=50,"请选择最多50个原袋实例");for(Object id:(List<?>)raw){long value;try{value=Long.parseLong(id.toString());}catch(Exception e){throw new IllegalArgumentException("原袋实例无效");}require(value>0&&!ids.contains(value),"原袋实例无效或重复");ids.add(value);}Collections.sort(ids);return ids;}
    static List<Map<String,Object>> requests(OrderRepository r,String c,Map<String,Object> refund,Map<String,Object> parent,Map<String,Object> input){
        long units=number(input,"quantity"),bundle=number(parent,"id"),id=number(refund,"id");Map<String,Object> snapshot=obj(parent.get("snapshot"));List<Map<String,Object>> originals=com.oms.common.lucky.LuckyBagRules.rows(snapshot.get("units"));List<Long> selected=unitIds(input);
        if(selected.isEmpty()){require(units==number(parent,"quantity"),"部分整袋退回必须明确选择原袋号，不能重新随机确定退回内容");for(Map<String,Object> unit:originals)selected.add(number(unit,"unit_id"));Collections.sort(selected);}
        require(selected.size()==units,"选中的原袋数与应退袋数不一致");Map<Long,Long> quantities=new TreeMap<>();Map<Long,String> skus=new HashMap<>();Set<Long> found=new HashSet<>();
        for(Map<String,Object> unit:originals)if(selected.contains(number(unit,"unit_id"))){long unitId=number(unit,"unit_id");found.add(unitId);require(r.db().queryForObject("SELECT COUNT(*) FROM oms_refund_lucky_unit_claim WHERE company_code=? AND bundle_id=? AND unit_id=?",Long.class,c,bundle,unitId)==0,"原袋已被其他整袋退回安排占用：袋"+unit.get("unit"));for(Map<String,Object> component:com.oms.common.lucky.LuckyBagRules.rows(unit.get("components"))){long source=number(component,"stock_source_id");require(source>0,"原袋组件来源缺失");quantities.merge(source,number(component,"quantity"),Long::sum);skus.put(source,text(component,"sku_sn"));}}
        require(found.size()==selected.size(),"选中的原袋不属于该销售行");List<Map<String,Object>> requests=new ArrayList<>();
        for(Map.Entry<Long,Long> entry:quantities.entrySet()){long left=entry.getValue();List<Map<String,Object>> sources=r.db().query("SELECT sl.* FROM oms_order_shipment_line sl JOIN oms_order_line ol ON ol.company_code=sl.company_code AND ol.id=sl.order_line_id WHERE sl.company_code=? AND ol.order_id=? AND ol.bundle_id=? AND sl.stock_source_id=? AND sl.sku_sn=? AND sl.shipped_quantity>0 ORDER BY sl.id FOR UPDATE",ROW,c,refund.get("order_id"),bundle,entry.getKey(),skus.get(entry.getKey()));
            for(Map<String,Object> source:sources){require(r.db().queryForObject("SELECT COUNT(*) FROM oms_refund_return_plan WHERE company_code=? AND refund_id=? AND shipment_line_id=?",Long.class,c,id,source.get("id"))==0,"本退款已有该原组件安排，请继续原安排或按指定组件处理");long capacity=number(source,"shipped_quantity")-ReturnSourceClaims.refundUsed(r,c,number(source,"id"),0,id)-ReturnSourceClaims.exchangeUsed(r,c,number(source,"id")),take=Math.min(left,Math.max(0,capacity));if(take>0){requests.add(m("shipment_line_id",source.get("id"),"sku_sn",source.get("sku_sn"),"goods_name",source.get("goods_name"),"quantity",take,"request_key","LB"+id+"_"+bundle+"_"+source.get("id")));left-=take;}if(left==0)break;}require(left==0,"选中原袋的组件尚未完整出库，或已被其他售后占用，请核对实际来源");
        }
        // Different units may share a stock source; merge each physical shipment line once.
        Map<Long,Map<String,Object>> merged=new LinkedHashMap<>();for(Map<String,Object> request:requests){long line=number(request,"shipment_line_id");if(!merged.containsKey(line))merged.put(line,new LinkedHashMap<>(request));else merged.get(line).put("quantity",number(merged.get(line),"quantity")+number(request,"quantity"));}
        input.put("unit_ids",selected);for(Long unit:selected)r.db().update("INSERT INTO oms_refund_lucky_unit_claim(company_code,bundle_id,unit_id,refund_id) VALUES (?,?,?,?)",c,bundle,unit,id);return new ArrayList<>(merged.values());
    }
}
