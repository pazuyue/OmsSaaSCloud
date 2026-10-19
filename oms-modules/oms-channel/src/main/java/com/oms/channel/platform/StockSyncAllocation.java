package com.oms.channel.platform;
import java.util.*;
import static com.oms.channel.platform.PlatformInteractionLog.*;
/** Integer, capped proportional allocation. No per-piece loops or inventory writes. */
public final class StockSyncAllocation {
 private StockSyncAllocation(){}
 public static List<Map<String,Object>> distribute(long available,long safety,Long cap,List<Map<String,Object>> links){
  require(available>=0&&safety>=0&&links.size()<=20,"同步数量或链接数无效");long budget=Math.max(0,available-safety);if(cap!=null){require(cap>=0,"同步上限无效");budget=Math.min(budget,cap);}
  List<Map<String,Object>> out=new ArrayList<>();long weights=0,total=0;
  for(Map<String,Object> link:links){if(n(link,"enabled")!=1)continue;long weight=n(link,"weight");require(weight>0&&weight<=10000,"链接分配权重无效");weights+=weight;}
  require(weights>0,"至少启用一个销售链接");
  for(Map<String,Object> link:links){if(n(link,"enabled")!=1)continue;Map<String,Object> row=new LinkedHashMap<>(link);long numerator=Math.multiplyExact(budget,n(link,"weight")),target=numerator/weights;Long limit=link.get("quantity_cap")==null?null:Long.valueOf(s(link,"quantity_cap"));require(limit==null||limit>=0,"链接上限无效");if(limit!=null)target=Math.min(target,limit);row.put("target",target);row.put("remainder",numerator%weights);out.add(row);total+=target;}
  // Distribute rounding residual only; caps never silently transfer another link's allocation.
  long residual=Math.min(budget-total,out.size());List<Map<String,Object>> ranked=new ArrayList<>(out);ranked.sort(Comparator.<Map<String,Object>>comparingLong(r->n(r,"remainder")).reversed().thenComparingLong(r->n(r,"id")));
  for(Map<String,Object> row:ranked){if(residual==0)break;long target=n(row,"target"),weight=n(row,"weight");long floor=Math.multiplyExact(budget,weight)/weights;if(target==floor&&n(row,"remainder")>0&&(row.get("quantity_cap")==null||target<Long.parseLong(s(row,"quantity_cap")))){row.put("target",target+1);residual--;}}
  return out;
 }
}
