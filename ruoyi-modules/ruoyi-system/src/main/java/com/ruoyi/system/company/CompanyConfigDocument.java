package com.ruoyi.system.company;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import java.util.*;
import static com.ruoyi.system.company.CompanyServices.*;
/** Operates on one company's three keys; unrelated configuration and secrets never enter the ledger. */
public final class CompanyConfigDocument {
    private final Map<String,Object> root;
    @SuppressWarnings("unchecked") public CompanyConfigDocument(String raw){Object parsed=new Yaml(new SafeConstructor()).load(raw);require(parsed instanceof Map,"服务配置格式无效");root=(Map<String,Object>)parsed;}
    @SuppressWarnings("unchecked") private Map<String,Object> child(Map<String,Object> parent,String key){Object value=parent.get(key);if(value==null){Map<String,Object> created=new LinkedHashMap<>();parent.put(key,created);return created;}require(value instanceof Map,"服务路由配置格式无效");return (Map<String,Object>)value;}
    private Map<String,Object> property(String name){Map<String,Object> nested=child(child(child(root,"oms"),"tenant"),name);for(String key:new ArrayList<>(nested.keySet())){String normalized=company(key);if(!key.equals(normalized)){require(!nested.containsKey(normalized),"存在重复的公司路由定义");nested.put(normalized,nested.remove(key));}}String prefix="oms.tenant."+name+".";for(String key:new ArrayList<>(root.keySet()))if(key.startsWith(prefix)){String company=company(key.substring(prefix.length()));require(!nested.containsKey(company)||Objects.equals(nested.get(company),root.get(key)),"存在冲突的公司路由定义");nested.put(company,root.remove(key));}return nested;}
    public Map<String,Object> slot(String company){return map("alias",property("routes").get(company),"version",property("versions").get(company),"managed",property("managed").get(company));}
    public String patch(String company,Map<String,Object> slot){for(String field:Arrays.asList("alias","version","managed")){String name="alias".equals(field)?"routes":"version".equals(field)?"versions":"managed";Map<String,Object> values=property(name);if(slot.get(field)==null)values.remove(company);else values.put(company,slot.get(field));}return new Yaml().dump(root);}
}
