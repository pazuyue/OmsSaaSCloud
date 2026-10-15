package com.ruoyi.system.company;
import org.springframework.stereotype.Component;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.RedisCallback;
import java.nio.charset.StandardCharsets;
import javax.annotation.Resource;
import java.util.*;
/** Revision fencing prevents a delayed enable from overwriting a newer disable. */
@Component
public class CompanyStateStore {
    @Resource private StringRedisTemplate redis;
    private static final DefaultRedisScript<Long> WRITE=new DefaultRedisScript<>("local old=redis.call('GET',KEYS[1]); if not old or string.sub(old,1,20)<=string.sub(ARGV[1],1,20) then redis.call('SET',KEYS[1],ARGV[1]); return 1 end return 0",Long.class);
    private String value(long revision,String status){CompanyServices.require(revision>0&&Arrays.asList("DRAFT","ENABLED","DISABLED").contains(status),"公司执行开关无效");return String.format(Locale.ROOT,"%020d|%s",revision,status);}
    public boolean write(String company,long revision,String status){Long changed=redis.execute(WRITE,Collections.singletonList("oms:company:state:"+CompanyServices.company(company)),value(revision,status));return Long.valueOf(1).equals(changed);}
    public String state(String company){String raw=redis.opsForValue().get("oms:company:state:"+CompanyServices.company(company));return raw!=null&&raw.matches("[0-9]{20}\\|(DRAFT|ENABLED|DISABLED)")?raw.substring(21):"UNSYNCED";}
    public void warm(Map<String,Object> company){redis.opsForValue().setIfAbsent("oms:company:state:"+company.get("company_code"),value(CompanyServices.number(company,"revision"),CompanyServices.text(company,"status")));}
    public void warmBatch(List<Map<String,Object>> companies){redis.executePipelined((RedisCallback<Object>) connection->{for(Map<String,Object> company:companies)connection.setNX(("oms:company:state:"+company.get("company_code")).getBytes(StandardCharsets.UTF_8),value(CompanyServices.number(company,"revision"),CompanyServices.text(company,"status")).getBytes(StandardCharsets.UTF_8));return null;});}
}
