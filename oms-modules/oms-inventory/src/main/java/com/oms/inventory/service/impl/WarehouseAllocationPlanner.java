package com.oms.inventory.service.impl;

import java.util.*;

/** Bounded deterministic candidates, indexed stock lookup, no mutation of caller inventory. */
public final class WarehouseAllocationPlanner {
    private WarehouseAllocationPlanner(){}
    static long n(Map<String,Object> m,String k){Object v=m.get(k);return v==null?0:Long.parseLong(v.toString());}
    static String s(Map<String,Object> m,String k){Object v=m.get(k);return v==null?"":v.toString();}
    static void require(boolean b,String m){if(!b)throw new IllegalArgumentException(m);}
    public static long quantity(List<Map<String,Object>> rows){return rows.stream().mapToLong(r->n(r,"quantity")).sum();}
    static String key(String sku,String store){return sku.length()+":"+sku+store;}
    public static List<Map<String,Object>> plan(List<Map<String,Object>> sources,List<Map<String,Object>> batches,List<Map<String,Object>> stores,List<Map<String,Object>> existing,long required,boolean multi,boolean partial,boolean split){return plan(sources,batches,stores,existing,required,multi,partial,split,Collections.emptyMap());}
    public static List<Map<String,Object>> plan(List<Map<String,Object>> sources,List<Map<String,Object>> batches,List<Map<String,Object>> stores,List<Map<String,Object>> existing,long required,boolean multi,boolean partial,boolean split,Map<String,Object> policy){
        require(required>0&&stores.size()<=100,"分仓需求或候选仓数量无效");require(multi||!split,"同SKU跨仓需先允许多仓发货");
        String strategy=policy.containsKey("strategy")?s(policy,"strategy"):"FEWEST_SHIPMENTS";require(Arrays.asList("FEWEST_SHIPMENTS","REGION_FIRST").contains(strategy),"分仓策略无效");
        LinkedHashMap<String,Map<String,Object>> catalog=new LinkedHashMap<>();for(Map<String,Object> store:stores)catalog.put(s(store,"store_code"),store);
        Budget budget=new Budget(existing,policy,multi);long occupied=quantity(existing);require(occupied<=required,"累计分仓数量超过订单需求");budget.check(Collections.emptyList());if(occupied==required)return Collections.emptyList();
        require(budget.slots>0||!budget.pending.isEmpty(),"累计发货单数量已达到本单上限，请核对剩余需求");Index index=new Index(batches);List<Map<String,Object>> best=Collections.emptyList();
        if("REGION_FIRST".equals(strategy)){best=attempt(sources,index,catalog,existing,catalog.keySet(),split,partial,budget);if(best.isEmpty()&&!partial)for(String code:catalog.keySet()){List<Map<String,Object>> candidate=attempt(sources,index,catalog,existing,Collections.singleton(code),split,false,budget);if(quantity(candidate)+occupied==required)return candidate;}}
        else{
            // At most N single-store candidates plus two greedy multi-store candidates, never enumerate subsets.
            for(String code:catalog.keySet()){List<Map<String,Object>> candidate=attempt(sources,index,catalog,existing,Collections.singleton(code),split,partial,budget);if(quantity(candidate)+occupied==required)return candidate;if(better(candidate,best))best=candidate;}
            if(multi){List<Map<String,Object>> candidate=attempt(sources,index,catalog,existing,catalog.keySet(),split,partial,budget);if(better(candidate,best))best=candidate;
                Map<String,Long> needed=new HashMap<>();for(Map<String,Object> source:sources)needed.merge(s(source,"sku_sn"),n(source,"quantity"),Long::sum);
                List<String> coverage=new ArrayList<>(catalog.keySet());Map<String,Long> scores=new HashMap<>();for(String code:coverage){long score=0;for(Map.Entry<String,Long> demand:needed.entrySet())score+=Math.min(demand.getValue(),index.available(demand.getKey(),code));for(Map<String,Object> source:sources)if("LOCK".equals(s(source,"source_type"))&&code.equals(s(source,"store_code")))score+=n(source,"quantity");scores.put(code,score);}
                coverage.sort((a,b)->Long.compare(scores.get(b),scores.get(a)));candidate=attempt(sources,index,catalog,existing,new LinkedHashSet<>(coverage),split,partial,budget);if(better(candidate,best))best=candidate;
            }
        }
        long planned=quantity(best);require(planned>0&&(partial||planned+occupied==required),"当前库存来源、跨仓限制或拆分上限下未找到可执行方案，请查看候选仓明细");
        long percent=n(policy,"min_first_allocation_percent");require(percent>=0&&percent<=100,"首次分仓比例无效");require(occupied>0||planned>=java.math.BigDecimal.valueOf(required).multiply(java.math.BigDecimal.valueOf(percent)).divide(java.math.BigDecimal.valueOf(100),0,java.math.RoundingMode.CEILING).longValueExact(),"本次可分仓数量未达到首次最低满足比例（"+percent+"%），暂不占用仓库库存");budget.check(best);return best;
    }
    private static boolean better(List<Map<String,Object>> candidate,List<Map<String,Object>> best){long a=quantity(candidate),b=quantity(best);return a>b||(a==b&&storeCount(candidate)<storeCount(best));}
    private static int storeCount(List<Map<String,Object>> rows){Set<String> codes=new HashSet<>();for(Map<String,Object> row:rows)codes.add(s(row,"store_code"));return codes.size();}
    private static final class Index{
        final Map<String,Map<Long,Long>> free=new HashMap<>();final Map<String,List<Map<String,Object>>> rows=new HashMap<>();final Map<String,Long> totals=new HashMap<>();
        Index(List<Map<String,Object>> batches){for(Map<String,Object> batch:batches){free.computeIfAbsent(s(batch,"sku_sn"),k->new HashMap<>()).put(n(batch,"id"),n(batch,"available"));String key=key(s(batch,"sku_sn"),s(batch,"store_code"));rows.computeIfAbsent(key,k->new ArrayList<>()).add(batch);totals.merge(key,n(batch,"available"),Long::sum);}}
        long available(String sku,String store){return totals.getOrDefault(key(sku,store),0L);}
    }
    private static final class Budget{
        final Set<String> used=new HashSet<>(),pending=new HashSet<>();final long warehouses,slots;final List<Map<String,Object>> inherited=new ArrayList<>();
        @SuppressWarnings("unchecked") Budget(List<Map<String,Object>> existing,Map<String,Object> policy,boolean multi){if(policy.get("global_fulfillment") instanceof List){require(((List<?>)policy.get("global_fulfillment")).size()<=10000,"累计履约来源数量过多");for(Object row:(List<?>)policy.get("global_fulfillment")){require(row instanceof Map,"累计履约来源无效");inherited.add((Map<String,Object>)row);used.add(s((Map<String,Object>)row,"store_code"));}}for(Map<String,Object> row:existing)used.add(s(row,"store_code"));long max=n(policy,"max_virtual_warehouses"),ship=n(policy,"max_shipments");require(max>=0&&max<=100&&ship>=0&&ship<=100,"分仓上限无效");warehouses=multi?(max==0?100:max):1;
            if(ship>0)require(policy.containsKey("existing_shipment_count")&&policy.get("mapped_allocation_ids") instanceof Collection,"缺少累计发货单核对信息，请刷新后重试");
            Set<Long> mapped=new HashSet<>();if(policy.get("mapped_allocation_ids") instanceof Collection)for(Object id:(Collection<?>)policy.get("mapped_allocation_ids"))mapped.add(Long.parseLong(id.toString()));
            if(ship>0)for(Map<String,Object> row:existing)if(!mapped.contains(n(row,"id")))pending.add(s(row,"store_code"));
            slots=ship==0?Integer.MAX_VALUE:ship-n(policy,"existing_shipment_count");
        }
        boolean allows(String code,Set<String> selected){Set<String> all=new HashSet<>(used);all.addAll(selected);all.add(code);Set<String> wave=new HashSet<>(pending);wave.addAll(selected);wave.add(code);return all.size()<=warehouses&&wave.size()<=slots;}
        void check(List<Map<String,Object>> rows){Set<String> all=new HashSet<>(used),wave=new HashSet<>(pending);for(Map<String,Object> row:rows){all.add(s(row,"store_code"));wave.add(s(row,"store_code"));}require(all.size()<=warehouses,"已使用虚拟仓数量超过本单规则上限");require(wave.size()<=slots,"累计发货单数量已达到本单上限，请核对剩余需求");}
    }
    private static List<Map<String,Object>> attempt(List<Map<String,Object>> sources,Index index,Map<String,Map<String,Object>> catalog,List<Map<String,Object>> existing,Set<String> allowed,boolean split,boolean partial,Budget budget){
        Map<String,List<Map<String,Object>>> bySku=new TreeMap<>();for(Map<String,Object> source:sources)bySku.computeIfAbsent(s(source,"sku_sn"),k->new ArrayList<>()).add(source);List<Map<String,Object>> out=new ArrayList<>();
        for(Map.Entry<String,List<Map<String,Object>>> group:bySku.entrySet()){
            Map<Long,Long> free=index.free.getOrDefault(group.getKey(),Collections.emptyMap());Set<String> fixed=new HashSet<>(),selectedStores=new HashSet<>();for(Map<String,Object> row:out)selectedStores.add(s(row,"store_code"));for(Map<String,Object> row:existing)if(group.getKey().equals(s(row,"sku_sn")))fixed.add(s(row,"store_code"));for(Map<String,Object> row:budget.inherited)if(group.getKey().equals(s(row,"sku_sn")))fixed.add(s(row,"store_code"));for(Map<String,Object> source:group.getValue())if("LOCK".equals(s(source,"source_type")))fixed.add(s(source,"store_code"));
            List<Map<String,Object>> selected=null;Map<Long,Long> next=null;
            if(split){next=new HashMap<>(free);selected=allocate(group.getValue(),index,catalog,allowed,next,partial,budget,selectedStores);}
            else{
                for(String code:allowed){if(!fixed.isEmpty()&&!(fixed.size()==1&&fixed.contains(code)))continue;Map<Long,Long> trial=new HashMap<>(free);List<Map<String,Object>> candidate=allocate(group.getValue(),index,catalog,Collections.singleton(code),trial,false,budget,selectedStores);if(candidate!=null){selected=candidate;next=trial;break;}}
                if(selected==null&&partial)for(String code:allowed){if(!fixed.isEmpty()&&!(fixed.size()==1&&fixed.contains(code)))continue;Map<Long,Long> trial=new HashMap<>(free);List<Map<String,Object>> candidate=allocate(group.getValue(),index,catalog,Collections.singleton(code),trial,true,budget,selectedStores);if(selected==null||quantity(candidate)>quantity(selected)){selected=candidate;next=trial;}}
            }
            if(selected==null){if(!partial)return Collections.emptyList();continue;}out.addAll(selected);
        }return out;
    }
    private static List<Map<String,Object>> allocate(List<Map<String,Object>> sources,Index index,Map<String,Map<String,Object>> catalog,Set<String> allowed,Map<Long,Long> free,boolean partial,Budget budget,Set<String> priorStores){
        List<Map<String,Object>> out=new ArrayList<>();Set<String> selected=new HashSet<>(priorStores);
        for(Map<String,Object> source:sources){long left=n(source,"quantity");
            if("LOCK".equals(s(source,"source_type"))){String code=s(source,"store_code");Map<String,Object> store=catalog.get(code);if(store==null||!allowed.contains(code)||!budget.allows(code,selected)){if(partial)continue;return null;}out.add(receipt(source,store,n(source,"batch_id"),left));selected.add(code);continue;}
            for(String code:allowed){if(!budget.allows(code,selected))continue;for(Map<String,Object> batch:index.rows.getOrDefault(key(s(source,"sku_sn"),code),Collections.emptyList())){long take=Math.min(left,free.get(n(batch,"id")));if(take<=0)continue;out.add(receipt(source,catalog.get(code),n(batch,"id"),take));selected.add(code);free.put(n(batch,"id"),free.get(n(batch,"id"))-take);left-=take;if(left==0)break;}if(left==0)break;}
            if(left>0&&!partial)return null;
        }return out;
    }
    private static Map<String,Object> receipt(Map<String,Object> source,Map<String,Object> store,long batch,long quantity){Map<String,Object> row=new LinkedHashMap<>(store);row.put("stock_source_id",source.get("id"));row.put("demand_id",source.get("demand_id"));row.put("sku_sn",source.get("sku_sn"));row.put("source_type",source.get("source_type"));row.put("batch_id",batch);row.put("quantity",quantity);return row;}
}
