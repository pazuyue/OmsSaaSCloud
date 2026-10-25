package com.oms.order.service;
import java.util.*;
import static com.oms.order.service.OrderValues.*;
import static com.oms.order.service.RefundValues.*;
/** Sales-unit quantities for gift eligibility. Never sums kit components into a number of sold bags. */
final class RefundMixedScope {
 private RefundMixedScope(){}
 static Map<Long,Map<String,Object>> sales(OrderRepository r,String c,long order){
  List<Map<String,Object>> tasks=r.db().query("SELECT t.refund_id,t.order_line_id,t.quantity,t.state,l.platform_oid,l.bundle_id FROM oms_refund_goods_task t JOIN oms_order_line l ON l.company_code=t.company_code AND l.id=t.order_line_id WHERE t.company_code=? AND t.order_id=? AND t.state<>'CANCELED' ORDER BY t.refund_id,t.id",ROW,c,order);
  Map<Long,List<Map<String,Object>>> groups=new LinkedHashMap<>();for(Map<String,Object> task:tasks)groups.computeIfAbsent(number(task,"refund_id"),k->new ArrayList<>()).add(task);Map<Long,Map<String,Object>> out=new HashMap<>();if(groups.isEmpty())return out;
  List<Map<String,Object>> lines=r.db().query("SELECT l.id,l.platform_oid,l.bundle_id,l.quantity,l.component_quantity,k.quantity sales_quantity FROM oms_order_line l LEFT JOIN oms_order_bundle k ON k.company_code=l.company_code AND k.id=l.bundle_id WHERE l.company_code=? AND l.order_id=? AND l.gift_plan_id IS NULL AND l.exchange_id IS NULL AND l.business_type='SALE' ORDER BY l.id",ROW,c,order);
  for(Map.Entry<Long,List<Map<String,Object>>> entry:groups.entrySet()){Map<Long,Long> claimed=new HashMap<>();Set<Long> bundles=new HashSet<>();Set<String> oids=new HashSet<>();boolean done=true;long normal=0;for(Map<String,Object> task:entry.getValue()){claimed.merge(number(task,"order_line_id"),number(task,"quantity"),Long::sum);oids.add(text(task,"platform_oid"));if(task.get("bundle_id")!=null)bundles.add(number(task,"bundle_id"));else normal+=number(task,"quantity");done&="DONE".equals(text(task,"state"));}
   long sold=normal;boolean ambiguous=false;for(Long bundle:bundles){List<Map<String,Object>> original=new ArrayList<>();for(Map<String,Object> line:lines)if(number(line,"bundle_id")==bundle&&oids.contains(text(line,"platform_oid")))original.add(line);long units=bundleUnits(original,claimed);if(units>=0)sold+=units;else ambiguous=true;}out.put(entry.getKey(),m("quantity",sold,"ambiguous",ambiguous,"done",done));
  }return out;
 }
 static long bundleUnits(List<Map<String,Object>> lines,Map<Long,Long> claimed){
  if(lines.isEmpty())return -1;long sales=number(lines.get(0),"sales_quantity"),units=-1;boolean full=true,regular=true;
  for(Map<String,Object> line:lines){long actual=claimed.getOrDefault(number(line,"id"),0L),per=number(line,"component_quantity");if(actual<0||actual>number(line,"quantity"))return -1;full&=actual==number(line,"quantity");if(per<=0||actual%per!=0){regular=false;continue;}long current=actual/per;if(units<0)units=current;else if(units!=current)regular=false;}
  if(full)return sales;return regular&&units>=0&&units<=sales?units:-1;
 }
}
