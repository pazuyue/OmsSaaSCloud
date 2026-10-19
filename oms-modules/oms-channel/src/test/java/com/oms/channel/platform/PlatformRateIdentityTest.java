package com.oms.channel.platform;
import org.junit.jupiter.api.Test;import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class PlatformRateIdentityTest {
 Map<String,Object> row(String company,String key,long id,boolean simulated){Map<String,Object> r=new HashMap<>();r.put("company_code",company);r.put("app_key",key);r.put("app_id",id);r.put("simulation_mode",simulated);r.put("expected_shop_id","SHOP1");return r;}
 @Test void sameRealApplicationSharesQuotaAcrossCompanyBindingsWithoutExposingAppKey(){List<String> a=PlatformRateIdentity.keys(row("A","actual-app",1,false)),b=PlatformRateIdentity.keys(row("B","actual-app",2,false));assertEquals(a,b);assertTrue(a.stream().allMatch(k->k.matches("[a-f0-9]{64}")));}
 @Test void simulatedApplicationHasStableExplicitIdentityAndRealEmptyAppIsRejected(){assertEquals(PlatformRateIdentity.keys(row("QM","",1,true)),PlatformRateIdentity.keys(row("qm","",1,true)));assertNotEquals(PlatformRateIdentity.keys(row("QM","",1,true)).get(0),PlatformRateIdentity.keys(row("QM","",2,true)).get(0));assertThrows(IllegalArgumentException.class,()->PlatformRateIdentity.keys(row("QM","",1,false)));}
}
