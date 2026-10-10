package com.oms.inventory;

import com.baomidou.dynamic.datasource.DynamicRoutingDataSource;
import com.baomidou.dynamic.datasource.provider.DynamicDataSourceProvider;
import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import com.oms.common.filter.DynamicDatasourceInterceptorFilter;
import com.oms.inventory.model.dto.AllocationDraft;
import com.oms.inventory.model.entity.WmsInventoryBatch;
import com.oms.inventory.service.impl.*;
import com.oms.inventory.service.impl.rule.*;
import com.ruoyi.common.core.constant.SecurityConstants;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.mock.web.*;
import org.springframework.test.util.ReflectionTestUtils;
import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class DailyCoordinatorTest {
    static final List<com.alibaba.druid.pool.DruidDataSource> testPools=new ArrayList<>();
    static DataSource pooled(String url,String password){
        com.alibaba.druid.pool.DruidDataSource pool=new com.alibaba.druid.pool.DruidDataSource();pool.setUrl(url);pool.setUsername("root");pool.setPassword(password);pool.setMaxActive(8);pool.setMaxWait(10000);testPools.add(pool);
        // Route removal must not close the shared test fixture pool, which assertions still inspect.
        return new AbstractDataSource(){public java.sql.Connection getConnection() throws SQLException{return pool.getConnection();}public java.sql.Connection getConnection(String u,String p) throws SQLException{return pool.getConnection(u,p);}};
    }
    static AnnotationConfigApplicationContext context;static DynamicRoutingDataSource routing;
    static DataSource primary,secondary;static JdbcTemplate db;static InventoryMutationService inventory;
    static AllocationWorkspaceService workspace;static DailyAllocationService daily;static DailyAllocationCoordinator coordinator;
    @Configuration @EnableTransactionManagement
    static class Config {
        @Bean DataSource dataSource(){DynamicRoutingDataSource d=new DynamicRoutingDataSource(Collections.emptyList());d.setPrimary("master");d.setStrict(false);d.addDataSource("master",primary);return d;}
        @Bean DynamicDataSourceProvider provider(){return Collections::emptyMap;}
        @Bean JdbcTemplate jdbcTemplate(DataSource ds){return new JdbcTemplate(ds);}
        @Bean PlatformTransactionManager transactionManager(DataSource ds){return new DataSourceTransactionManager(ds);}
        @Bean InventoryMutationService inventory(){return new InventoryMutationService();}
        @Bean AllocationWorkspaceService workspace(){return new AllocationWorkspaceService();}
        @Bean AllocationReservationService reservations(){return new AllocationReservationService();}
        @Bean DailyAllocationService daily(){return new DailyAllocationService();}
        @Bean DailyAllocationScanner scanner(){return new DailyAllocationScanner();}
        @Bean DailyAllocationCoordinator coordinator(){return new DailyAllocationCoordinator();}
    }
    @BeforeAll static void init(){
        String url=System.getenv("INVENTORY_TEST_URL");Assumptions.assumeTrue(url!=null);assertTrue(url.contains("/inventory_workspace_test?"));String password=System.getenv("INVENTORY_TEST_PASSWORD");
        primary=pooled(url,password);secondary=pooled(url.replace("/inventory_workspace_test?","/inventory_workspace_test_other?"),password);
        context=new AnnotationConfigApplicationContext(Config.class);routing=(DynamicRoutingDataSource)context.getBean(DataSource.class);db=context.getBean(JdbcTemplate.class);inventory=context.getBean(InventoryMutationService.class);workspace=context.getBean(AllocationWorkspaceService.class);daily=context.getBean(DailyAllocationService.class);coordinator=context.getBean(DailyAllocationCoordinator.class);
    }
    @AfterAll static void stop(){DynamicDataSourceContextHolder.clear();if(context!=null)context.close();for(com.alibaba.druid.pool.DruidDataSource pool:testPools)pool.close();}
    @AfterEach void awaitWorkers(){idle();}
    @BeforeEach void clear(){
        DynamicDataSourceContextHolder.clear();for(String key:new ArrayList<>(routing.getDataSources().keySet()))if(!key.equals("master"))routing.removeDataSource(key);
        for(DataSource pool:Arrays.asList(primary,secondary))for(String table:Arrays.asList("rule_stock_daily_item","rule_stock_daily_run","rule_stock_reservation_event","rule_stock_order_reservation","rule_stock_reservation","rule_stock_result","rule_stock_channel_info","rule_stock_store_code_info","rule_stock_goods_info","rule_stock_info","oms_channel_inventory","wms_inventory_change_history","wms_inventory_batch","wms_inventory","oms_inventory"))new JdbcTemplate(pool).update("DELETE FROM "+table);
        ((Queue<?>)ReflectionTestUtils.getField(coordinator,"failures")).clear();
    }
    long create(String company,int qty,boolean enable){
        WmsInventoryBatch b=new WmsInventoryBatch();b.setCompanyCode(company);b.setSkuSn("A");b.setStoreCode("W1");b.setBatchCode("B1");b.setZpActualNumber(qty);b.setCpActualNumber(0);inventory.receive(b,"IN-A");
        AllocationDraft d=new AllocationDraft();d.setRuleType(1);d.setRuleName("自动发现验证");d.setStartTime(LocalDateTime.now().minusMinutes(1));d.setEndTime(LocalDateTime.now().plusHours(1));d.setStores(Arrays.asList("W1"));AllocationDraft.Channel c=new AllocationDraft.Channel();c.setChannelId(Math.floorMod(company.hashCode(),Integer.MAX_VALUE));c.setChannelName("渠道");c.setPercentage(BigDecimal.valueOf(100));d.getChannels().add(c);
        long id=workspace.save(company,d,"tester");workspace.submit(company,id,1,false);if(enable)daily.command(company,id,2,"ENABLE","tester");return id;
    }
    long stock(DataSource pool,String company){return new JdbcTemplate(pool).queryForObject("SELECT COALESCE(SUM(available_stock),0) FROM oms_channel_inventory WHERE company_code=?",Long.class,company);}
    void idle(){
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(180);
        while(((AtomicBoolean)ReflectionTestUtils.getField(coordinator,"discovering")).get() || !((Set<?>)ReflectionTestUtils.getField(coordinator,"active")).isEmpty()){
            assertTrue(System.nanoTime()<deadline,"Background sweep timed out");try{Thread.sleep(10);}catch(InterruptedException e){throw new RuntimeException(e);}
        }
    }
    Map<String,Object> scan(){coordinator.poll();idle();Map<String,Object> result=new HashMap<>();result.put("failures",new ArrayList<>((Queue<?>)ReflectionTestUtils.getField(coordinator,"failures")));return result;}
    @Test void newCompanyIsDiscoveredWithoutAnyTaskOrCompanyConfiguration(){
        create("ALPHA",100,true);long beta=create("BETA",35,false);scan();assertEquals(100,stock(primary,"ALPHA"));assertEquals(0,stock(primary,"BETA"));
        daily.command("BETA",beta,2,"ENABLE","tester");scan();assertEquals(35,stock(primary,"BETA"));assertNull(DynamicDataSourceContextHolder.peek());
    }
    @Test void dynamicallyLoadedDatabaseKeepsSameCompanyAndSkuIsolated(){
        create("SAME",100,true);scan();routing.addDataSource("second",secondary);
        DynamicDataSourceContextHolder.push("second");try{create("SAME",27,true);}finally{DynamicDataSourceContextHolder.poll();}
        scan();assertEquals(100,stock(primary,"SAME"));assertEquals(27,stock(secondary,"SAME"));assertNull(DynamicDataSourceContextHolder.peek());
        routing.removeDataSource("second");scan();assertEquals(27,stock(secondary,"SAME"));
    }
    @Test void brokenDatasourceDoesNotFallBackOrBlockHealthyCompanies() throws Exception {
        create("GOOD",90,true);DataSource broken=mock(DataSource.class);when(broken.getConnection()).thenThrow(new SQLException("Intentional isolated test connection failure"));routing.addDataSource("aBroken",broken);
        DynamicDataSourceContextHolder.push("outer");try{Map<String,Object> result=scan();assertFalse(((List<?>)result.get("failures")).isEmpty());assertEquals("outer",DynamicDataSourceContextHolder.peek());}finally{DynamicDataSourceContextHolder.poll();}
        assertEquals(90,stock(primary,"GOOD"));assertEquals(1,new JdbcTemplate(primary).queryForObject("SELECT COUNT(*) FROM rule_stock_daily_run",Integer.class));
    }
    @Test void invalidCompanyDoesNotPreventOtherCompanyFromRunning(){
        create("GOOD",80,true);long invalid=create("BAD",10,true);db.update("UPDATE rule_stock_info SET company_code='BAD SPACE' WHERE id=?",invalid);
        Map<String,Object> result=scan();assertEquals(1,((List<?>)result.get("failures")).size());assertEquals(80,stock(primary,"GOOD"));
    }
    @Test void everyCompanyIsDiscoveredInOneTriggerAndNewEarlierCompanyJoins(){
        for(int i=0;i<7;i++)create("T"+i,10+i,true);scan();assertEquals(7,db.queryForObject("SELECT COUNT(*) FROM rule_stock_daily_run",Integer.class));
        create("EARLIER",25,true);scan();assertEquals(25,stock(primary,"EARLIER"));
        Map<?,?> plan=db.queryForMap("EXPLAIN SELECT DISTINCT company_code FROM rule_stock_info FORCE INDEX (idx_daily_company_scan) WHERE rule_type=1 AND daily_enabled=1 AND company_code>'' ORDER BY company_code LIMIT 5");assertEquals("idx_daily_company_scan",plan.get("key"));assertFalse(String.valueOf(plan.get("Extra")).contains("filesort"));
    }
    @Test void companyRoutingBypassOnlyAcceptsExactInternalPostAndAlwaysClearsContext() throws Exception {
        DynamicDatasourceInterceptorFilter filter=new DynamicDatasourceInterceptorFilter();MockHttpServletRequest request=new MockHttpServletRequest("POST","/allocation/internal/daily-scan-all");request.addHeader(SecurityConstants.FROM_SOURCE,SecurityConstants.INNER);MockHttpServletResponse response=new MockHttpServletResponse();
        DynamicDataSourceContextHolder.push("previous");filter.doFilter(request,response,(req,res)->assertNull(DynamicDataSourceContextHolder.peek()));assertNull(DynamicDataSourceContextHolder.peek());
        assertThrows(javax.servlet.ServletException.class,()->filter.doFilter(request,response,(req,res)->{DynamicDataSourceContextHolder.push("failed");throw new javax.servlet.ServletException("expected");}));assertNull(DynamicDataSourceContextHolder.peek());
        MockHttpServletRequest publicRequest=new MockHttpServletRequest("POST","/allocation/internal/daily-scan-all");MockHttpServletResponse rejected=new MockHttpServletResponse();filter.doFilter(publicRequest,rejected,(req,res)->fail("Unauthenticated public request must not reach coordinator"));assertEquals(400,rejected.getStatus());
    }
    @Test void companyWorkersRunInParallelButRepeatedTriggersDoNotDuplicateCompany() throws Exception {
        create("ALPHA",1,true);create("BETA",1,true);create("GAMMA",1,true);
        DailyAllocationScanner original=context.getBean(DailyAllocationScanner.class),blocked=mock(DailyAllocationScanner.class);
        CountDownLatch entered=new CountDownLatch(2),release=new CountDownLatch(1);AtomicInteger active=new AtomicInteger(),max=new AtomicInteger(),calls=new AtomicInteger();
        doAnswer(invocation->{int n=active.incrementAndGet();max.accumulateAndGet(n,Math::max);calls.incrementAndGet();entered.countDown();try{assertEquals("master",DynamicDataSourceContextHolder.peek());assertTrue(release.await(15,TimeUnit.SECONDS));}finally{active.decrementAndGet();}return null;}).when(blocked).drain(anyString());
        ReflectionTestUtils.setField(coordinator,"scanner",blocked);
        try{coordinator.poll();assertTrue(entered.await(10,TimeUnit.SECONDS));assertEquals(2,max.get());for(int i=0;i<10;i++)coordinator.poll();assertEquals(2,calls.get());while(((AtomicBoolean)ReflectionTestUtils.getField(coordinator,"discovering")).get())Thread.sleep(10);release.countDown();idle();assertEquals(3,calls.get());}
        finally{release.countDown();idle();ReflectionTestUtils.setField(coordinator,"scanner",original);}
    }
    @Test void fiftyRulesAndMultiBatchSkusCompleteFromOneTrigger(){
        long first=create("LARGE",100,true);
        List<Object[]> stocks=new ArrayList<>();for(int i=0;i<120;i++)stocks.add(new Object[]{"LARGE","SKU"+String.format("%06d",i),100,100});
        db.batchUpdate("INSERT INTO oms_inventory(company_code,sku_sn,total_stock,available_stock) VALUES (?,?,?,?)",stocks);
        db.batchUpdate("INSERT INTO wms_inventory(company_code,sku_sn,zp_actual_number,zp_available_number,store_code) VALUES (?,?,?,?,'W1')",stocks);
        for(int i=1;i<50;i++){
            AllocationDraft d=new AllocationDraft();d.setRuleType(1);d.setRuleName("Full sweep "+i);d.setStartTime(LocalDateTime.now().minusMinutes(1));d.setEndTime(LocalDateTime.now().plusHours(1));d.setStores(Arrays.asList("W1"));AllocationDraft.Channel c=new AllocationDraft.Channel();c.setChannelId(10000+i);c.setChannelName("channel"+i);c.setPercentage(BigDecimal.valueOf(100));d.getChannels().add(c);
            long id=workspace.save("LARGE",d,"tester");workspace.submit("LARGE",id,1,false);daily.command("LARGE",id,2,"ENABLE","tester");
        }
        scan();assertEquals(50,db.queryForObject("SELECT COUNT(*) FROM rule_stock_daily_run WHERE status='SUCCESS'",Integer.class));assertEquals(6050,db.queryForObject("SELECT SUM(success) FROM rule_stock_daily_run",Integer.class));assertEquals(6050,db.queryForObject("SELECT SUM(attempts) FROM rule_stock_daily_item",Integer.class));
    }
    @Test void hundredThousandSkuPreparationUsesKeysetIndexAndBoundedBatches(){
        long rule=create("VOLUME",100,true);
        String digits="(SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9)";
        String skus="SELECT CONCAT('SKU',LPAD(a.n+10*b.n+100*c.n+1000*d.n+10000*e.n,6,'0')) sku FROM "+digits+" a CROSS JOIN "+digits+" b CROSS JOIN "+digits+" c CROSS JOIN "+digits+" d CROSS JOIN "+digits+" e";
        db.update("INSERT INTO oms_inventory(company_code,sku_sn,total_stock,available_stock) SELECT 'VOLUME',sku,100,100 FROM ("+skus+") s");
        db.update("INSERT INTO wms_inventory(company_code,sku_sn,store_code,zp_actual_number,zp_available_number) SELECT 'VOLUME',sku,'W1',100,100 FROM ("+skus+") s");
        Map<String,Object> plan=db.queryForMap("EXPLAIN SELECT DISTINCT sku_sn FROM wms_inventory WHERE company_code='VOLUME' AND store_code IN ('W1') AND sku_sn>'SKU050000' ORDER BY sku_sn LIMIT 2000");
        assertFalse(String.valueOf(plan.get("Extra")).contains("filesort"));assertNotNull(plan.get("key"));
        long begin=System.nanoTime();daily.tick("VOLUME",rule);
        while("PREPARING".equals(db.queryForObject("SELECT status FROM rule_stock_daily_run WHERE rule_id=?",String.class,rule)))daily.tick("VOLUME",rule);
        assertEquals(100001,db.queryForObject("SELECT total FROM rule_stock_daily_run WHERE rule_id=?",Integer.class,rule));
        assertEquals(100001,db.queryForObject("SELECT COUNT(*) FROM rule_stock_daily_item",Integer.class));
        long prepared=System.nanoTime();int before=db.queryForObject("SELECT success FROM rule_stock_daily_run WHERE rule_id=?",Integer.class,rule);
        for(int i=0;i<10;i++)daily.tick("VOLUME",rule);
        int after=db.queryForObject("SELECT success FROM rule_stock_daily_run WHERE rule_id=?",Integer.class,rule);assertTrue(after>before);assertEquals(0,db.queryForObject("SELECT failed FROM rule_stock_daily_run WHERE rule_id=?",Integer.class,rule));
        System.out.println("VOLUME CHECK: 100001 SKU preparation ms="+TimeUnit.NANOSECONDS.toMillis(prepared-begin)+", sampled SKU="+(after-before)+", sample ms="+TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-prepared));
        daily.command("VOLUME",rule,3,"PAUSE","tester");
    }
}
