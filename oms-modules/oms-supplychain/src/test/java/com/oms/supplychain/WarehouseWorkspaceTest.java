package com.oms.supplychain;

import com.oms.supplychain.controller.warehouse.*;
import com.oms.supplychain.model.dto.warehouse.SimulationStoreInfoDto;
import com.oms.supplychain.model.vo.warehouse.WarehouseExportRow;
import com.oms.supplychain.service.warehouse.WarehouseReferenceClient;
import com.oms.supplychain.service.warehouse.impl.WarehouseWorkspaceService;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

/** Real MySQL transactions, restricted to a disposable schema. */
public class WarehouseWorkspaceTest {
    static AnnotationConfigApplicationContext context;static JdbcTemplate db;static WarehouseWorkspaceService workspace;
    static AtomicBoolean used=new AtomicBoolean(),offline=new AtomicBoolean();
    long owner,warehouse,relation,virtual;
    @Configuration @EnableTransactionManagement static class Config {
        @Bean DataSource dataSource(){String url=System.getenv("WAREHOUSE_TEST_URL");if(url==null || !url.contains("/warehouse_workspace_test?"))throw new IllegalStateException("Disposable schema required");return new DriverManagerDataSource(url,"root",System.getenv("INVENTORY_TEST_PASSWORD"));}
        @Bean JdbcTemplate jdbcTemplate(DataSource ds){return new JdbcTemplate(ds);}
        @Bean PlatformTransactionManager transactionManager(DataSource ds){return new DataSourceTransactionManager(ds);}
        @Bean WarehouseWorkspaceService workspace(){return new WarehouseWorkspaceService();}
        @Bean WarehouseReferenceClient references(){return (company,codes,source)->{if(offline.get())throw new IllegalStateException("offline");return R.ok(used.get()?codes:Collections.emptyList());};}
    }
    @BeforeAll static void init(){context=new AnnotationConfigApplicationContext(Config.class);db=context.getBean(JdbcTemplate.class);workspace=context.getBean(WarehouseWorkspaceService.class);}
    @AfterAll static void close(){context.close();}
    @BeforeEach void seed(){
        for(String table:Arrays.asList("po_info","no_tickets","wms_tickets","wms_simulation_store_info","owner_warehouse","owner_info","wms_real_store_info"))db.update("DELETE FROM "+table);
        used.set(false);offline.set(false);
        owner=workspace.save("QM","owner",owner("OWNER-A"));warehouse=workspace.save("QM","realStore",warehouse("WH-A"));
        relation=workspace.save("QM","ownerWarehouse",relation(owner,warehouse));virtual=workspace.save("QM","simulationStore",virtual("V-A",relation));
    }
    static Map<String,Object> data(Object... values){Map<String,Object> row=new HashMap<>();for(int i=0;i<values.length;i+=2)row.put((String)values[i],values[i+1]);return row;}
    static Map<String,Object> owner(String code){return data("ownerCode",code,"ownerName","青木货主","isEnable",2);}
    static Map<String,Object> warehouse(String code){return data("realStoreCode",code,"wmsName","广州仓","status",2,"wmsType",1,"director","张三","mobilePhone","020-12345678","province","广东","city","广州");}
    static Map<String,Object> relation(long owner,long warehouse){return data("ownerId",owner,"realStoreId",warehouse,"status",2);}
    static Map<String,Object> virtual(String code,long relation){return data("wmsSimulationCode",code,"wmsSimulationName","业务虚仓","status",2,"inboundMode",2,"outboundMode",2,"ownerWarehouseId",relation);}
    Map<String,Object> detail(String kind,long id){return new HashMap<>(workspace.detail("QM",kind,id));}
    void disable(String kind,long id){Map<String,Object> r=detail(kind,id);r.put(kind.equals("owner")?"isEnable":"status",1);workspace.save("QM",kind,r);}
    @Test void oneOwnerUsesTwoPhysicalWarehousesWithoutDuplicatingOwner(){long second=workspace.save("QM","realStore",warehouse("WH-B"));long link=workspace.save("QM","ownerWarehouse",relation(owner,second));workspace.save("QM","simulationStore",virtual("V-B",link));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM owner_info",Integer.class));assertEquals(2L,((Number)detail("owner",owner).get("warehouseCount")).longValue());assertEquals("WH-B",workspace.resolve("QM","V-B",true).getOwnerInfo().getRealStoreCode());assertEquals("WH-A",workspace.resolve("QM","V-A",true).getOwnerInfo().getRealStoreCode());}
    @Test void onePhysicalWarehouseServesTwoOwnersWithoutMergingStockIdentity(){long other=workspace.save("QM","owner",owner("OWNER-B"));long link=workspace.save("QM","ownerWarehouse",relation(other,warehouse));workspace.save("QM","simulationStore",virtual("V-B",link));assertEquals(2L,((Number)detail("realStore",warehouse).get("ownerCount")).longValue());assertEquals("OWNER-A",workspace.resolve("QM","V-A",true).getOwnerCode());assertEquals("OWNER-B",workspace.resolve("QM","V-B",true).getOwnerCode());}
    @Test void virtualOwnershipAndRelationEndpointsAreImmutable(){long second=workspace.save("QM","realStore",warehouse("WH-B"));long link=workspace.save("QM","ownerWarehouse",relation(owner,second));Map<String,Object> v=detail("simulationStore",virtual);v.put("ownerWarehouseId",link);assertThrows(IllegalArgumentException.class,()->workspace.save("QM","simulationStore",v));Map<String,Object> r=detail("ownerWarehouse",relation);r.put("realStoreId",second);assertThrows(IllegalArgumentException.class,()->workspace.save("QM","ownerWarehouse",r));}
    @Test void codesAreImmutableAndUniqueWithinCompany(){for(String kind:Arrays.asList("owner","realStore","simulationStore")){long id=kind.equals("owner")?owner:kind.equals("realStore")?warehouse:virtual;String key=kind.equals("owner")?"ownerCode":kind.equals("realStore")?"realStoreCode":"wmsSimulationCode";Map<String,Object> r=detail(kind,id);r.put(key,"CHANGED");assertThrows(IllegalArgumentException.class,()->workspace.save("QM",kind,r));}assertThrows(IllegalArgumentException.class,()->workspace.save("QM","owner",owner("OWNER-A")));workspace.save("OTHER","owner",owner("OWNER-A"));}
    @Test void crossCompanyIdsAndRelationsAreRejected(){assertThrows(IllegalArgumentException.class,()->workspace.detail("OTHER","owner",owner));assertThrows(IllegalArgumentException.class,()->workspace.save("OTHER","ownerWarehouse",relation(owner,warehouse)));assertThrows(IllegalArgumentException.class,()->workspace.save("OTHER","simulationStore",virtual("OTHER-V",relation)));assertThrows(IllegalArgumentException.class,()->workspace.delete("OTHER","owner",Collections.singletonList(owner)));assertEquals(0,workspace.page("OTHER","simulationStore",data(),1,10).getTotal());}
    @Test void duplicateAssociationIsRejected(){assertThrows(IllegalArgumentException.class,()->workspace.save("QM","ownerWarehouse",relation(owner,warehouse)));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM owner_warehouse",Integer.class));}
    @Test void renamesUseCurrentNamesWithoutChangingOwnership(){Map<String,Object> o=detail("owner",owner);o.put("ownerName","新的货主名称");workspace.save("QM","owner",o);Map<String,Object> w=detail("realStore",warehouse);w.put("wmsName","新的仓库名称");workspace.save("QM","realStore",w);Map<String,Object> v=detail("simulationStore",virtual);assertEquals("新的货主名称",v.get("ownerName"));assertEquals("新的仓库名称",v.get("wmsName"));assertEquals("OWNER-A",v.get("ownerCode"));}
    @Test void eachDisabledLinkBlocksNewBusinessButRetainsHistory(){for(String kind:Arrays.asList("owner","realStore","ownerWarehouse","simulationStore")){long id=kind.equals("owner")?owner:kind.equals("realStore")?warehouse:kind.equals("ownerWarehouse")?relation:virtual;disable(kind,id);assertThrows(IllegalArgumentException.class,()->workspace.resolve("QM","V-A",true));assertNotNull(workspace.resolve("QM","V-A",false));assertEquals(0,workspace.list("QM","simulationStore",data("effectiveEnabled",1)).size());Map<String,Object> r=detail(kind,id);r.put(kind.equals("owner")?"isEnable":"status",2);workspace.save("QM",kind,r);}}
    @Test void enabledVirtualCannotUseDisabledOwnerOrAssociation(){disable("owner",owner);assertThrows(IllegalArgumentException.class,()->workspace.save("QM","simulationStore",virtual("V-B",relation)));Map<String,Object> v=virtual("V-B",relation);v.put("status",1);workspace.save("QM","simulationStore",v);assertEquals(2,workspace.page("QM","simulationStore",data(),1,10).getTotal());}
    @Test void referenceDeletionGuardsCoverAllThreeLevels(){assertThrows(IllegalArgumentException.class,()->workspace.delete("QM","owner",Collections.singletonList(owner)));assertThrows(IllegalArgumentException.class,()->workspace.delete("QM","realStore",Collections.singletonList(warehouse)));assertThrows(IllegalArgumentException.class,()->workspace.delete("QM","ownerWarehouse",Collections.singletonList(relation)));used.set(true);assertThrows(IllegalArgumentException.class,()->workspace.delete("QM","simulationStore",Collections.singletonList(virtual)));}
    @Test void missingInventoryServiceFailsClosed(){offline.set(true);assertThrows(IllegalArgumentException.class,()->workspace.delete("QM","simulationStore",Collections.singletonList(virtual)));assertNotNull(detail("simulationStore",virtual));}
    @Test void historicalDocumentBlocksDeleteEvenWithoutInventory(){db.update("INSERT INTO wms_tickets(sn,ticket_type,wms_simulation_code,company_code) VALUES('TEST-TICKET',1,'V-A','QM')");assertThrows(IllegalArgumentException.class,()->workspace.delete("QM","simulationStore",Collections.singletonList(virtual)));}
    @Test void unreferencedMastersCanBeDeletedInDependencyOrder(){workspace.delete("QM","simulationStore",Collections.singletonList(virtual));workspace.delete("QM","ownerWarehouse",Collections.singletonList(relation));workspace.delete("QM","owner",Collections.singletonList(owner));workspace.delete("QM","realStore",Collections.singletonList(warehouse));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM owner_info",Integer.class));}
    @Test void batchDeleteIsAtomicWhenOneRowHasChildren(){long empty=workspace.save("QM","owner",owner("EMPTY"));assertThrows(IllegalArgumentException.class,()->workspace.delete("QM","owner",Arrays.asList(empty,owner)));assertNotNull(detail("owner",empty));}
    @Test void filtersActuallyApplyAndPaginationHasStableTotals(){assertEquals(0,workspace.page("QM","realStore",data("status",1),1,10).getTotal());assertEquals(0,workspace.page("QM","realStore",data("director","不存在"),1,10).getTotal());assertEquals(0,workspace.page("QM","simulationStore",data("inboundMode",1),1,10).getTotal());assertEquals(0,workspace.page("QM","owner",data("beginTime","2099-01-01"),1,10).getTotal());assertEquals(1,workspace.page("QM","simulationStore",data("ownerName","青木","realStoreId",warehouse),1,10).getTotal());workspace.save("QM","simulationStore",virtual("V-B",relation));assertEquals(2,workspace.page("QM","simulationStore",data(),2,1).getTotal());assertEquals(1,workspace.page("QM","simulationStore",data(),2,1).getRows().size());}
    @Test void executionModeMustBeExplicitOnVirtual(){Map<String,Object> v=virtual("V-C",relation);v.remove("inboundMode");assertThrows(IllegalArgumentException.class,()->workspace.save("QM","simulationStore",v));}
    @Test void realInboundRequiresConnectionAndExternalMappings(){Map<String,Object> v=detail("simulationStore",virtual);v.put("inboundMode",1);assertThrows(IllegalArgumentException.class,()->workspace.save("QM","simulationStore",v));}
    @Test void rejectsMissingRelationshipMalformedStatusAndInvalidDates(){Map<String,Object> v=virtual("V-B",0);assertThrows(IllegalArgumentException.class,()->workspace.save("QM","simulationStore",v));Map<String,Object> o=owner("OWNER-B");o.put("isEnable",0);assertThrows(IllegalArgumentException.class,()->workspace.save("QM","owner",o));assertThrows(IllegalArgumentException.class,()->workspace.page("QM","owner",data("beginTime","invalid"),1,10));}
    @Test void exportConversionHandlesMySqlDatesAndResolvedNames(){WarehouseExportRow row=workspace.convert(detail("simulationStore",virtual),WarehouseExportRow.class);assertEquals("OWNER-A",row.getOwnerCode());assertEquals("WH-A",row.getRealStoreCode());assertNotNull(row.getModifyTime());}
    @Test void concurrentDuplicateCreationHasOneWinner() throws Exception {ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch ready=new CountDownLatch(2),start=new CountDownLatch(1);List<Future<Boolean>> tasks=new ArrayList<>();for(int i=0;i<2;i++)tasks.add(pool.submit(()->{ready.countDown();start.await();try{workspace.save("QM","owner",owner("CONCURRENT"));return true;}catch(IllegalArgumentException e){return false;}}));try{ready.await(5,TimeUnit.SECONDS);start.countDown();int success=0;for(Future<Boolean> f:tasks)if(f.get(10,TimeUnit.SECONDS))success++;assertEquals(1,success);}finally{pool.shutdownNow();}}
    @Test void everyPublicMasterEndpointHasPermissionGuard(){for(Class<?> type:Arrays.asList(OwnerInfoController.class,WmsRealStoreInfoController.class,WmsSimulationStoreInfoController.class,OwnerWarehouseController.class))for(java.lang.reflect.Method method:type.getDeclaredMethods())if(java.lang.reflect.Modifier.isPublic(method.getModifiers()))assertNotNull(method.getAnnotation(RequiresPermissions.class),type.getSimpleName()+"."+method.getName());}

    @Test void databaseRejectsCompanyCaseVariantsOfSameMasterCode(){
        assertThrows(org.springframework.dao.DuplicateKeyException.class,()->db.update("INSERT INTO owner_info(owner_code,owner_name,is_sync,is_enable,company_code) VALUES('OWNER-A','duplicate',1,2,'qm')"));
    }
    @Test void disabledAssociationStillPreventsOwnerAndWarehouseDeletion(){disable("ownerWarehouse",relation);assertThrows(IllegalArgumentException.class,()->workspace.delete("QM","owner",Collections.singletonList(owner)));assertThrows(IllegalArgumentException.class,()->workspace.delete("QM","realStore",Collections.singletonList(warehouse)));}
    @Test void completedAndCancelledDocumentsStillProtectWarehouse(){
        workspace.delete("QM","simulationStore",Collections.singletonList(virtual));workspace.delete("QM","ownerWarehouse",Collections.singletonList(relation));
        for(int state:Arrays.asList(2,5)){db.update("INSERT INTO wms_tickets(sn,ticket_type,real_store_code,status_ticket,company_code) VALUES(?,1,'WH-A',?,'QM')","HISTORY-"+state,state);assertThrows(IllegalArgumentException.class,()->workspace.delete("QM","realStore",Collections.singletonList(warehouse)));db.update("DELETE FROM wms_tickets");}
    }
    @Test void deletionVersusNewAssociationCannotLeaveOrphan() throws Exception {
        for(String kind:Arrays.asList("owner","realStore")){
            long empty=workspace.save("QM",kind,kind.equals("owner")?owner("EMPTY-O"):warehouse("EMPTY-W"));long o=kind.equals("owner")?empty:owner,w=kind.equals("realStore")?empty:warehouse;
            race(()->workspace.save("QM","ownerWarehouse",relation(o,w)),()->workspace.delete("QM",kind,Collections.singletonList(empty)));
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM owner_warehouse r LEFT JOIN owner_info o ON o.id=r.owner_id LEFT JOIN wms_real_store_info w ON w.id=r.real_store_id WHERE o.id IS NULL OR w.id IS NULL",Integer.class));
        }
    }
    @Test void deletionVersusNewVirtualCannotLeaveOrphan() throws Exception {
        long other=workspace.save("QM","realStore",warehouse("EMPTY-W")),link=workspace.save("QM","ownerWarehouse",relation(owner,other));
        race(()->workspace.save("QM","simulationStore",virtual("NEW-V",link)),()->workspace.delete("QM","ownerWarehouse",Collections.singletonList(link)));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM wms_simulation_store_info v LEFT JOIN owner_warehouse r ON r.id=v.owner_warehouse_id WHERE r.id IS NULL",Integer.class));
    }
    void race(Runnable create,Runnable delete) throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try{List<Future<Boolean>> futures=new ArrayList<>();for(Runnable action:Arrays.asList(create,delete))futures.add(pool.submit(()->{start.await();try{action.run();return true;}catch(IllegalArgumentException e){return false;}}));start.countDown();assertNotEquals(futures.get(0).get(10,TimeUnit.SECONDS),futures.get(1).get(10,TimeUnit.SECONDS));}finally{pool.shutdownNow();}
    }
}
