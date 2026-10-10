package com.oms.channel.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.oms.channel.platform.ChannelPlatformService.map;

class ChannelPlatformTest {
    JdbcTemplate db; ChannelPlatformService service; PlatformSecrets secrets; Fixture client; long app;
    static class Fixture extends TmallClient {
        List<Map<String,String>> requests=new CopyOnWriteArrayList<>();
        String sid="12345",items="[{\"item_code\":\"ts-test-1\",\"deadline\":\"2030-01-01 00:00:00\"}]";
        boolean noRefresh=false,malformed=false;Failure failure;CountDownLatch entered,release;
        @Override protected JsonNode post(String url,Map<String,String> args) {
            requests.add(new HashMap<>(args));
            if(entered!=null) {entered.countDown();try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(Exception e){throw new RuntimeException(e);}}
            if(failure!=null)throw failure;
            try {
                if(url.endsWith("/token"))return new ObjectMapper().readTree("{\"access_token\":\"PRIVATE_ACCESS\",\"refresh_token\":\"PRIVATE_REFRESH\",\"expires_in\":864000,\"re_expires_in\":"+(noRefresh?0:864000)+",\"taobao_user_id\":\"SELLER-1\",\"taobao_user_nick\":\"%E6%B5%8B%E8%AF%95\"}");
                assertEquals(TmallClient.sign(args,"PRIVATE_SECRET"),args.get("sign"));
                if("taobao.shop.seller.get".equals(args.get("method")))return new ObjectMapper().readTree("{\"shop_seller_get_response\":{\"shop\":{\"sid\":\""+sid+"\",\"title\":\"测试店\"}}}");
                assertFalse(args.containsKey("session"),"Subscription queries do not need a session");
                return new ObjectMapper().readTree(malformed?"{}":"{\"vas_subscribe_get_response\":{\"article_user_subscribes\":{\"article_user_subscribe\":"+items+"}}}");
            } catch(java.io.IOException e){throw new RuntimeException(e);}
        }
    }
    @BeforeEach void setup() {
        String url=System.getenv("CHANNEL_TEST_URL");assertNotNull(url);assertTrue(url.contains("/channel_platform_test?"));
        DriverManagerDataSource source=new DriverManagerDataSource(url,"root",System.getenv("INVENTORY_TEST_PASSWORD"));db=new JdbcTemplate(source);
        for(String table:Arrays.asList("channel_interaction_log","channel_oauth_state","channel_platform_binding","channel_platform_app","t_channel"))db.update("DELETE FROM "+table);
        db.update("INSERT INTO t_channel(channel_id,channel_name,channel_type,enabled,to_channel_enabled,sync_enabled,m_model_type,company_code) VALUES(101,'店铺','TM',1,1,0,1,'QM'),(102,'外公司','TM',1,1,0,1,'OTHER'),(103,'同公司另一店铺','TM',1,1,0,1,'QM')");
        service=new ChannelPlatformService();secrets=new PlatformSecrets();client=new Fixture();
        ReflectionTestUtils.setField(secrets,"encryptionKey",Base64.getEncoder().encodeToString(new byte[32]));
        ReflectionTestUtils.setField(service,"db",db);ReflectionTestUtils.setField(service,"transactionManager",new DataSourceTransactionManager(source));ReflectionTestUtils.setField(service,"secrets",secrets);ReflectionTestUtils.setField(service,"client",client);
        app=service.saveApp("QM",appBody(),"tester");service.bind("QM",101,map("app_id",app,"expected_shop_id","12345"),"tester");
    }
    Map<String,Object> appBody(){return map("name","应用","app_key","test-app","secret","PRIVATE_SECRET","redirect_uri","https://oms.example.com/channel-authorize","article_code","ts-test","item_code","ts-test-1","renewal_url","https://fuwu.taobao.com/ser/detail.htm?service_code=ts-test","enabled",1);}
    String state(){String url=service.authorize("QM",101,7,"tester");return url.substring(url.indexOf("&state=")+7);}
    void authorize(){service.complete("QM",7,state(),"PRIVATE_CODE","tester");}
    int logs(String result){return db.queryForObject("SELECT COUNT(*) FROM channel_interaction_log WHERE result=?",Integer.class,result);}
    void simulation(boolean enabled) {
        ReflectionTestUtils.setField(service,"simulationEnabled",enabled);
        db.update("INSERT INTO channel_platform_app(company_code,name,enabled,simulation_mode,article_code,item_code) VALUES('QM','本地模拟',1,1,'SIMULATED_SERVICE','SIMULATED_ITEM')");
        long simulatedApp=db.queryForObject("SELECT id FROM channel_platform_app WHERE simulation_mode=1",Long.class);
        service.bind("QM",101,map("app_id",simulatedApp,"expected_shop_id","900000101"),"tester");
    }

    @Test void simulationUnavailableByDefaultOutsideLocalRuntime(){simulation(false);assertThrows(IllegalArgumentException.class,()->service.simulate("QM",101,"tester"));assertTrue(client.requests.isEmpty());assertEquals(false,service.detail("QM",101).get("can_simulate"));}
    @Test void simulationReturnsExplicitlyMarkedAuthorizedService(){simulation(true);service.simulate("QM",101,"tester");Map<String,Object>d=service.detail("QM",101);assertEquals(true,d.get("simulation"));assertEquals("模拟对接成功（授权与服务有效）",d.get("availability"));assertEquals("已授权",d.get("auth_label"));assertEquals("服务有效",d.get("service_label"));assertEquals(false,d.get("can_authorize"));assertTrue(client.requests.isEmpty());}
    @Test void simulatedQueriesAndRefreshNeverCallRealClient(){simulation(true);service.simulate("QM",101,"tester");service.query("QM",101,"shop","tester");service.query("QM",101,"subscription","tester");service.refresh("QM",101,"tester");assertTrue(client.requests.isEmpty());assertEquals("店铺",service.detail("QM",101).get("shop_title"));assertEquals(4,db.queryForObject("SELECT COUNT(*) FROM channel_interaction_log WHERE channel_id=101 AND result='SUCCESS' AND JSON_EXTRACT(response_summary,'$.simulation')=true",Integer.class));}
    @Test void realApplicationsCannotUseSimulationEndpoint(){assertThrows(IllegalArgumentException.class,()->service.simulate("QM",101,"tester"));assertTrue(client.requests.isEmpty());assertEquals("未授权",service.detail("QM",101).get("auth_label"));}
    @Test void simulatedAppsCannotStartRealOauthOrAcceptCredentialEdits(){simulation(true);assertThrows(IllegalArgumentException.class,()->service.authorize("QM",101,7,"tester"));long id=((Number)service.detail("QM",101).get("app_id")).longValue();Map<String,Object>b=appBody();b.put("id",id);assertThrows(IllegalArgumentException.class,()->service.saveApp("QM",b,"tester"));assertTrue(client.requests.isEmpty());}
    @Test void simulationRetainsTenantIsolation(){simulation(true);assertThrows(IllegalArgumentException.class,()->service.simulate("OTHER",101,"tester"));assertThrows(IllegalArgumentException.class,()->service.query("OTHER",101,"shop","tester"));assertTrue(client.requests.isEmpty());}
    @Test void simulatedLogRequestAndResponseAreClearlyMarked(){simulation(true);service.simulate("QM",101,"tester");Map<String,Object>log=db.queryForMap("SELECT * FROM channel_interaction_log WHERE action='SIMULATED_AUTHORIZATION'");assertTrue(log.get("request_summary").toString().contains("\"simulation\":true"));assertTrue(log.get("response_summary").toString().contains("\"simulation\":true"));assertFalse(log.toString().contains("SIMULATED_REFRESH_"));}

    @Test void noApplicationCannotAppearAuthorized(){Map<String,Object> d=service.detail("QM",103);assertEquals("待配置",d.get("auth_label"));assertEquals("未核验",d.get("service_label"));assertEquals(false,d.get("can_authorize"));}
    @Test void draftSavedWithoutCredentialsAndCannotAuthorize(){long draft=service.saveApp("QM",map("name","草稿","enabled",0),"tester");service.bind("QM",103,map("app_id",draft,"expected_shop_id","777"),"tester");assertThrows(IllegalArgumentException.class,()->service.authorize("QM",103,7,"tester"));assertEquals(false,service.detail("QM",103).get("can_authorize"));}
    @Test void enabledAppRequiresAllCredentials(){Map<String,Object> body=appBody();body.remove("secret");assertThrows(IllegalArgumentException.class,()->service.saveApp("QM",body,"tester"));}
    @Test void callbackAndRenewalRejectUnsafeDestinations(){for(String uri:Arrays.asList("http://oms.example.com/channel-authorize","https://oms.example.com/other","https://name:pass@oms.example.com/channel-authorize","https://oms.example.com/channel-authorize?next=evil")){Map<String,Object>b=appBody();b.put("redirect_uri",uri);assertThrows(IllegalArgumentException.class,()->service.saveApp("QM",b,"tester"));}Map<String,Object>b=appBody();b.put("renewal_url","https://fuwu.taobao.com.evil.test/");assertThrows(IllegalArgumentException.class,()->service.saveApp("QM",b,"tester"));}
    @Test void companyIsolationForAppsShopsAndBinding(){assertTrue(service.apps("OTHER").isEmpty());assertThrows(IllegalArgumentException.class,()->service.detail("OTHER",101));assertThrows(IllegalArgumentException.class,()->service.bind("OTHER",102,map("app_id",app,"expected_shop_id","12345"),"tester"));assertEquals(0L,service.logs("OTHER",null,null,1,20).get("total"));}
    @Test void oauthStateIsHashedAndBoundToUserAndCompany(){String state=state();assertFalse(db.queryForObject("SELECT state_hash FROM channel_oauth_state",String.class).equals(state));assertThrows(IllegalArgumentException.class,()->service.complete("QM",8,state,"CODE","other"));assertThrows(IllegalArgumentException.class,()->service.complete("OTHER",7,state,"CODE","tester"));assertEquals(101,service.complete("QM",7,state,"CODE","tester"));}
    @Test void oauthStateSingleUse(){String state=state();service.complete("QM",7,state,"CODE","tester");assertThrows(IllegalArgumentException.class,()->service.complete("QM",7,state,"CODE","tester"));assertEquals(2,client.requests.size());}
    @Test void expiredStateRejectedBeforeNetwork(){String state=state();db.update("UPDATE channel_oauth_state SET expires_at=DATE_SUB(NOW(),INTERVAL 1 SECOND)");assertThrows(IllegalArgumentException.class,()->service.complete("QM",7,state,"CODE","tester"));assertTrue(client.requests.isEmpty());}
    @Test void issuingNewStateInvalidatesPriorAttempt(){String old=state(),fresh=state();assertThrows(IllegalArgumentException.class,()->service.complete("QM",7,old,"CODE","tester"));assertEquals(101,service.complete("QM",7,fresh,"CODE","tester"));}
    @Test void wrongShopRejectedWithoutSavingToken(){client.sid="999";assertThrows(IllegalArgumentException.class,this::authorize);assertNull(db.queryForObject("SELECT token_cipher FROM channel_platform_binding WHERE channel_id=101",String.class));assertEquals(1,logs("FAILED"));}
    @Test void credentialsEncryptedAndNeverReturnedOrLogged() throws Exception {authorize();String cipher=db.queryForObject("SELECT token_cipher FROM channel_platform_binding WHERE channel_id=101",String.class);assertNotEquals("PRIVATE_ACCESS",cipher);assertEquals("PRIVATE_ACCESS",secrets.decrypt(cipher));String serialized=new ObjectMapper().writeValueAsString(Arrays.asList(service.detail("QM",101),service.apps("QM"),service.logs("QM",null,null,1,100)));assertFalse(serialized.contains("PRIVATE_"));assertFalse(serialized.contains("token_cipher"));assertFalse(serialized.contains("secret_cipher"));assertEquals("测试",service.detail("QM",101).get("seller_nick"));}
    @Test void authorizationDoesNotFabricateSubscription(){authorize();assertEquals("已授权",service.detail("QM",101).get("auth_label"));assertEquals("未核验",service.detail("QM",101).get("service_label"));assertEquals("请核验服务订购状态",service.detail("QM",101).get("availability"));}
    @Test void serviceQueryUsesExactConfiguredFeeItem(){authorize();client.items="[{\"item_code\":\"other\",\"deadline\":\"2040-01-01 00:00:00\"},{\"item_code\":\"ts-test-1\",\"deadline\":\"2030-01-01 00:00:00\"}]";service.query("QM",101,"subscription","tester");assertTrue(service.detail("QM",101).get("service_expires_at").toString().startsWith("2030"));assertEquals("授权与服务均有效",service.detail("QM",101).get("availability"));}
    @Test void emptySubscriptionIsNotSubscribed(){authorize();client.items="[]";service.query("QM",101,"subscription","tester");assertEquals("未订购",service.detail("QM",101).get("service_label"));}
    @Test void malformedResponseDoesNotOverwriteLastVerifiedSubscription(){authorize();service.query("QM",101,"subscription","tester");client.malformed=true;assertThrows(IllegalArgumentException.class,()->service.query("QM",101,"subscription","tester"));assertEquals("服务有效",service.detail("QM",101).get("service_label"));}
    @Test void timeoutLoggedUnknownAndLeaseReleased(){authorize();client.failure=new TmallClient.Failure("TRANSPORT_OR_RESPONSE_ERROR",true);assertThrows(TmallClient.Failure.class,()->service.query("QM",101,"shop","tester"));assertEquals(1,logs("UNKNOWN"));assertEquals(false,service.detail("QM",101).get("busy"));}
    @Test void invalidSessionChangesAuthorizationStatus(){authorize();client.failure=new TmallClient.Failure("isv.session-expired",false);assertThrows(TmallClient.Failure.class,()->service.query("QM",101,"shop","tester"));assertEquals("授权失效",service.detail("QM",101).get("auth_label"));}
    @Test void refreshUnavailableWhenPlatformReturnsZeroExpiry(){client.noRefresh=true;authorize();assertEquals(false,service.detail("QM",101).get("can_refresh"));assertThrows(IllegalArgumentException.class,()->service.refresh("QM",101,"tester"));}
    @Test void refreshUsesServerStoredCredentialAndChecksShop(){authorize();service.refresh("QM",101,"tester");assertTrue(client.requests.stream().anyMatch(p->"refresh_token".equals(p.get("grant_type"))&&"PRIVATE_REFRESH".equals(p.get("refresh_token"))));assertEquals("已授权",service.detail("QM",101).get("auth_label"));}
    @Test void localDisableClearsSecretsAndInvalidatesOutstandingState(){authorize();String pending=state();service.disable("QM",101,"tester");assertEquals("本地已停用",service.detail("QM",101).get("auth_label"));assertNull(db.queryForObject("SELECT token_cipher FROM channel_platform_binding WHERE channel_id=101",String.class));assertThrows(IllegalArgumentException.class,()->service.complete("QM",7,pending,"CODE","tester"));}
    @Test void activeAuthorizationCannotBeRebound(){authorize();assertThrows(IllegalArgumentException.class,()->service.bind("QM",101,map("app_id",app,"expected_shop_id","999"),"tester"));}
    @Test void boundAppCredentialsCannotBeOverwritten(){Map<String,Object>b=appBody();b.put("id",app);b.put("app_key","changed");assertThrows(IllegalArgumentException.class,()->service.saveApp("QM",b,"tester"));}
    @Test void disabledAppBlocksCalls(){Map<String,Object>b=appBody();b.put("id",app);b.put("secret","");b.put("enabled",0);service.saveApp("QM",b,"tester");assertThrows(IllegalArgumentException.class,this::authorize);assertEquals(false,service.detail("QM",101).get("can_authorize"));}
    @Test void disabledShopBlocksCalls(){db.update("UPDATE t_channel SET enabled=0 WHERE channel_id=101");assertThrows(IllegalArgumentException.class,this::authorize);}
    @Test void remindersUseExpiryAndOptOut(){authorize();db.update("UPDATE channel_platform_binding SET token_expires_at=DATE_ADD(NOW(),INTERVAL 2 DAY)");assertEquals(1,service.reminders("QM").size());assertTrue(service.reminders("OTHER").isEmpty());service.reminder("QM",101,1,true,"tester");assertTrue(service.reminders("QM").isEmpty());service.reminder("QM",101,7,false,"tester");assertTrue(service.reminders("QM").isEmpty());assertThrows(IllegalArgumentException.class,()->service.reminder("QM",101,0,true,"tester"));}
    @Test void staleSubscriptionDoesNotShowAvailable(){authorize();service.query("QM",101,"subscription","tester");db.update("UPDATE channel_platform_binding SET service_checked_at=DATE_SUB(NOW(),INTERVAL 25 HOUR)");assertEquals("请核验服务订购状态",service.detail("QM",101).get("availability"));}
    @Test void concurrentRequestsAndConfigurationCannotOverwriteInFlightOperation() throws Exception {
        authorize();client.entered=new CountDownLatch(1);client.release=new CountDownLatch(1);ExecutorService executor=Executors.newSingleThreadExecutor();
        try {Future<?> first=executor.submit(()->service.query("QM",101,"shop","tester"));assertTrue(client.entered.await(5,TimeUnit.SECONDS));assertThrows(IllegalArgumentException.class,()->service.query("QM",101,"shop","tester"));assertThrows(IllegalArgumentException.class,()->service.disable("QM",101,"tester"));Map<String,Object>b=appBody();b.put("id",app);b.put("secret","");assertThrows(IllegalArgumentException.class,()->service.saveApp("QM",b,"tester"));client.release.countDown();first.get(10,TimeUnit.SECONDS);}finally {client.release.countDown();executor.shutdownNow();}
    }
    @Test void encryptionDetectsTamperingAndUsesRandomNonce(){String a=secrets.encrypt("token"),b=secrets.encrypt("token");assertNotEquals(a,b);byte[] changed=Base64.getDecoder().decode(a);changed[15]^=1;assertThrows(IllegalArgumentException.class,()->secrets.decrypt(Base64.getEncoder().encodeToString(changed)));}
    @Test void logPaginationAndCrossCompanyFilterAreBounded(){Map<String,Object>a=service.logs("QM",null,null,1,1),b=service.logs("QM",null,null,2,1);assertEquals(2L,a.get("total"));assertNotEquals(a.get("rows"),b.get("rows"));assertThrows(IllegalArgumentException.class,()->service.logs("QM",102L,null,1,20));assertThrows(IllegalArgumentException.class,()->service.logs("QM",null,null,1,101));}
}
