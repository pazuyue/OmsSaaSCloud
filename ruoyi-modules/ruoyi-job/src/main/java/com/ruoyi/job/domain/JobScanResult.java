package com.ruoyi.job.domain;
import java.util.*;
/** Only explicit scan reports can enrich the public scheduler log. */
public class JobScanResult {
    private final Map<String,Object> data=new LinkedHashMap<>();
    public JobScanResult(String kind,Map<String,Object> input){
        if(input==null)throw new IllegalStateException("任务服务未返回执行统计");data.put("kind",kind);
        for(String key:Arrays.asList("scanned","succeeded","failed","unconfirmed","pending","skipped","review_pending","scope_failures","omitted")){if(!(input.get(key) instanceof Number))throw new IllegalStateException("任务统计不完整");data.put(key,Math.max(0,((Number)input.get(key)).longValue()));}
        List<Map<String,Object>> items=new ArrayList<>();Object raw=input.get("items");if(raw instanceof List)for(Object value:(List<?>)raw){if(items.size()==50)break;if(!(value instanceof Map))continue;Map<?,?> item=(Map<?,?>)value;Map<String,Object> safe=new LinkedHashMap<>();for(String key:Arrays.asList("company_code","reference_sn","state","message")){String text=Objects.toString(item.get(key),"");safe.put(key,text.substring(0,Math.min(text.length(),"message".equals(key)?200:100)));}Object id=item.get("reference_id");safe.put("reference_id",id instanceof Number?((Number)id).longValue():0L);items.add(safe);}data.put("items",items);
    }
    public Map<String,Object> getData(){return data;}
    public boolean hasFailures(){return ((Number)data.get("failed")).longValue()>0||((Number)data.get("scope_failures")).longValue()>0;}
    public String summary(){return "扫描 "+data.get("scanned")+"，成功 "+data.get("succeeded")+"，失败 "+data.get("failed")+"，结果待确认 "+data.get("unconfirmed")+"，仍待处理 "+data.get("pending")+"，跳过 "+data.get("skipped")+"；待人工核对存量 "+data.get("review_pending")+"，扫描范围异常 "+data.get("scope_failures");}
}
