package com.oms.common.tenant;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import javax.annotation.Resource;
/** Runtime switch on the existing Redis infrastructure. No system-service RPC in business requests. */
@Component
public class TenantCompanyAccess {
    @Resource private StringRedisTemplate redis;
    private String decode(String value){return value!=null&&value.matches("[0-9]{20}\\|(DRAFT|ENABLED|DISABLED)")?value.substring(21):"UNSYNCED";}
    public String state(String company){String value=decode(redis.opsForValue().get("oms:company:state:"+TenantRouting.company(company)));if("UNSYNCED".equals(value))throw new IllegalStateException("公司执行开关尚未同步，请联系平台管理员");return value;}
    public java.util.Map<String,String> states(java.util.Collection<String> companies){java.util.List<String> keys=new java.util.ArrayList<>();for(String company:companies)keys.add("oms:company:state:"+TenantRouting.company(company));java.util.List<String> values=redis.opsForValue().multiGet(keys);if(values==null||values.size()!=keys.size())throw new IllegalStateException("公司执行开关暂时无法核实");java.util.Map<String,String> result=new java.util.LinkedHashMap<>();int i=0;for(String company:companies)result.put(company,decode(values.get(i++)));return result;}
    public boolean isEnabled(String company){return "ENABLED".equals(state(company));}
    public void requireEnabled(String company){if(!isEnabled(company))throw new IllegalArgumentException("公司尚未启用或已停用，不能新增业务");}
    public void requireExisting(String company){String value=state(company);if(!"ENABLED".equals(value)&&!"DISABLED".equals(value))throw new IllegalArgumentException("公司尚未完成入驻启用");}
}
