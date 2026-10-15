package com.ruoyi.system.company;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.ruoyi.system.company.CompanyServices.*;
class CompanyStateStoreTest {
    @Test void realRedisRevisionFenceAndWarmupNeverOverwriteNewerDisable(){
        String host=System.getenv("COMPANY_REDIS_TEST_HOST");Assumptions.assumeTrue(host!=null);LettuceConnectionFactory factory=new LettuceConnectionFactory(host,6379);factory.setDatabase(15);factory.afterPropertiesSet();
        StringRedisTemplate redis=new StringRedisTemplate(factory);CompanyStateStore states=new CompanyStateStore();ReflectionTestUtils.setField(states,"redis",redis);
        String company="UNIT_STATE_"+UUID.randomUUID().toString().replace("-","").toUpperCase(Locale.ROOT),key="oms:company:state:"+company;
        try{assertTrue(states.write(company,10,"ENABLED"));assertTrue(states.write(company,11,"DISABLED"));assertFalse(states.write(company,10,"ENABLED"));assertEquals("DISABLED",states.state(company));states.warmBatch(Collections.singletonList(map("company_code",company,"revision",10,"status","ENABLED")));assertEquals("DISABLED",states.state(company));assertTrue(states.write(company,12,"ENABLED"));assertEquals("ENABLED",states.state(company));}finally{redis.delete(key);factory.destroy();}
    }
}
