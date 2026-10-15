package com.ruoyi.system.company;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static com.ruoyi.system.company.CompanyServices.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CompanyOnboardingTest {
    CompanyOnboardingService service; JdbcTemplate db; CompanyRuntimeProbe runtime; CompanyStateStore states; FakeConfig configs;
    static class FakeConfig implements CompanyConfigTransport {
        Map<String,String> data=new LinkedHashMap<>();String fail=""; boolean external;
        FakeConfig(){for(String key:APPS.keySet())data.put(key,"secret: unchanged\noms:\n  tenant:\n    routes:\n      QM: master\n");}
        public String read(String key){return data.get(key);}
        public boolean compareWrite(String key,String original,String updated){if(key.equals(fail)){fail="";if(external)data.put("order",new CompanyConfigDocument(data.get("order")).patch("UNIT_A",map("alias","external","version","external","managed",true)));return false;}if(!Objects.equals(original,data.get(key)))return false;data.put(key,updated);return true;}
    }
    @BeforeEach void setup(){
        String url=System.getenv("COMPANY_TEST_URL");Assumptions.assumeTrue(url!=null&&url.contains("company_onboarding_test"));
        db=new JdbcTemplate(new DriverManagerDataSource(url,"root",System.getenv("INVENTORY_TEST_PASSWORD")));
        for(String table:Arrays.asList("sys_company_release","sys_company_service_route","sys_company_event","sys_company"))db.update("DELETE FROM "+table);
        db.update("UPDATE sys_company_publish_lock SET lease_token='',lease_until=NULL WHERE id=1");
        service=new CompanyOnboardingService();runtime=mock(CompanyRuntimeProbe.class);states=mock(CompanyStateStore.class);configs=new FakeConfig();
        ReflectionTestUtils.setField(service,"db",db);ReflectionTestUtils.setField(service,"json",new ObjectMapper());ReflectionTestUtils.setField(service,"runtime",runtime);ReflectionTestUtils.setField(service,"configs",configs);ReflectionTestUtils.setField(service,"states",states);
        when(states.state(anyString())).thenReturn("DRAFT");when(states.write(anyString(),anyLong(),anyString())).thenReturn(true);
        when(runtime.inspect(anyString(),anyMap(),anyString(),anyBoolean())).thenAnswer(i->map("ready",true,"services",Collections.emptyList(),"version",i.getArgument(2),"checked_at",1L));
    }
    Map<String,Object> draft(String code){Map<String,String> routes=new LinkedHashMap<>();APPS.keySet().forEach(k->routes.put(k,"master"));return map("company_code",code,"company_name","测试公司","revision",0L,"routes",routes);}
    String save(){return service.save(draft("unit_a"),"unit");}
    long revision(){return number(service.detail("UNIT_A"),"revision");}
    @Test void draftNormalizesIdentityAndGuardsConcurrencyAndCompleteRoutes(){assertEquals("UNIT_A",save());assertEquals(5,service.routes("UNIT_A").size());Map<String,Object> edit=draft("UNIT_A");assertThrows(IllegalArgumentException.class,()->service.save(edit,"unit"));((Map<?,?>)edit.get("routes")).remove("order");edit.put("revision",1);assertThrows(IllegalArgumentException.class,()->service.save(edit,"unit"));}
    @Test void listAndDetailNeverProbeServicesOrReadNacos(){save();clearInvocations(runtime);assertEquals(1L,service.list("UNIT","DRAFT",1,10).get("total"));service.detail("UNIT_A");verifyNoInteractions(runtime);assertThrows(IllegalArgumentException.class,()->service.list("","",1,101));}
    @Test void cannotEnableBeforePublishOrWithPartialReadiness(){save();assertThrows(IllegalArgumentException.class,()->service.status("UNIT_A",1,"ENABLED","unit"));service.publish("UNIT_A",1,"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","unit");when(runtime.inspect(anyString(),anyMap(),anyString(),eq(true))).thenReturn(map("ready",false));assertThrows(IllegalArgumentException.class,()->service.status("UNIT_A",revision(),"ENABLED","unit"));}
    @Test void publishIsIdempotentAndPreservesOtherCompanyAndSecrets(){save();String id="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";service.publish("UNIT_A",1,id,"unit");long version=revision();service.publish("UNIT_A",1,id,"unit");assertEquals(version,revision());for(String raw:configs.data.values()){assertEquals("master",new CompanyConfigDocument(raw).slot("QM").get("alias"));assertTrue(raw.contains("secret: unchanged"));assertEquals(id,new CompanyConfigDocument(raw).slot("UNIT_A").get("version"));}assertFalse(db.queryForObject("SELECT snapshot_json FROM sys_company_release WHERE id=?",String.class,id).contains("secret"));}
    @Test void partialPublicationRestoresOnlyTargetCompany(){save();configs.fail="inventory";assertThrows(IllegalArgumentException.class,()->service.publish("UNIT_A",1,"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","unit"));assertEquals("ROLLED_BACK",db.queryForObject("SELECT state FROM sys_company_release",String.class));for(String raw:configs.data.values())assertNull(new CompanyConfigDocument(raw).slot("UNIT_A").get("alias"));}
    @Test void externalChangeIsNeverOverwrittenAndBlocksLaterPublication(){save();configs.fail="inventory";configs.external=true;assertThrows(IllegalArgumentException.class,()->service.publish("UNIT_A",1,"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","unit"));assertEquals("ROLLBACK_FAILED",db.queryForObject("SELECT state FROM sys_company_release",String.class));assertEquals("external",new CompanyConfigDocument(configs.read("order")).slot("UNIT_A").get("alias"));assertThrows(IllegalArgumentException.class,()->service.publish("UNIT_A",1,"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","unit"));}
    @Test void enabledCompanyCannotChangeDatabaseAndDisableKeepsRevisionFence(){save();service.publish("UNIT_A",1,"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","unit");service.status("UNIT_A",revision(),"ENABLED","unit");Map<String,Object> edit=draft("UNIT_A");edit.put("revision",revision());((Map<String,String>)edit.get("routes")).put("order","second");assertThrows(IllegalArgumentException.class,()->service.save(edit,"unit"));long old=revision();service.status("UNIT_A",old,"DISABLED","unit");verify(states,atLeastOnce()).write("UNIT_A",old+1,"DISABLED");assertEquals("DISABLED",service.detail("UNIT_A").get("status"));}
    @Test void ordinaryUserOptionsOnlyReturnOwnCompany(){save();service.save(draft("UNIT_B"),"unit");assertEquals(1,service.options("UNIT_B","UNIT_A",false).size());assertEquals("UNIT_A",service.options("UNIT_B","UNIT_A",false).get(0).get("company_code"));assertThrows(IllegalArgumentException.class,()->service.validateUserCompany("UNKNOWN"));}
    @Test void configNormalizesCaseAndRefusesAmbiguousDefinitions(){CompanyConfigDocument config=new CompanyConfigDocument("oms:\n  tenant:\n    routes:\n      qm: master\n");assertEquals("master",config.slot("QM").get("alias"));assertThrows(IllegalArgumentException.class,()->new CompanyConfigDocument("oms:\n  tenant:\n    routes:\n      QM: master\n      qm: second\n").slot("QM"));}
    @Test void editingDraftInvalidatesReadinessAndListContainsOnlySummary(){save();Map<String,Object> edit=draft("UNIT_A");edit.put("revision",1);((Map<String,String>)edit.get("routes")).put("order","second");service.save(edit,"unit");assertEquals(2L,number(service.detail("UNIT_A"),"route_revision"));Map<String,Object> page=service.list("","",1,10);Map<String,Object> row=((List<Map<String,Object>>)page.get("rows")).get(0);assertEquals(false,row.get("runtime_ready"));assertFalse(row.containsKey("last_detect"));assertFalse(row.containsKey("last_check"));}
}
