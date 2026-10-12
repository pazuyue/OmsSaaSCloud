package com.oms.inventory.service.impl;

import java.util.*;

/** Deterministic warehouse plan. No writes until a valid whole/partial plan is selected. */
public final class WarehouseAllocationPlanner {
    private WarehouseAllocationPlanner(){}
    static long n(Map<String,Object> m,String k){Object v=m.get(k);return v==null?0:Long.parseLong(v.toString());}
    static String s(Map<String,Object> m,String k){Object v=m.get(k);return v==null?"":v.toString();}
    static void require(boolean b,String m){if(!b)throw new IllegalArgumentException(m);}
    private static long quantity(List<Map<String,Object>> rows){return rows.stream().mapToLong(r->n(r,"quantity")).sum();}
    public static List<Map<String,Object>> plan(List<Map<String,Object>> sources,List<Map<String,Object>> batches,
            List<Map<String,Object>> stores,List<Map<String,Object>> existing,long required,boolean multi,boolean partial,boolean split){
        LinkedHashSet<String> reals=new LinkedHashSet<>();Map<String,Map<String,Object>> catalog=new LinkedHashMap<>();
        for(Map<String,Object> store:stores){catalog.put(s(store,"store_code"),store);reals.add(s(store,"real_store_code"));}
        Set<String> used=new LinkedHashSet<>();for(Map<String,Object> receipt:existing)used.add(s(receipt,"real_store_code"));
        require(multi||used.size()<=1,"已有多仓分仓结果，与单仓策略冲突");
        long occupied=quantity(existing);if(occupied==required)return Collections.emptyList();
        List<Map<String,Object>> best=Collections.emptyList();
        // Prefer one warehouse able to fulfill the whole order; priority resolves ties.
        for(String real:reals){if(!used.isEmpty()&&!(used.size()==1&&used.contains(real)))continue;
            List<Map<String,Object>> candidate=attempt(sources,batches,catalog,existing,Collections.singleton(real),split,partial);
            if(quantity(candidate)+occupied==required)return candidate;
            if(quantity(candidate)>quantity(best))best=candidate;
        }
        if(multi){List<Map<String,Object>> candidate=attempt(sources,batches,catalog,existing,reals,split,partial);
            if(quantity(candidate)+occupied==required)return candidate;
            if(quantity(candidate)>quantity(best))best=candidate;
        }
        require(partial&&quantity(best)>0,"允许仓库存或原锁库仓不满足分仓策略；请补货或调整多仓、部分发货、同SKU跨仓设置");return best;
    }
    private static List<Map<String,Object>> attempt(List<Map<String,Object>> sources,List<Map<String,Object>> batches,
            Map<String,Map<String,Object>> catalog,List<Map<String,Object>> existing,Set<String> reals,boolean split,boolean partial){
        Map<Long,Long> free=new LinkedHashMap<>();for(Map<String,Object> batch:batches)free.put(n(batch,"id"),n(batch,"available"));
        Map<String,List<Map<String,Object>>> bySku=new TreeMap<>();for(Map<String,Object> source:sources)bySku.computeIfAbsent(s(source,"sku_sn"),k->new ArrayList<>()).add(source);
        List<Map<String,Object>> out=new ArrayList<>();
        for(Map.Entry<String,List<Map<String,Object>>> group:bySku.entrySet()){
            Set<String> fixed=new LinkedHashSet<>();for(Map<String,Object> receipt:existing)if(group.getKey().equals(s(receipt,"sku_sn")))fixed.add(s(receipt,"real_store_code"));
            for(Map<String,Object> source:group.getValue())if("LOCK".equals(s(source,"source_type"))&&catalog.containsKey(s(source,"store_code")))fixed.add(s(catalog.get(s(source,"store_code")),"real_store_code"));
            List<Map<String,Object>> selected=null;Map<Long,Long> next=null;
            if(split){next=new LinkedHashMap<>(free);selected=allocate(group.getValue(),batches,catalog,reals,next,partial);}
            else for(String real:reals){if(!fixed.isEmpty()&&!(fixed.size()==1&&fixed.contains(real)))continue;
                Map<Long,Long> trial=new LinkedHashMap<>(free);List<Map<String,Object>> candidate=allocate(group.getValue(),batches,catalog,Collections.singleton(real),trial,false);
                if(candidate!=null){selected=candidate;next=trial;break;}}
            if(!split&&selected==null&&partial)for(String real:reals){if(!fixed.isEmpty()&&!(fixed.size()==1&&fixed.contains(real)))continue;
                Map<Long,Long> trial=new LinkedHashMap<>(free);List<Map<String,Object>> candidate=allocate(group.getValue(),batches,catalog,Collections.singleton(real),trial,true);
                if(selected==null||quantity(candidate)>quantity(selected)){selected=candidate;next=trial;}}
            if(selected==null){if(!partial)return Collections.emptyList();continue;}
            out.addAll(selected);free=next;
        }
        return out;
    }
    private static List<Map<String,Object>> allocate(List<Map<String,Object>> sources,List<Map<String,Object>> batches,
            Map<String,Map<String,Object>> catalog,Set<String> reals,Map<Long,Long> free,boolean partial){
        List<Map<String,Object>> out=new ArrayList<>();
        for(Map<String,Object> source:sources){long left=n(source,"quantity");
            if("LOCK".equals(s(source,"source_type"))){Map<String,Object> store=catalog.get(s(source,"store_code"));
                if(store==null||!reals.contains(s(store,"real_store_code"))){if(partial)continue;return null;}
                out.add(receipt(source,store,n(source,"batch_id"),left));continue;}
            for(Map<String,Object> store:catalog.values()){if(!reals.contains(s(store,"real_store_code")))continue;
                for(Map<String,Object> batch:batches){if(!s(source,"sku_sn").equals(s(batch,"sku_sn"))||!s(store,"store_code").equals(s(batch,"store_code")))continue;
                    long take=Math.min(left,free.get(n(batch,"id")));if(take<=0)continue;
                    out.add(receipt(source,store,n(batch,"id"),take));free.put(n(batch,"id"),free.get(n(batch,"id"))-take);left-=take;if(left==0)break;}
                if(left==0)break;}
            if(left>0&&!partial)return null;
        }
        return out;
    }
    private static Map<String,Object> receipt(Map<String,Object> source,Map<String,Object> store,long batch,long quantity){
        Map<String,Object> r=new LinkedHashMap<>(store);r.put("stock_source_id",source.get("id"));r.put("demand_id",source.get("demand_id"));r.put("sku_sn",source.get("sku_sn"));r.put("source_type",source.get("source_type"));r.put("batch_id",batch);r.put("quantity",quantity);return r;
    }
}
