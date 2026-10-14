package com.oms.common.task;
import java.util.*;
/** Bounded business-only scan evidence. Never includes request bodies or credentials. */
public class ScanReport extends LinkedHashMap<String,Object> {
    public ScanReport(){for(String key:Arrays.asList("scanned","succeeded","failed","unconfirmed","pending","skipped","review_pending","scope_failures","omitted"))put(key,0L);put("items",new ArrayList<Map<String,Object>>());}
    public void add(String key,long count){put(key,((Number)get(key)).longValue()+count);}
    @SuppressWarnings("unchecked") public void record(String company,long reference,String sn,String state,String message){
        add("scanned",1);add("SUCCESS".equals(state)?"succeeded":"FAILED".equals(state)?"failed":"UNKNOWN".equals(state)?"unconfirmed":"SKIPPED".equals(state)?"skipped":"pending",1);
        note(company,reference,sn,state,message);
    }
    @SuppressWarnings("unchecked") public void note(String company,long reference,String sn,String state,String message){
        Map<String,Object> item=new LinkedHashMap<>();item.put("company_code",company);item.put("reference_id",reference);item.put("reference_sn",sn);item.put("state",state);item.put("message",message);
        List<Map<String,Object>> items=(List<Map<String,Object>>)get("items");if(items.size()<50)items.add(item);else add("omitted",1);
    }
    public void scopeFailure(String company){add("scope_failures",1);note(company,0,"","FAILED","扫描未完成，请检查服务日志或租户路由后重试");}
    @SuppressWarnings("unchecked") public void merge(Map<String,Object> other){for(String key:Arrays.asList("scanned","succeeded","failed","unconfirmed","pending","skipped","review_pending","scope_failures","omitted"))if(other.get(key) instanceof Number)add(key,((Number)other.get(key)).longValue());List<Map<String,Object>> items=(List<Map<String,Object>>)get("items");for(Map<String,Object> item:(List<Map<String,Object>>)other.getOrDefault("items",Collections.emptyList()))if(items.size()<50)items.add(item);else add("omitted",1);}
}
