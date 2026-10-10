package com.oms.supplychain;

import com.oms.supplychain.service.warehouse.impl.*;
import com.oms.supplychain.service.warehouse.WarehouseReferenceClient;
import com.oms.supplychain.service.warehouse.PurchaseGoodsClient;
import com.oms.common.api.*;
import com.oms.supplychain.service.wms.*;
import org.springframework.test.util.ReflectionTestUtils;
import com.oms.common.model.entity.GoodsSkuSnInfo;
import com.ruoyi.common.core.domain.R;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

public class PurchaseWorkspaceTest {
    static AnnotationConfigApplicationContext context;static JdbcTemplate db;static PurchaseWorkspaceService service;static WarehouseWorkspaceService warehouses;
    static Set<String> fail=ConcurrentHashMap.newKeySet(),posted=ConcurrentHashMap.newKeySet();static AtomicInteger calls=new AtomicInteger();
    static volatile CountDownLatch inventoryEntered,inventoryRelease;
    long supplier;String supplierSn;
    @Configuration @EnableTransactionManagement static class Config {
        @Bean DataSource dataSource(){String url=System.getenv("PURCHASE_TEST_URL");if(url==null||!url.contains("/purchase_workspace_test?"))throw new IllegalStateException("Disposable schema required");return new DriverManagerDataSource(url,"root",System.getenv("INVENTORY_TEST_PASSWORD"));}
        @Bean JdbcTemplate jdbcTemplate(DataSource ds){return new JdbcTemplate(ds);}
        @Bean PlatformTransactionManager transactionManager(DataSource ds){return new DataSourceTransactionManager(ds);}
        @Bean WarehouseWorkspaceService warehouses(){return new WarehouseWorkspaceService();}
        @Bean WmsStore wmsStore(){return new WmsStore();}
        @Bean WmsTenantRoutes tenantRoutes(){return new WmsTenantRoutes();}
        @Bean WmsConnections wmsConnections(){WmsConnections c=new WmsConnections();ReflectionTestUtils.setField(c,"encryptionKey",java.util.Base64.getEncoder().encodeToString(new byte[32]));return c;}
        @Bean WmsInboundService inbound(){return new WmsInboundService();}
        @Bean WmsInteractionLog interactionLog(){return new WmsInteractionLog();}
        @Bean QimenProtocol qimen(){return new QimenProtocol();}
        @Bean JdHufuProtocol hufu(){return new JdHufuProtocol();}
        @Bean PurchaseWorkspaceService purchase(){return new PurchaseWorkspaceService();}
        @Bean WarehouseReferenceClient references(){return (company,codes,source)->R.ok(Collections.emptyList());}
        @Bean PurchaseGoodsClient goods(){return (query,company,source)->{if(query.getSkuSn().equals("UNKNOWN"))return R.ok(null);GoodsSkuSnInfo found=new GoodsSkuSnInfo();found.setSkuSn(query.getSkuSn());found.setGoodsName("商品"+query.getSkuSn());return R.ok(found);};}
        @Bean RemoteInventoryService inventory(){return (dto,company)->{calls.incrementAndGet();String sku=dto.getWmsInventoryBatch().getSkuSn();if(sku.equals("SLOW")&&inventoryEntered!=null){inventoryEntered.countDown();await(inventoryRelease);}try(java.sql.Connection connection=context.getBean(DataSource.class).getConnection();java.sql.PreparedStatement check=connection.prepareStatement(dto.getRelationSn().startsWith("WMS_RECEIPT_")?"SELECT COUNT(*) FROM wms_receipt_line WHERE CONCAT('WMS_RECEIPT_',id)=?":"SELECT COUNT(*) FROM wms_tickets WHERE sn=?")){check.setString(1,dto.getRelationSn());try(java.sql.ResultSet result=check.executeQuery()){result.next();assertEquals(1,result.getInt(1),"Execution key must be committed before inventory receives it");}}catch(java.sql.SQLException e){throw new IllegalStateException(e);}if(fail.contains(sku))return R.fail("模拟库存服务不可用");posted.add(company+":"+dto.getRelationSn()+":"+sku);return R.ok(true);};}
    }
    @BeforeAll static void init(){context=new AnnotationConfigApplicationContext(Config.class);db=context.getBean(JdbcTemplate.class);service=context.getBean(PurchaseWorkspaceService.class);warehouses=context.getBean(WarehouseWorkspaceService.class);ReflectionTestUtils.setField(context.getBean(WmsConnections.class),"encryptionKey",java.util.Base64.getEncoder().encodeToString(new byte[32]));WmsTenantRoutes routes=context.getBean(WmsTenantRoutes.class);ReflectionTestUtils.setField(routes,"configured","QM=master");routes.initialize();}
    @AfterAll static void shutdown(){context.close();}
    static Map<String,Object> m(Object...v){Map<String,Object> r=new LinkedHashMap<>();for(int i=0;i<v.length;i+=2)r.put((String)v[i],v[i+1]);return r;}
    @BeforeEach void seed(){for(String t:Arrays.asList("wms_interaction_log","wms_receipt_line","wms_receipt_event","wms_inbound_task","wms_connection","purchase_event","purchase_line","no_tickets_goods","wms_tickets_goods","no_tickets","wms_tickets","po_info","supplier_info","wms_simulation_store_info","owner_warehouse","owner_info","wms_real_store_info"))db.update("DELETE FROM "+t);fail.clear();posted.clear();calls.set(0);inventoryEntered=null;inventoryRelease=null;long o=warehouses.save("QM","owner",m("ownerCode","O","ownerName","货主","isEnable",2));long w=warehouses.save("QM","realStore",m("realStoreCode","W","wmsName","实仓","status",2,"wmsType",1));long r=warehouses.save("QM","ownerWarehouse",m("ownerId",o,"realStoreId",w,"status",2));warehouses.save("QM","simulationStore",m("wmsSimulationCode","V","wmsSimulationName","虚仓","inboundMode",2,"outboundMode",2,"status",2,"ownerWarehouseId",r));supplier=service.saveSupplier("QM",m("supplierName","供货商","companyName","供货公司","status",2),"tester");supplierSn=(String)service.detail("QM","supplier",supplier).get("supplierSn");}
    Map<String,Object> line(String sku,int qty,String price){return m("skuSn",sku,"quantity",qty,"purchasePrice",new BigDecimal(price));}
    long purchase(Map<String,Object>... lines){return service.savePurchase("QM",m("poName","测试采购","supplierSn",supplierSn,"wmsSimulationCode","V","lines",Arrays.asList(lines)),"tester");}
    @SuppressWarnings("unchecked") List<Map<String,Object>> lines(String kind,long key){return (List<Map<String,Object>>)service.detail("QM",kind,key).get("lines");}
    long receipt(long po,int qty){Map<String,Object> line=lines("purchase",po).get(0);return service.createReceipt("QM",po,m("lines",Collections.singletonList(m("lineId",line.get("id"),"quantity",qty))),"tester");}
    // A queued virtual ticket models interruption after approval committed, before automatic execution.
    long queuedApproval(long n){Object target=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(service);return new org.springframework.transaction.support.TransactionTemplate(context.getBean(PlatformTransactionManager.class)).execute(status->ReflectionTestUtils.invokeMethod(target,"prepareReceipt","QM",n,"tester"));}
    long ticket(long po,int qty){return queuedApproval(receipt(po,qty));}
    void receive(long t,int zp,int cp){Map<String,Object> l=lines("ticket",t).get(0);service.receive("QM",t,m("lines",Collections.singletonList(m("id",l.get("id"),"numberZp",zp,"numberCp",cp))),"tester");}
    int state(long po){return ((Number)service.detail("QM","purchase",po).get("poState")).intValue();}
    @Test void quantityTimesPriceDefinesPlan(){long p=purchase(line("A",5,"2.50"),line("B",3,"1.20"));Map<String,Object>d=service.detail("QM","purchase",p);assertEquals(new BigDecimal("16.10"),d.get("moneyExpected"));assertEquals(8,((Number)d.get("numberExpected")).intValue());}
    @Test void duplicateSkusRejectWholePurchase(){assertThrows(IllegalArgumentException.class,()->purchase(line("A",1,"1"),line("A",2,"1")));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM po_info",Integer.class));}
    @Test void skuCaseMatchesCaseSensitiveCatalog(){long p=purchase(line("SKU-A",1,"1"),line("sku-a",2,"2"));assertEquals(2,lines("purchase",p).size());}
    @Test void unknownSkuRejectsWholePurchase(){assertThrows(IllegalArgumentException.class,()->purchase(line("A",1,"1"),line("UNKNOWN",2,"1")));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM purchase_line",Integer.class));}
    @Test void negativeQuantityAndExtraPricePrecisionRejected(){assertThrows(IllegalArgumentException.class,()->purchase(line("A",-1,"1")));assertThrows(IllegalArgumentException.class,()->purchase(line("A",1,"1.123")));}
    @Test void disabledSupplierBlocksNewPurchaseButPreservesOldPlan(){long p=purchase(line("A",10,"2"));service.saveSupplier("QM",m("id",supplier,"supplierName","供货商","companyName","供货公司","status",1),"tester");assertThrows(IllegalArgumentException.class,()->purchase(line("A",1,"1")));assertEquals(1,state(p));assertThrows(IllegalArgumentException.class,()->service.approve("QM",p,"tester"));}
    @Test void supplierReferencesPreventDeletion(){purchase(line("A",1,"1"));assertThrows(IllegalArgumentException.class,()->service.deleteSupplier("QM",supplier,"tester"));}
    @Test void companyIsolationAcrossReadAndTransitions(){long p=purchase(line("A",10,"1"));assertEquals(0,service.page("OTHER","purchase",m(),1,10).getTotal());assertThrows(IllegalArgumentException.class,()->service.detail("OTHER","purchase",p));assertThrows(IllegalArgumentException.class,()->service.approve("OTHER",p,"tester"));assertThrows(IllegalArgumentException.class,()->service.deleteSupplier("OTHER",supplier,"tester"));}
    @Test void approvedPlanCannotBeRewritten(){long p=purchase(line("A",10,"1"));service.approve("QM",p,"tester");Map<String,Object>d=service.detail("QM","purchase",p);assertThrows(IllegalArgumentException.class,()->service.savePurchase("QM",d,"tester"));assertThrows(IllegalArgumentException.class,()->service.approve("QM",p,"tester"));}
    @Test void draftCannotScheduleReceipts(){long p=purchase(line("A",10,"1"));assertThrows(IllegalArgumentException.class,()->receipt(p,5));}
    @Test void draftWithReceiptIsBlockedUntilDataIsRepaired(){
        long p=purchase(line("A",10,"1"));service.approve("QM",p,"tester");receipt(p,5);
        db.update("UPDATE po_info SET po_state=1 WHERE id=?",p);
        Map<String,Object> d=service.detail("QM","purchase",p);
        assertEquals("草稿",d.get("stateLabel"));assertEquals(false,d.get("editable"));
        assertEquals(1,((Number)d.get("receiptCount")).intValue());assertEquals(true,d.get("stateNeedsReview"));
        Map<String,Object> exported=service.exportRows("QM","purchase",m("state",1)).get(0);
        assertEquals(d.get("stateLabel"),exported.get("stateLabel"));assertEquals(d.get("editBlockReason"),exported.get("editBlockReason"));
        assertThrows(IllegalArgumentException.class,()->service.savePurchase("QM",d,"tester"));
        assertEquals(1,state(p));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM no_tickets",Integer.class));
    }

    @Test void cancelledReceiptStillLocksPlanAndInvalidModernDraftCannotApprove(){
        long p=purchase(line("A",2,"1"));service.approve("QM",p,"tester");long n=receipt(p,1);
        service.cancel("QM","receipt",n,"延期","tester");db.update("UPDATE po_info SET po_state=1 WHERE id=?",p);
        Map<String,Object>d=service.detail("QM","purchase",p);assertEquals(false,d.get("editable"));assertEquals(true,d.get("stateNeedsReview"));
        assertThrows(IllegalArgumentException.class,()->service.savePurchase("QM",d,"tester"));assertThrows(IllegalArgumentException.class,()->service.approve("QM",p,"tester"));
    }
    @Test void otherCompanyReceiptDoesNotLockDraft(){
        long p=purchase(line("A",2,"1"));String po=service.detail("QM","purchase",p).get("poSn").toString();
        db.update("INSERT INTO no_tickets(no_sn,po_sn,company_code,no_state) VALUES('FOREIGN',?,'OTHER',1)",po);
        Map<String,Object>d=service.detail("QM","purchase",p);assertEquals(0,((Number)d.get("receiptCount")).intValue());assertEquals(true,d.get("editable"));
    }
    @Test void receiptHasIndependentDateAndRemarksAndInvalidInputDoesNotReserveQuantity(){
        long p=purchase(line("A",2,"1"));service.approve("QM",p,"tester");Map<String,Object>l=lines("purchase",p).get(0);
        Map<String,Object>body=m("expectedDate","2026-10-20","remarks","分批送货","lines",Collections.singletonList(m("lineId",l.get("id"),"quantity",1)));
        long n=service.createReceipt("QM",p,body,"tester");Map<String,Object>d=service.detail("QM","receipt",n);
        assertTrue(d.get("expectedCallbackTime").toString().startsWith("2026-10-20"));assertEquals("分批送货",d.get("remarks"));
        body.put("expectedDate","invalid");assertThrows(IllegalArgumentException.class,()->service.createReceipt("QM",p,body,"tester"));
        body.put("expectedDate","2026-10-21");body.put("remarks",String.join("",Collections.nCopies(256,"字")));
        assertThrows(IllegalArgumentException.class,()->service.createReceipt("QM",p,body,"tester"));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM no_tickets",Integer.class));
    }
    @Test void receiptInheritsWarehouseAndPreventsOverSchedule(){long p=purchase(line("A",10,"1"));service.approve("QM",p,"tester");long n=receipt(p,6);assertEquals("V",service.detail("QM","receipt",n).get("wmsSimulationCode"));assertThrows(IllegalArgumentException.class,()->receipt(p,5));receipt(p,4);}
    @Test void receiptApprovalCreatesExactlyOneExecution(){long p=purchase(line("A",10,"1"));service.approve("QM",p,"tester");long n=receipt(p,5);service.approveReceipt("QM",n,"tester");assertThrows(IllegalArgumentException.class,()->service.approveReceipt("QM",n,"tester"));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM wms_tickets",Integer.class));}
    @Test void virtualApprovalAutomaticallyReceivesAndPosts(){long p=purchase(line("A",5,"2"));service.approve("QM",p,"tester");long n=receipt(p,5),t=service.approveReceipt("QM",n,"tester");assertEquals(4,service.detail("QM","receipt",n).get("noState"));assertEquals(2,service.detail("QM","ticket",t).get("inventoryStatus"));assertEquals(5,service.detail("QM","purchase",p).get("numberActually"));assertEquals(4,state(p));assertEquals(1,calls.get());assertTrue(service.completeVirtual("QM",t,"tester"));assertEquals(1,calls.get());}
    @Test void automaticPartialFailureRetainsApprovalAndRetriesOnlyFailedLines(){
        long p=purchase(line("A",2,"1"),line("B",3,"1"));service.approve("QM",p,"tester");List<Map<String,Object>> selected=new ArrayList<>();for(Map<String,Object>l:lines("purchase",p))selected.add(m("lineId",l.get("id"),"quantity",l.get("quantity")));
        long n=service.createReceipt("QM",p,m("lines",selected),"tester");fail.add("B");long t=service.approveReceipt("QM",n,"tester");
        assertEquals(3,service.detail("QM","receipt",n).get("noState"));assertEquals(3,service.detail("QM","ticket",t).get("inventoryStatus"));assertEquals(2,service.detail("QM","purchase",p).get("numberActually"));assertEquals(2,calls.get());
        fail.clear();assertTrue(service.completeVirtual("QM",t,"tester"));assertEquals(3,calls.get());assertEquals(4,state(p));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM wms_tickets",Integer.class));
    }
    @Test void realApprovalWaitsForWmsWithoutInventoryCall(){long p=purchase(line("A",2,"1"));service.approve("QM",p,"tester");long n=receipt(p,2);realVirtual("QIMEN","https://example.invalid/router");long t=service.approveReceipt("QM",n,"tester");assertEquals(3,service.detail("QM","receipt",n).get("noState"));assertEquals(1,service.detail("QM","ticket",t).get("statusTicket"));assertEquals(0,calls.get());assertThrows(IllegalArgumentException.class,()->service.completeVirtual("QM",t,"tester"));}
    @Test void queuedVirtualExecutionCanResumeButInvalidStateCannot(){long p=purchase(line("A",2,"1"));service.approve("QM",p,"tester");long t=ticket(p,2);assertTrue(service.completeVirtual("QM",t,"tester"));assertEquals(4,state(p));long q=purchase(line("B",2,"1"));service.approve("QM",q,"tester");long held=ticket(q,2);db.update("UPDATE wms_tickets SET inventory_status=99 WHERE id=?",held);assertThrows(IllegalArgumentException.class,()->service.completeVirtual("QM",held,"tester"));assertEquals(1,calls.get());assertThrows(IllegalArgumentException.class,()->service.completeVirtual("OTHER",t,"tester"));}
    @Test void concurrentAutomaticCompletionCannotDoublePost() throws Exception {long p=purchase(line("A",2,"1"));service.approve("QM",p,"tester");long t=ticket(p,2);ExecutorService pool=Executors.newFixedThreadPool(2);try{CountDownLatch gate=new CountDownLatch(1);Callable<Boolean> run=()->{gate.await();return service.completeVirtual("QM",t,"tester");};Future<Boolean>a=pool.submit(run),b=pool.submit(run);gate.countDown();assertTrue(a.get(20,TimeUnit.SECONDS));assertTrue(b.get(20,TimeUnit.SECONDS));assertEquals(1,calls.get());assertEquals(2,service.detail("QM","purchase",p).get("numberActually"));}finally{pool.shutdownNow();}}
    @Test void splitReceiptsRollUpPurchaseProgress(){long p=purchase(line("A",10,"2"));service.approve("QM",p,"tester");long a=ticket(p,4);receive(a,3,1);assertEquals(0,calls.get());assertTrue(service.postInventory("QM",a,"tester"));assertEquals(3,state(p));long b=ticket(p,6);receive(b,6,0);assertTrue(service.postInventory("QM",b,"tester"));assertEquals(4,state(p));assertEquals(new BigDecimal("20.00"),service.detail("QM","purchase",p).get("moneyActually"));}
    @Test void shortReceiptReleasesRemainingForNextDelivery(){long p=purchase(line("A",10,"1"));service.approve("QM",p,"tester");long t=ticket(p,10);receive(t,6,0);service.postInventory("QM",t,"tester");receipt(p,4);assertThrows(IllegalArgumentException.class,()->receipt(p,1));}
    @Test void executionDoesNotImplyInventorySuccess(){long p=purchase(line("A",5,"1"));service.approve("QM",p,"tester");long t=ticket(p,5);receive(t,5,0);fail.add("A");assertFalse(service.postInventory("QM",t,"tester"));assertEquals(2,service.detail("QM","ticket",t).get("statusTicket"));assertEquals(3,service.detail("QM","ticket",t).get("inventoryStatus"));assertEquals(0,service.detail("QM","purchase",p).get("numberActually"));fail.clear();assertTrue(service.postInventory("QM",t,"tester"));assertEquals(4,state(p));}
    @Test void retriesSkipSuccessfulLines(){long p=purchase(line("A",2,"1"),line("B",3,"1"));service.approve("QM",p,"tester");List<Map<String,Object>> selected=new ArrayList<>();for(Map<String,Object>l:lines("purchase",p))selected.add(m("lineId",l.get("id"),"quantity",l.get("quantity")));long n=service.createReceipt("QM",p,m("lines",selected),"tester"),t=queuedApproval(n);List<Map<String,Object>> receive=new ArrayList<>();for(Map<String,Object> l:lines("ticket",t))receive.add(m("id",l.get("id"),"numberZp",l.get("numberExpected"),"numberCp",0));service.receive("QM",t,m("lines",receive),"tester");fail.add("B");assertFalse(service.postInventory("QM",t,"tester"));assertEquals(2,calls.get());assertEquals(2,service.detail("QM","purchase",p).get("numberActually"));fail.clear();assertTrue(service.postInventory("QM",t,"tester"));assertEquals(3,calls.get());assertTrue(service.postInventory("QM",t,"tester"));assertEquals(3,calls.get());assertEquals(2,posted.size());}
    @Test void overReceiptAndForeignLineRejected(){long p=purchase(line("A",2,"1"));service.approve("QM",p,"tester");long t=ticket(p,2);assertThrows(IllegalArgumentException.class,()->receive(t,2,1));assertThrows(IllegalArgumentException.class,()->service.receive("QM",t,m("lines",Collections.singletonList(m("id",-1,"numberZp",1,"numberCp",0))),"tester"));}
    @Test void confirmedReceiptMovesPurchaseToReceivingBeforeInventoryPosting(){long p=purchase(line("A",2,"1"));service.approve("QM",p,"tester");long t=ticket(p,2);receive(t,2,0);assertEquals(3,state(p));assertEquals(0,service.detail("QM","purchase",p).get("numberActually"));fail.add("A");service.postInventory("QM",t,"tester");assertEquals(3,state(p));}
    @Test void receivedQuantitiesLockBeforePosting(){long p=purchase(line("A",2,"1"));service.approve("QM",p,"tester");long t=ticket(p,2);receive(t,2,0);assertThrows(IllegalArgumentException.class,()->receive(t,1,0));assertThrows(IllegalArgumentException.class,()->service.cancel("QM","ticket",t,"作废","tester"));}
    @Test void zeroReceiptCompletesWithoutInventoryCall(){long p=purchase(line("A",2,"1"));service.approve("QM",p,"tester");long t=ticket(p,2);receive(t,0,0);assertTrue(service.postInventory("QM",t,"tester"));assertEquals(0,calls.get());receipt(p,2);}
    @Test void cancelKeepsAuditAndReleasesReservation(){long p=purchase(line("A",2,"1"));service.approve("QM",p,"tester");long t=ticket(p,2);service.cancel("QM","ticket",t,"供应商延期","tester");assertEquals(5,service.detail("QM","ticket",t).get("statusTicket"));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM wms_tickets",Integer.class));receipt(p,2);}
    @Test void closeRequiresNoInflightAndReason(){long p=purchase(line("A",3,"1"));service.approve("QM",p,"tester");long n=receipt(p,2);assertThrows(IllegalArgumentException.class,()->service.closePurchase("QM",p,"不再采购","tester"));service.cancel("QM","receipt",n,"取消到货","tester");assertThrows(IllegalArgumentException.class,()->service.closePurchase("QM",p,"","tester"));service.closePurchase("QM",p,"不再采购","tester");assertEquals(5,state(p));assertThrows(IllegalArgumentException.class,()->receipt(p,1));}

    @Test void invalidInventoryStateBlocksPosting(){long p=purchase(line("A",1,"1"));service.approve("QM",p,"tester");long t=ticket(p,1);receive(t,1,0);db.update("UPDATE wms_tickets SET inventory_status=99 WHERE id=?",t);assertThrows(IllegalArgumentException.class,()->service.postInventory("QM",t,"tester"));assertEquals(0,calls.get());}
    @Test void realWarehouseCannotBeSimulated(){long p=purchase(line("A",1,"1"));service.approve("QM",p,"tester");long t=ticket(p,1);db.update("UPDATE wms_tickets SET actual_warehouse=1 WHERE id=?",t);assertThrows(IllegalArgumentException.class,()->receive(t,1,0));assertThrows(IllegalArgumentException.class,()->service.postInventory("QM",t,"tester"));}
    @Test void paginationAndFilterMatchExports(){for(int i=0;i<4;i++)purchase(line("A",1,"1"));assertEquals(4,service.page("QM","purchase",m("keyword","测试"),1,2).getTotal());assertEquals(2,service.page("QM","purchase",m(),1,2).getRows().size());assertEquals(4,service.exportRows("QM","purchase",m("state",1)).size());assertEquals(0,service.exportRows("QM","purchase",m("state",4)).size());}
    @Test void readOnlyRoleCanListButCannotMutate() throws Exception {
        new com.ruoyi.common.core.utils.SpringUtils().postProcessBeanFactory(context.getBeanFactory());
        context.getBeanFactory().registerSingleton("testTokenService",new com.ruoyi.common.security.service.TokenService());
        com.ruoyi.common.security.auth.AuthLogic previous=com.ruoyi.common.security.auth.AuthUtil.authLogic;
        com.ruoyi.system.api.model.LoginUser login=new com.ruoyi.system.api.model.LoginUser();login.setCompanyCode("QM");
        com.ruoyi.common.core.context.SecurityContextHolder.set(com.ruoyi.common.core.constant.SecurityConstants.LOGIN_USER,login);
        com.ruoyi.common.security.auth.AuthUtil.authLogic=new com.ruoyi.common.security.auth.AuthLogic(){@Override public Set<String> getPermiList(){return new HashSet<>(Arrays.asList("warehouse:supplier:list","warehouse:supplier:query","warehouse:poInfo:list","warehouse:poInfo:query","warehouse:tickets:list","warehouse:tickets:query"));}};
        try {
            com.oms.supplychain.controller.warehouse.PurchaseWorkspaceController controller=new com.oms.supplychain.controller.warehouse.PurchaseWorkspaceController();java.lang.reflect.Field field=controller.getClass().getDeclaredField("service");field.setAccessible(true);field.set(controller,service);
            assertEquals(1,controller.list("supplier",m(),1,10).getTotal());assertNotNull(controller.detail("supplier",supplier));
            assertThrows(com.ruoyi.common.core.exception.auth.NotPermissionException.class,()->controller.supplier(m("id",supplier)));
            assertThrows(com.ruoyi.common.core.exception.auth.NotPermissionException.class,()->controller.purchase(m()));
            assertThrows(com.ruoyi.common.core.exception.auth.NotPermissionException.class,()->controller.approve(1));
            assertThrows(com.ruoyi.common.core.exception.auth.NotPermissionException.class,()->controller.receipt(1,m()));
            assertThrows(com.ruoyi.common.core.exception.auth.NotPermissionException.class,()->controller.approveReceipt(1));
            assertThrows(com.ruoyi.common.core.exception.auth.NotPermissionException.class,()->controller.receive(1,m()));
            assertThrows(com.ruoyi.common.core.exception.auth.NotPermissionException.class,()->controller.post(1));
            assertThrows(com.ruoyi.common.core.exception.auth.NotPermissionException.class,()->controller.completeVirtual(1));
            assertThrows(com.ruoyi.common.core.exception.auth.NotPermissionException.class,()->controller.close(1,Collections.singletonMap("reason","关闭")));
            assertThrows(com.ruoyi.common.core.exception.auth.NotPermissionException.class,()->controller.cancel("ticket",1,Collections.singletonMap("reason","作废")));
        } finally {com.ruoyi.common.security.auth.AuthUtil.authLogic=previous;com.ruoyi.common.core.context.SecurityContextHolder.remove();}
    }
    @Test void concurrentSchedulingCannotExceedPlan() throws Exception {long p=purchase(line("A",10,"1"));service.approve("QM",p,"tester");ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch gate=new CountDownLatch(1);Callable<Boolean> run=()->{gate.await();try{receipt(p,7);return true;}catch(IllegalArgumentException e){return false;}};Future<Boolean>a=pool.submit(run),b=pool.submit(run);gate.countDown();assertNotEquals(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));pool.shutdownNow();assertEquals(7,db.queryForObject("SELECT SUM(number_expected) FROM no_tickets",Integer.class));}

    static void await(CountDownLatch latch){try{assertTrue(latch.await(10,TimeUnit.SECONDS),"concurrency gate timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
    org.springframework.transaction.support.TransactionTemplate tx(){org.springframework.transaction.support.TransactionTemplate tx=new org.springframework.transaction.support.TransactionTemplate(context.getBean(PlatformTransactionManager.class));tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);return tx;}
    @Test void slowInventoryDoesNotBlockAnotherPurchaseOrMasterEdit() throws Exception {
        long p=purchase(line("SLOW",2,"1")),q=purchase(line("FAST",3,"1"));service.approve("QM",p,"tester");service.approve("QM",q,"tester");long t=ticket(p,2),u=ticket(q,3);
        inventoryEntered=new CountDownLatch(1);inventoryRelease=new CountDownLatch(1);ExecutorService pool=Executors.newFixedThreadPool(2);
        try{Future<Boolean> slow=pool.submit(()->service.completeVirtual("QM",t,"tester"));await(inventoryEntered);
            Future<Boolean> other=pool.submit(()->{Map<String,Object> w=warehouses.list("QM","realStore",m()).get(0);w.put("wmsName","并行修改名称");warehouses.save("QM","realStore",w);return service.completeVirtual("QM",u,"tester");});
            assertTrue(other.get(5,TimeUnit.SECONDS),"independent purchase must finish while first RPC is paused");assertEquals(4,state(q));assertFalse(slow.isDone());inventoryRelease.countDown();assertTrue(slow.get(10,TimeUnit.SECONDS));
        }finally{inventoryRelease.countDown();pool.shutdownNow();}
    }
    @Test void supplierDeleteWaitsForUncommittedPurchaseReference() throws Exception {assertReferenceRace(false);}
    @Test void virtualDeleteWaitsForUncommittedPurchaseReference() throws Exception {assertReferenceRace(true);}
    void assertReferenceRace(boolean virtual) throws Exception {
        CountDownLatch inserted=new CountDownLatch(1),commit=new CountDownLatch(1),deleting=new CountDownLatch(1);ExecutorService pool=Executors.newFixedThreadPool(2);
        long virtualId=((Number)warehouses.list("QM","simulationStore",m()).get(0).get("id")).longValue();
        try{Future<Long> create=pool.submit(()->tx().execute(status->{long p=purchase(line("A",1,"1"));inserted.countDown();await(commit);return p;}));await(inserted);
            Future<?> deletion=pool.submit(()->{deleting.countDown();assertThrows(IllegalArgumentException.class,()->{if(virtual)warehouses.delete("QM","simulationStore",Collections.singletonList(virtualId));else service.deleteSupplier("QM",supplier,"tester");});});await(deleting);
            assertThrows(TimeoutException.class,()->deletion.get(250,TimeUnit.MILLISECONDS));commit.countDown();create.get(10,TimeUnit.SECONDS);deletion.get(10,TimeUnit.SECONDS);
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM po_info",Integer.class));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM supplier_info",Integer.class));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM wms_simulation_store_info",Integer.class));
        }finally{commit.countDown();pool.shutdownNow();}
    }
    @Test void deletedSupplierCannotBeReferencedByNewPurchase(){service.deleteSupplier("QM",supplier,"tester");assertThrows(IllegalArgumentException.class,()->purchase(line("A",1,"1")));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM po_info",Integer.class));}
    @Test void cancelledAndCompletedPurchasesStillProtectMasters(){
        long p=purchase(line("A",1,"1"));service.cancel("QM","purchase",p,"取消","tester");assertThrows(IllegalArgumentException.class,()->service.deleteSupplier("QM",supplier,"tester"));
        long q=purchase(line("B",1,"1"));service.approve("QM",q,"tester");service.approveReceipt("QM",receipt(q,1),"tester");assertEquals(4,state(q));
        assertThrows(IllegalArgumentException.class,()->service.deleteSupplier("QM",supplier,"tester"));long v=((Number)warehouses.list("QM","simulationStore",m()).get(0).get("id")).longValue();assertThrows(IllegalArgumentException.class,()->warehouses.delete("QM","simulationStore",Collections.singletonList(v)));
    }
    @Test void simultaneousReceiptApprovalCreatesOneExecutionAndOnePosting() throws Exception {
        long p=purchase(line("A",2,"1"));service.approve("QM",p,"tester");long n=receipt(p,2);CountDownLatch start=new CountDownLatch(1);ExecutorService pool=Executors.newFixedThreadPool(2);
        try{Callable<Boolean> run=()->{await(start);try{service.approveReceipt("QM",n,"tester");return true;}catch(IllegalArgumentException e){return false;}};Future<Boolean>a=pool.submit(run),b=pool.submit(run);start.countDown();assertNotEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));assertEquals(1,calls.get());assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM wms_tickets",Integer.class));}finally{pool.shutdownNow();}
    }
    @Test void callbackRoutingRequiresExplicitCompanyMapping(){WmsTenantRoutes routes=new WmsTenantRoutes();ReflectionTestUtils.setField(routes,"configured","QM=master,SECOND=tenant2");routes.initialize();assertEquals("master",routes.datasource("qm"));assertEquals("tenant2",routes.datasource("SECOND"));assertThrows(IllegalArgumentException.class,()->routes.datasource("UNKNOWN"));}
    @Test void callbackRoutingRejectsMalformedOrDuplicateCompanies(){WmsTenantRoutes routes=new WmsTenantRoutes();ReflectionTestUtils.setField(routes,"configured","QM=master,qm=tenant2");assertThrows(IllegalArgumentException.class,routes::initialize);ReflectionTestUtils.setField(routes,"configured","QM=");assertThrows(IllegalArgumentException.class,routes::initialize);}
    WmsInboundService inbound(){return context.getBean(WmsInboundService.class);}
    long realVirtual(String provider,String endpoint){
        long key=context.getBean(WmsConnections.class).save("QM",m("name","WMS-"+UUID.randomUUID(),"provider",provider,"api_version",provider.equals("QIMEN")?"2.0":"1.0","environment","TEST","endpoint",endpoint,"app_key","APP","customer_id","CUSTOMER","secret","test-secret","enabled",1));
        db.update("UPDATE wms_simulation_store_info SET inbound_mode=1,connection_id=?,external_warehouse='EXT-W',external_owner='EXT-O' WHERE wms_simulation_code='V'",key);return key;
    }
    long realTicket(int quantity){realVirtual("QIMEN","https://example.invalid/router");long p=purchase(line("A",quantity,"2.50"));service.approve("QM",p,"tester");return service.approveReceipt("QM",receipt(p,quantity),"tester");}
    WmsProtocol.Receipt confirmation(long ticket,String message,int good,int bad,boolean complete){
        WmsProtocol.Receipt r=new WmsProtocol.Receipt();r.ticketSn=(String)service.detail("QM","ticket",ticket).get("sn");r.externalOrder="EXT-ORDER";r.messageId=message;r.warehouse="EXT-W";r.owner="EXT-O";r.status=complete?"FULFILLED":"PARTFULFILLED";r.complete=complete;
        WmsProtocol.Line l=new WmsProtocol.Line();l.sku="A";l.owner="EXT-O";l.batch="BATCH-1";l.good=good;l.bad=bad;r.lines.add(l);return r;
    }
    void accepted(long t){db.update("UPDATE wms_inbound_task SET dispatch_state='ACCEPTED' WHERE ticket_id=?",t);}
    @Test void sameOwnerAndRealWarehouseSupportDifferentVirtualModes(){
        long relation=((Number)warehouses.list("QM","ownerWarehouse",m()).get(0).get("id")).longValue();
        warehouses.save("QM","simulationStore",m("wmsSimulationCode","V2","wmsSimulationName","虚拟记账仓","inboundMode",2,"outboundMode",2,"status",2,"ownerWarehouseId",relation));
        realVirtual("QIMEN","https://example.invalid/router");assertEquals(1,warehouses.resolve("QM","V",true).getInboundMode());assertEquals(2,warehouses.resolve("QM","V2",true).getInboundMode());
    }
    @Test void approvalAtomicallyQueuesRealInboundAndFreezesMapping(){long t=realTicket(10);Map<String,Object> task=inbound().task("QM",t);assertEquals("PENDING",task.get("dispatch_state"));db.update("UPDATE wms_simulation_store_info SET external_warehouse='CHANGED',inbound_mode=2");assertEquals("EXT-W",inbound().task("QM",t).get("external_warehouse"));assertEquals(1,service.detail("QM","ticket",t).get("actualWarehouse"));assertEquals(0,calls.get());}
    @Test void realPartialReceiptsPostOnlyIncrementsAndDuplicatesNeverRepost(){long t=realTicket(10);accepted(t);Map<String,Object> task=inbound().task("QM",t);WmsProtocol.Receipt first=confirmation(t,"M1",3,1,false);inbound().accept("QM",task,first);assertTrue(inbound().post("QM",t));assertEquals(4,((Number)service.detail("QM","ticket",t).get("numberActually")).intValue());assertEquals(1,calls.get());inbound().accept("QM",task,first);inbound().post("QM",t);assertEquals(1,calls.get());inbound().accept("QM",task,confirmation(t,"M2",6,0,true));inbound().post("QM",t);assertEquals(2,calls.get());assertEquals(2,service.detail("QM","ticket",t).get("inventoryStatus"));assertEquals(10,((Number)service.detail("QM","ticket",t).get("numberActually")).intValue());}
    @Test void conflictingMessageAndOverReceiptLeaveQuantitiesUnchanged(){long t=realTicket(5);accepted(t);Map<String,Object> task=inbound().task("QM",t);inbound().accept("QM",task,confirmation(t,"M1",2,0,false));assertThrows(IllegalArgumentException.class,()->inbound().accept("QM",task,confirmation(t,"M1",3,0,false)));assertThrows(IllegalArgumentException.class,()->inbound().accept("QM",task,confirmation(t,"M2",4,0,true)));assertEquals(2,((Number)service.detail("QM","ticket",t).get("numberActually")).intValue());}
    @Test void shortFinalReceiptReleasesRemainingPurchase(){long t=realTicket(10);accepted(t);inbound().accept("QM",inbound().task("QM",t),confirmation(t,"SHORT",8,0,true));inbound().post("QM",t);Map<String,Object> td=service.detail("QM","ticket",t);long po=db.queryForObject("SELECT id FROM po_info WHERE po_sn=?",Long.class,td.get("originalSn"));assertEquals(8,((Number)lines("purchase",po).get(0).get("scheduledQuantity")).intValue());assertEquals(3,state(po));assertEquals(4,db.queryForObject("SELECT no_state FROM no_tickets WHERE no_sn=?",Integer.class,td.get("relationSn")));}
    @Test void realInventoryFailureCanRetryWithoutRepeatingSuccessfulReceipt(){long t=realTicket(5);accepted(t);inbound().accept("QM",inbound().task("QM",t),confirmation(t,"M1",5,0,true));fail.add("A");assertFalse(inbound().post("QM",t));assertEquals(3,service.detail("QM","ticket",t).get("inventoryStatus"));fail.clear();assertTrue(service.postInventory("QM",t,"tester"));int before=calls.get();assertTrue(service.postInventory("QM",t,"tester"));assertEquals(before,calls.get());}
    @Test void callbackRejectsWrongWarehouseOwnerAndTenant(){long t=realTicket(5);accepted(t);WmsProtocol.Receipt r=confirmation(t,"M1",2,0,false);r.owner="FOREIGN";assertThrows(IllegalArgumentException.class,()->inbound().accept("QM",inbound().task("QM",t),r));assertNull(inbound().task("OTHER",t));assertEquals(0,((Number)service.detail("QM","ticket",t).get("numberActually")).intValue());}
    @Test void unknownDispatchCannotBeBlindlyRetried(){long t=realTicket(1);db.update("UPDATE wms_inbound_task SET dispatch_state='UNKNOWN' WHERE ticket_id=?",t);assertThrows(IllegalArgumentException.class,()->inbound().retry("QM",t));}
    @Test void connectionListNeverExposesSecretAndBoundTargetCannotChange(){long t=realTicket(1);Map<String,Object> task=inbound().task("QM",t);WmsConnections c=context.getBean(WmsConnections.class);Map<String,Object> row=c.list("QM").get(0);assertFalse(row.containsKey("secret_cipher"));assertFalse(row.containsKey("secret"));assertFalse(db.queryForObject("SELECT secret_cipher FROM wms_connection WHERE id=?",String.class,task.get("connection_id")).contains("test-secret"));row.put("endpoint","https://different.invalid");assertThrows(IllegalArgumentException.class,()->c.save("QM",row));}

    Map<String,String> signed(String provider,String body){Map<String,String> p=new TreeMap<>();p.put("app_key","APP");p.put("customerId","CUSTOMER");p.put("method",provider.equals("QIMEN")?"taobao.qimen.entryorder.confirm":"jingdong.hufu.entryorder.confirm");p.put("v",provider.equals("QIMEN")?"2.0":"1.0");p.put("sign_method","md5");p.put("timestamp",java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));p.put("sign",QimenProtocol.sign(p,body,"test-secret"));return p;}
    String callbackBody(String provider,String sn){
        if(provider.equals("QIMEN"))return "<request><entryOrder><entryOrderCode>"+sn+"</entryOrderCode><entryOrderId>EXT-HTTP</entryOrderId><outBizCode>HTTP1</outBizCode><ownerCode>EXT-O</ownerCode><warehouseCode>EXT-W</warehouseCode><status>FULFILLED</status><confirmType>0</confirmType><totalOrderLines>1</totalOrderLines></entryOrder><orderLines><orderLine><itemCode>A</itemCode><ownerCode>EXT-O</ownerCode><actualQty>5</actualQty><batchs><batch><batchCode>B1</batchCode><inventoryType>ZP</inventoryType><actualQty>3</actualQty></batch><batch><batchCode>B2</batchCode><inventoryType>CC</inventoryType><actualQty>2</actualQty></batch></batchs></orderLine></orderLines></request>";
        return "{\"entryOrder\":{\"entryOrderCode\":\""+sn+"\",\"entryOrderId\":\"EXT-HTTP\",\"outBizCode\":\"HTTP1\",\"ownerCode\":\"EXT-O\",\"warehouseCode\":\"EXT-W\",\"status\":\"FULFILLED\",\"confirmType\":\"0\",\"totalOrderLines\":1},\"orderLines\":[{\"itemCode\":\"A\",\"ownerCode\":\"EXT-O\",\"actualQty\":5,\"batchs\":[{\"batchCode\":\"B1\",\"inventoryType\":\"ZP\",\"actualQty\":3},{\"batchCode\":\"B2\",\"inventoryType\":\"CC\",\"actualQty\":2}]}]}";
    }
    void httpRoundTrip(String provider) throws Exception {
        com.sun.net.httpserver.HttpServer server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        java.util.concurrent.atomic.AtomicReference<Throwable> serverError=new java.util.concurrent.atomic.AtomicReference<>();AtomicInteger requests=new AtomicInteger();
        server.createContext("/router",exchange->{try{String body=org.springframework.util.StreamUtils.copyToString(exchange.getRequestBody(),java.nio.charset.StandardCharsets.UTF_8);Map<String,String> params=new TreeMap<>();for(String pair:exchange.getRequestURI().getRawQuery().split("&")){String[] kv=pair.split("=",2);params.put(java.net.URLDecoder.decode(kv[0],"UTF-8"),java.net.URLDecoder.decode(kv[1],"UTF-8"));}assertEquals(QimenProtocol.sign(params,body,"test-secret"),params.get("sign"));assertTrue(body.contains("EXT-W"));assertTrue(body.contains("EXT-O"));assertTrue(body.contains("CGRK"));if(provider.equals("JD_HUFU"))assertEquals("/router/entryOrderCreate",exchange.getRequestURI().getPath());requests.incrementAndGet();String reply=provider.equals("QIMEN")?"<response><flag>success</flag><entryOrderId>EXT-HTTP</entryOrderId></response>":"{\"flag\":\"success\",\"entryOrderId\":\"EXT-HTTP\"}";byte[] bytes=reply.getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);}catch(Throwable e){serverError.set(e);}finally{exchange.close();}});server.start();
        try{long c=realVirtual(provider,"http://127.0.0.1:"+server.getAddress().getPort()+"/router"),po=purchase(line("A",5,"2"));service.approve("QM",po,"tester");long t=service.approveReceipt("QM",receipt(po,5),"tester");Map<String,Object> task=inbound().task("QM",t);inbound().dispatch("QM",((Number)task.get("id")).longValue());assertNull(serverError.get());assertEquals(1,requests.get());assertEquals("ACCEPTED",inbound().task("QM",t).get("dispatch_state"));String body=callbackBody(provider,(String)task.get("ticket_sn")),key=db.queryForObject("SELECT callback_key FROM wms_connection WHERE id=?",String.class,c);Map<String,String> signed=signed(provider,body);Map<String,String> bad=new TreeMap<>(signed);bad.put("sign","WRONG");assertTrue(inbound().callback("QM",key,bad,body).contains("failure"));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM wms_receipt_line",Integer.class));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM wms_interaction_log WHERE direction='IN' AND result='FAILED'",Integer.class));assertTrue(inbound().callback("QM",key,signed,body).contains("success"));assertTrue(inbound().callback("QM",key,signed,body).contains("success"));assertTrue(inbound().post("QM",t));assertEquals(2,calls.get());assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM wms_receipt_line",Integer.class));assertEquals(4,state(po));assertEquals(5,service.detail("QM","purchase",po).get("numberActually"));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM wms_interaction_log WHERE direction='OUT' AND result='SUCCESS'",Integer.class));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM wms_interaction_log WHERE request_body LIKE '%test-secret%' OR response_body LIKE '%test-secret%'",Integer.class));}finally{server.stop(0);}
    }
    @Test void qimenHttpSigningAndMultibatchCallbackPostExactlyOnce() throws Exception {httpRoundTrip("QIMEN");}
    @Test void jdHufuHttpSigningAndMultibatchCallbackPostExactlyOnce() throws Exception {httpRoundTrip("JD_HUFU");}
    @Test void pendingTaskCanCancelLocallyWithoutCallingWarehouse(){long t=realTicket(5);inbound().cancel("QM",t,"测试取消","tester");assertEquals("CANCELED",inbound().task("QM",t).get("dispatch_state"));assertEquals(5,service.detail("QM","ticket",t).get("statusTicket"));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM wms_interaction_log",Integer.class));}
    @Test void signedCancellationIsIdempotentAndCannotBeFollowedByReceipt(){long t=realTicket(5);accepted(t);Map<String,Object> task=inbound().task("QM",t);WmsProtocol.Receipt cancel=confirmation(t,"CANCEL",0,0,false);cancel.lines.clear();cancel.status="CANCELED";inbound().accept("QM",task,cancel);inbound().accept("QM",task,cancel);assertEquals("CANCELED",inbound().task("QM",t).get("dispatch_state"));assertThrows(IllegalArgumentException.class,()->inbound().accept("QM",task,confirmation(t,"LATE",1,0,true)));}
    @Test void finalShortReceiptCannotConsumeQuantityReallocatedToAnotherDelivery(){long t=realTicket(5);accepted(t);Map<String,Object> task=inbound().task("QM",t);inbound().accept("QM",task,confirmation(t,"FINAL",3,0,true));inbound().post("QM",t);long po=db.queryForObject("SELECT id FROM po_info",Long.class);receipt(po,2);assertThrows(IllegalArgumentException.class,()->inbound().accept("QM",task,confirmation(t,"LATE",2,0,false)));assertEquals(3,((Number)service.detail("QM","ticket",t).get("numberActually")).intValue());}
    @Test void concurrentDuplicateCallbacksAndPostingAreSerialized() throws Exception {long t=realTicket(5);accepted(t);Map<String,Object> task=inbound().task("QM",t);WmsProtocol.Receipt r=confirmation(t,"SAME",5,0,true);ExecutorService pool=Executors.newFixedThreadPool(2);try{CountDownLatch gate=new CountDownLatch(1);Callable<Boolean> run=()->{gate.await();inbound().accept("QM",task,r);return inbound().post("QM",t);};Future<Boolean>a=pool.submit(run),b=pool.submit(run);gate.countDown();assertTrue(a.get(20,TimeUnit.SECONDS));assertTrue(b.get(20,TimeUnit.SECONDS));assertEquals(1,calls.get());assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM wms_receipt_event",Integer.class));}finally{pool.shutdownNow();}}
    @Test void incompleteReceiptAndUnsafeXmlAreRejected(){long t=realTicket(5);accepted(t);Map<String,Object> task=inbound().task("QM",t);WmsProtocol.Receipt r=confirmation(t,"FRAGMENT",3,0,true);r.totalLines=2;r.sourceLineCount=1;assertThrows(IllegalArgumentException.class,()->inbound().accept("QM",task,r));String body="<!DOCTYPE request [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><request><entryOrder>&x;</entryOrder></request>";assertThrows(IllegalArgumentException.class,()->new QimenProtocol().callback(m("app_key","APP","customer_id","CUSTOMER"),"test-secret",signed("QIMEN",body),body));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM wms_receipt_event",Integer.class));}

    @Test void ticketReadModelSeparatesPartialPostingFinalShortAndComplete() {
        long t=realTicket(10);accepted(t);Map<String,Object> task=inbound().task("QM",t);
        inbound().accept("QM",task,confirmation(t,"FIRST",3,0,false));inbound().post("QM",t);
        Map<String,Object> d=service.detail("QM","ticket",t);
        assertEquals("PROCESSING",d.get("progress"));assertNull(d.get("shortQuantity"));assertEquals(3,((Number)d.get("numberPosted")).intValue());
        assertEquals("全部实收入账",d.get("postingLabel"));
        inbound().accept("QM",task,confirmation(t,"FINAL",5,0,true));
        d=service.detail("QM","ticket",t);assertEquals("PROCESSING",d.get("progress"));assertEquals("部分入账",d.get("postingLabel"));assertEquals(5,((Number)d.get("numberPending")).intValue());
        fail.add("A");inbound().post("QM",t);assertEquals("ATTENTION",service.detail("QM","ticket",t).get("progress"));
        assertEquals(1,service.page("QM","ticket",m("progress","ATTENTION"),1,10).getTotal());
        fail.clear();inbound().post("QM",t);d=service.detail("QM","ticket",t);assertEquals("COMPLETE",d.get("progress"));assertEquals("已完成 · 短收 2",d.get("progressLabel"));
        List<Map<String,Object>> exported=service.exportRows("QM","ticket",m("progress","COMPLETE","actualWarehouse",1,"provider","QIMEN"));
        assertEquals(1,exported.size());assertEquals(d.get("progressLabel"),exported.get(0).get("progressLabel"));
        assertEquals(0,service.page("QM","ticket",m("progress","ATTENTION"),1,10).getTotal());
        assertEquals(1,service.page("QM","line",m("progress","COMPLETE"),1,10).getTotal());
        long po=db.queryForObject("SELECT id FROM po_info WHERE po_sn=?",Long.class,d.get("originalSn"));
        assertEquals(d.get("progressLabel"),((List<Map<String,Object>>)service.detail("QM","purchase",po).get("tickets")).get(0).get("progressLabel"));
    }

    @Test void actualBatchPaginationShowsReceiptBatchesAndEnforcesCompanyScope() {
        long t=realTicket(5);accepted(t);Map<String,Object> task=inbound().task("QM",t);
        WmsProtocol.Receipt first=confirmation(t,"FIRST",3,0,false);first.lines.get(0).batch="ACTUAL-1";
        WmsProtocol.Receipt second=confirmation(t,"SECOND",0,2,true);second.lines.get(0).batch="ACTUAL-2";
        inbound().accept("QM",task,first);inbound().post("QM",t);inbound().accept("QM",task,second);
        assertEquals(2,service.receiptBatches("QM",t,1,1).getTotal());
        Map<String,Object> newest=(Map<String,Object>)service.receiptBatches("QM",t,1,1).getRows().get(0);
        assertEquals("ACTUAL-2",newest.get("batchCode"));assertEquals(0,newest.get("posted"));
        Map<String,Object> earlier=(Map<String,Object>)service.receiptBatches("QM",t,2,1).getRows().get(0);
        assertEquals("ACTUAL-1",earlier.get("batchCode"));assertEquals(1,earlier.get("posted"));
        assertThrows(IllegalArgumentException.class,()->service.receiptBatches("OTHER",t,1,20));
        assertThrows(IllegalArgumentException.class,()->inbound().cancel("QM",t,"取消","tester"));
    }

    @Test void warehouseQueryReportsFailureAndNeverPostsFromStatusAlone() throws Exception {
        com.sun.net.httpserver.HttpServer server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        java.util.concurrent.atomic.AtomicReference<String> response=new java.util.concurrent.atomic.AtomicReference<>("<response><flag>success</flag><status>FULFILLED</status><entryOrderId>REMOTE</entryOrderId></response>");
        server.createContext("/router",exchange->{try{byte[] bytes=response.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);}finally{exchange.close();}});server.start();
        try {
            realVirtual("QIMEN","http://127.0.0.1:"+server.getAddress().getPort()+"/router");long po=purchase(line("A",5,"2"));service.approve("QM",po,"tester");long t=service.approveReceipt("QM",receipt(po,5),"tester");accepted(t);
            Map<String,Object> query=inbound().query("QM",t);assertEquals(true,query.get("success"));assertEquals("FULFILLED",query.get("warehouseStatus"));assertEquals(true,query.get("localUpdated"));
            assertEquals(true,service.detail("QM","ticket",t).get("receiptMissing"));assertEquals("ATTENTION",service.detail("QM","ticket",t).get("progress"));assertEquals(0,calls.get());
            response.set("<response><flag>failure</flag><message>仓库查询拒绝</message></response>");query=inbound().query("QM",t);assertEquals(false,query.get("success"));assertEquals("FAILED",query.get("outcome"));assertEquals(false,query.get("localUpdated"));assertEquals("ACCEPTED",inbound().task("QM",t).get("dispatch_state"));
            response.set("<response><flag>success</flag><status>UNRECOGNIZED</status></response>");query=inbound().query("QM",t);assertEquals(false,query.get("success"));assertEquals("UNSUPPORTED",query.get("outcome"));
            assertEquals(0,calls.get());assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM wms_receipt_line",Integer.class));
        } finally {server.stop(0);}
    }

    @Test void cancellationRequiresReasonAndKeepsAuditTrail() {
        long t=realTicket(5);assertThrows(IllegalArgumentException.class,()->inbound().cancel("QM",t," ","tester"));
        assertEquals("PENDING",inbound().task("QM",t).get("dispatch_state"));
        inbound().cancel("QM",t,"采购调整","tester");assertEquals("采购调整",inbound().task("QM",t).get("cancel_reason"));
        assertEquals("CANCELED",service.detail("QM","ticket",t).get("progress"));assertEquals(1,service.page("QM","ticket",m("progress","CANCELED"),1,10).getTotal());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM purchase_event WHERE action='申请取消入库' AND message LIKE '%采购调整%'",Integer.class));
    }

}
