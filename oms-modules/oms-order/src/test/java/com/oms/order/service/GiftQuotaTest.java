package com.oms.order.service;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static com.oms.order.service.GiftRules.*;
import static com.oms.order.service.OrderValues.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real InnoDB contention and rollback tests, restricted to the disposable order schema. */
class GiftQuotaTest {
    RefundServiceTest f;GiftService gifts;GiftConfiguration config;AtomicLong sequence=new AtomicLong();
    @BeforeAll static void start(){RefundServiceTest.start();}
    @BeforeEach void setup(){
        f=new RefundServiceTest();f.reset();clear();gifts=service();config=gifts.configuration;
    }
    GiftService service(){
        GiftService s=new GiftService();s.repository=f.repo;s.catalog=mock(OrderCatalog.class);s.inventory=mock(OrderWarehouseClient.class);
        when(s.catalog.goods(eq("QUOTA"),anyList())).thenAnswer(call->{Map<String,List<Map<String,Object>>> products=new HashMap<>();for(String sku:(List<String>)call.getArgument(1))products.put(sku,Collections.singletonList(map("sku_sn",sku,"is_gift",1,"is_fd",0,"is_package",0)));return products;});
        GiftConfiguration c=new GiftConfiguration();c.repository=f.repo;c.catalog=s.catalog;s.configuration=c;
        when(s.catalog.channels("QUOTA")).thenReturn(Arrays.asList(map("channel_id",1,"enabled",1),map("channel_id",2,"enabled",1)));return s;
    }
    void clear(){for(String t:Arrays.asList("oms_gift_quota_award","oms_gift_quota","oms_gift_plan","oms_gift_shop_member","oms_gift_shop_group_version","oms_gift_shop_group","oms_gift_config_lock","oms_gift_group_version","oms_gift_group"))f.db.update("DELETE FROM "+t+" WHERE company_code='QUOTA'");}
    @AfterEach void finish(){verifyNoInteractions(gifts.inventory);clear();f.clear();}
    Map<String,Object> limited(String key,String type,long max,String gift){Map<String,Object> r=GiftRulesTest.rule(key,type,3,"100",gift);r.put("max_count",max);return r;}
    long save(Map<String,Object>... rules){return number(gifts.save("QUOTA",map("name","Quota "+sequence.incrementAndGet(),"enabled",1,"policy",GiftRulesTest.policy(Arrays.asList(rules))),"test"),"id");}
    Map<String,Object> group(long id){return gifts.groups("QUOTA").stream().filter(g->number(g,"id")==id).findFirst().get();}
    void change(long id,long max){Map<String,Object> g=group(id),p=object(g.get("policy"));List<Map<String,Object>> rules=rows(p.get("rules"));rules.get(0).put("max_count",max);p.put("rules",rules);g.put("policy",p);gifts.save("QUOTA",g,"test");}
    void scope(long shop,long... ids){List<Map<String,Object>> links=new ArrayList<>();for(long id:ids)links.add(map("group_id",id,"continue_after_match",true));config.save("QUOTA",map("name","Shop "+shop,"enabled",1,"channels",Collections.singletonList(shop),"links",links),"test");gifts.invalidate("QUOTA");}
    Map<String,Object> order(long shop,int quantity){
        long n=sequence.incrementAndGet();String payload=TmallOrderParserTest.payload("300").replace("987654321012345678",String.valueOf(800000+n)).replace("\"num\":5","\"num\":"+quantity).replace("\"seller_nick\"","\"pay_time\":\"2026-10-20 12:00:00\",\"seller_nick\"");
        long id=f.repo.insert("INSERT INTO oms_platform_order(company_code,channel_id,platform_tid,platform_status,trade_type,seller_nick,source_modified,payload_hash,raw_payload,processing_payload,processing_hash,conversion_status) VALUES ('QUOTA',?,?,'WAIT_SELLER_SEND_GOODS','fixed','seller','2026-10-20 12:00:00',?,?,?,?,'PENDING')",shop,String.valueOf(800000+n),hash(payload),payload,payload,hash(payload));return f.repo.raw("QUOTA",id);
    }
    List<Map<String,Object>> prepare(Map<String,Object> order){TmallOrderParser.Trade trade=new TmallOrderParser().parse(text(order,"processing_payload"));return gifts.prepare("QUOTA",order,trade,Collections.singletonMap(trade.lines.get(0).oid,"A"));}
    long used(long group){return f.db.queryForObject("SELECT used_count FROM oms_gift_quota WHERE company_code='QUOTA' AND group_id=?",Long.class,group);}
    long count(String table){return f.db.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE company_code='QUOTA'",Long.class);}
    List<List<Map<String,Object>>> concurrent(List<Map<String,Object>> orders) throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(8);CountDownLatch go=new CountDownLatch(1);List<Future<List<Map<String,Object>>>> tasks=new ArrayList<>();
        try{for(Map<String,Object> order:orders)tasks.add(pool.submit(()->{go.await();return prepare(order);}));go.countDown();List<List<Map<String,Object>>> results=new ArrayList<>();for(Future<List<Map<String,Object>>> task:tasks)results.add(task.get(30,TimeUnit.SECONDS));return results;}finally{pool.shutdownNow();}
    }
    @Test void hundredOrdersAcrossShopsCannotOverspendLastTenUses() throws Exception {
        long id=save(limited("R","PRODUCT",10,"G"));scope(1,id);scope(2,id);List<Map<String,Object>> orders=new ArrayList<>();for(int i=0;i<100;i++)orders.add(order(1+i%2,3));
        long started=System.nanoTime();List<List<Map<String,Object>>> results=concurrent(orders);
        assertEquals(10,results.stream().filter(r->!r.isEmpty()).count());assertEquals(10,used(id));assertEquals(10,count("oms_gift_quota_award"));assertEquals(100,count("oms_gift_plan"));
        assertEquals(10,f.db.queryForObject("SELECT SUM(granted_count) FROM oms_gift_quota_award WHERE company_code='QUOTA'",Long.class));
        System.out.println("QUOTA CONTENTION: 100 orders / 8 workers / 2 shops in "+TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)+"ms; grants=10, overspend=0");
    }
    @Test void duplicateMessagesOnlyConsumeOnceAndReturnSameFrozenPlan() throws Exception {
        long id=save(limited("R","MULTIPLE",100,"G"));scope(1,id);Map<String,Object> order=order(1,6);List<List<Map<String,Object>>> results=concurrent(Collections.nCopies(40,order));
        assertEquals(2,used(id));assertEquals(1,count("oms_gift_plan"));assertEquals(1,count("oms_gift_quota_award"));for(List<Map<String,Object>> result:results){assertEquals(results.get(0),result);assertEquals(2,number(result.get(0),"quantity"));}
    }
    @Test void partialQuotaAlwaysGrantsCompleteSetsAcrossEveryGiftSku(){
        Map<String,Object> rule=limited("R","MULTIPLE",2,"G");rule.put("gifts",Arrays.asList(GiftRulesTest.gift("G",2),GiftRulesTest.gift("X",3)));long id=save(rule);scope(1,id);
        List<Map<String,Object>> result=prepare(order(1,9));assertEquals(4,number(result.get(0),"quantity"));assertEquals(6,number(result.get(1),"quantity"));assertEquals(2,used(id));assertEquals(3,f.db.queryForObject("SELECT requested_count FROM oms_gift_quota_award WHERE company_code='QUOTA'",Long.class));
    }
    @Test void exhaustedStopRuleFallsThroughToNextRule(){
        Map<String,Object> first=limited("R","PRODUCT",1,"G");first.put("stop",true);long id=save(first,limited("NEXT","PRODUCT",0,"X"));scope(1,id);
        assertEquals("G",text(prepare(order(1,3)).get(0),"sku_sn"));assertEquals("X",text(prepare(order(1,3)).get(0),"sku_sn"));assertEquals(1,used(id));
    }
    @Test void exhaustedGroupDoesNotSuppressLaterStrategyGroup(){
        long a=save(limited("R","PRODUCT",1,"G")),b=save(limited("R","PRODUCT",0,"X"));scope(1,a,b);Map<String,Object> scope=config.scopes("QUOTA").get(0);List<Map<String,Object>> links=rows(scope.get("links"));links.get(0).put("continue_after_match",false);scope.put("links",links);config.save("QUOTA",scope,"test");gifts.invalidate("QUOTA");
        assertEquals("G",text(prepare(order(1,3)).get(0),"sku_sn"));assertEquals("X",text(prepare(order(1,3)).get(0),"sku_sn"));
    }
    @Test void zeroGrantIsFrozenEvenIfQuotaIsLaterIncreased(){
        long id=save(limited("R","PRODUCT",1,"G"));scope(1,id);prepare(order(1,3));Map<String,Object> declined=order(1,3);assertTrue(prepare(declined).isEmpty());change(id,10);assertTrue(prepare(declined).isEmpty());assertEquals(1,used(id));assertEquals(2,count("oms_gift_plan"));assertEquals(0,f.db.queryForObject("SELECT dirty FROM oms_gift_plan WHERE company_code='QUOTA' AND platform_order_id=?",Integer.class,declined.get("id")));
    }
    @Test void outerTransactionRollbackRestoresQuotaPlanAndLedger(){
        long id=save(limited("R","PRODUCT",2,"G"));scope(1,id);Map<String,Object> order=order(1,3);
        assertThrows(IllegalStateException.class,()->gifts.tx().execute(s->{prepare(order);throw new IllegalStateException("rollback");}));assertEquals(0,used(id));assertEquals(0,count("oms_gift_plan"));assertEquals(0,count("oms_gift_quota_award"));assertFalse(prepare(order).isEmpty());assertEquals(1,used(id));
    }
    @Test void uniqueLedgerConflictRollsBackCounterAndPlanTogether(){
        long id=save(limited("R","PRODUCT",2,"G"));scope(1,id);Map<String,Object> order=order(1,3);
        f.db.update("INSERT INTO oms_gift_quota_award(company_code,platform_order_id,plan_id,group_id,rule_key,requested_count,granted_count,max_count) VALUES ('QUOTA',?,999,?,'R',1,1,2)",order.get("id"),id);
        assertThrows(org.springframework.dao.DuplicateKeyException.class,()->prepare(order));assertEquals(0,used(id));assertEquals(0,count("oms_gift_plan"));
    }
    @Test void quotaReductionCannotOverwriteCurrentConsumptionAndEditDoesNotReset(){
        long id=save(limited("R","MULTIPLE",10,"G"));scope(1,id);prepare(order(1,6));assertThrows(IllegalArgumentException.class,()->change(id,1));assertEquals(2,used(id));change(id,3);assertEquals(2,used(id));assertEquals(1,number(prepare(order(1,9)).get(0),"quantity"));assertEquals(3,used(id));
    }
    @Test void unlimitedRulesDoNotReadOrWriteQuotaInAllocation(){
        JdbcTemplate db=mock(JdbcTemplate.class);GiftQuota quota=new GiftQuota(db);Map<String,Object> group=GiftRulesTest.group(1,Collections.singletonList(limited("R","PRODUCT",0,"G")));List<Map<String,Object>> groups=Collections.singletonList(group);
        GiftQuota.Reservation reservation=quota.lock("QUOTA",groups,calculate(groups,1,"2026-10-20 12:00:00",GiftRulesTest.basis(3,"300")));assertEquals(1,reservation.grant(group,rows(object(group.get("policy")).get("rules")).get(0),1));reservation.persist(1,1);verifyNoInteractions(db);
    }
    @Test void unmetConditionsDoNotCreatePlansOrTouchQuota(){
        long id=save(limited("R","PRODUCT",10,"G"));scope(1,id);assertTrue(prepare(order(1,1)).isEmpty());assertEquals(0,used(id));assertEquals(0,count("oms_gift_plan"));assertEquals(0,count("oms_gift_quota_award"));
    }
    @Test void exhaustedQuotaDoesNotTakeExclusiveQuotaLocksOrWriteCounters(){
        JdbcTemplate db=new JdbcTemplate(){
            @Override @SuppressWarnings("unchecked") public <T> List<T> query(String sql,org.springframework.jdbc.core.RowMapper<T> mapper,Object... args){
                assertFalse(sql.contains("FOR UPDATE"));return (List<T>)Collections.singletonList(map("group_id",1,"rule_key","R","max_count",10,"used_count",10));
            }
        };
        GiftQuota quota=new GiftQuota(db);Map<String,Object> rule=limited("R","PRODUCT",10,"G"),group=GiftRulesTest.group(1,Collections.singletonList(rule));List<Map<String,Object>> groups=Collections.singletonList(group);
        GiftQuota.Reservation reservation=quota.lock("QUOTA",groups,calculate(groups,1,"2026-10-20 12:00:00",GiftRulesTest.basis(3,"300")));assertEquals(0,reservation.grant(group,rule,1));reservation.persist(1,1);
    }
    @Test void firstLimitStartsCountingAndRemovingLimitDoesNotResetAnEstablishedCounter(){
        long id=save(limited("R","PRODUCT",0,"G"));scope(1,id);prepare(order(1,3));assertEquals(0,count("oms_gift_quota"));change(id,2);prepare(order(1,3));assertEquals(1,used(id));change(id,0);prepare(order(1,3));assertEquals(2,used(id));change(id,3);prepare(order(1,3));assertEquals(3,used(id));assertTrue(prepare(order(1,3)).isEmpty());
    }
    @Test void anotherInstanceCannotBypassNewLimitWithCachedUnlimitedPolicy(){
        long id=save(limited("R","PRODUCT",0,"G"));scope(1,id);gifts.published("QUOTA");GiftService other=service();Map<String,Object> g=group(id),p=object(g.get("policy"));List<Map<String,Object>> rules=rows(p.get("rules"));rules.get(0).put("max_count",1);p.put("rules",rules);g.put("policy",p);other.save("QUOTA",g,"other instance");
        assertFalse(prepare(order(1,3)).isEmpty());assertTrue(prepare(order(1,3)).isEmpty());assertEquals(1,used(id));
    }
    @Test void reverseExecutionOrderAcrossShopsUsesConsistentQuotaLockOrder() throws Exception {
        long a=save(limited("R","PRODUCT",100,"G")),b=save(limited("R","PRODUCT",100,"X"));scope(1,a,b);scope(2,b,a);List<Map<String,Object>> orders=new ArrayList<>();for(int i=0;i<40;i++)orders.add(order(1+i%2,3));for(List<Map<String,Object>> result:concurrent(orders))assertEquals(2,result.size());assertEquals(40,used(a));assertEquals(40,used(b));
    }
    @Test void independentCopyGetsNewQuotaAndRuleRemovalDoesNotEraseOriginalUsage(){
        long id=save(limited("R","PRODUCT",3,"G"));scope(1,id);prepare(order(1,3));Map<String,Object> g=group(id);g.remove("id");long copy=number(gifts.save("QUOTA",g,"test"),"id");assertEquals(0,used(copy));assertEquals(1,used(id));
        Map<String,Object> old=group(id),p=object(old.get("policy"));p.put("rules",Collections.singletonList(limited("NEW","PRODUCT",0,"X")));old.put("policy",p);gifts.save("QUOTA",old,"test");old=group(id);old.put("policy",GiftRulesTest.policy(Collections.singletonList(limited("R","PRODUCT",3,"G"))));gifts.save("QUOTA",old,"test");assertEquals(1,used(id));
    }
    @Test void concurrentLimitEditAndAwardsPreserveInvariant() throws Exception {
        long id=save(limited("R","PRODUCT",20,"G"));scope(1,id);scope(2,id);Map<String,Object> update=group(id),p=object(update.get("policy"));List<Map<String,Object>> rules=rows(p.get("rules"));rules.get(0).put("max_count",5);p.put("rules",rules);update.put("policy",p);
        ExecutorService editor=Executors.newSingleThreadExecutor();try{Future<?> edited=editor.submit(()->{try{service().save("QUOTA",update,"concurrent edit");}catch(IllegalArgumentException e){assertTrue(e.getMessage().contains("已占用"));}});List<Map<String,Object>> orders=new ArrayList<>();for(int i=0;i<60;i++)orders.add(order(1+i%2,3));concurrent(orders);edited.get(15,TimeUnit.SECONDS);Map<String,Object> budget=f.db.queryForMap("SELECT max_count,used_count FROM oms_gift_quota WHERE company_code='QUOTA' AND group_id=?",id);assertTrue(number(budget,"used_count")<=number(budget,"max_count"));assertEquals(used(id),count("oms_gift_quota_award"));}finally{editor.shutdownNow();}
    }
    @Test void savedDraftSimulationReadsRemainingQuotaWithoutConsuming(){
        long id=save(limited("R","MULTIPLE",3,"G"));scope(1,id);prepare(order(1,6));GiftStrategyDesigner designer=new GiftStrategyDesigner();designer.gifts=gifts;designer.catalog=gifts.catalog;
        Map<String,Object> input=map("group_id",id,"name","Draft","policy",group(id).get("policy"),"paid_time","2026-10-20 12:00:00","lines",GiftRulesTest.basis(9,"300"));Map<String,Object> result=designer.simulate("QUOTA",input);assertEquals(1,number(rows(result.get("gifts")).get(0),"quantity"));assertEquals(2,used(id));assertEquals(1,count("oms_gift_quota_award"));input.put("group_id",999999999);assertThrows(IllegalArgumentException.class,()->designer.simulate("QUOTA",input));
    }
}
