package com.oms.order.service;
import org.junit.jupiter.api.*;
import java.util.*;
import com.ruoyi.common.core.domain.R;
import static com.oms.order.service.OrderValues.*;
import static com.oms.order.service.RefundValues.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class RefundParcelPolicyTest {
    RefundGoodsServiceTest f;RefundParcelPolicy policy;
    @BeforeAll static void start(){RefundGoodsServiceTest.start();}
    @BeforeEach void setup(){f=new RefundGoodsServiceTest();f.setup();f.f.db.update("DELETE FROM oms_refund_parcel_config");policy=new RefundParcelPolicy();policy.refunds=f.f.refunds;policy.catalog=mock(OrderCatalog.class);policy.returns=f.goods.returns;when(policy.returns.parcelCapabilities(eq("VERIFY"),anyString())).thenReturn(R.ok(Collections.singletonList(m("store_code","V","usable",1,"shared_return_waybill",1,"shared_return_append",1))));}
    @AfterEach void cleanup(){f.cleanup();}
    Map<String,Object> config(){Map<String,Object> p=RefundParcelPolicy.defaults();p.putAll(m("auto_enabled",1,"allow_append",0,"auto_append",0,"store_codes",Arrays.asList("V")));return p;}
    @Test void defaultsAreManualAndConfigurationHasOptimisticVersion(){assertEquals(0,number(policy.get("VERIFY",1),"auto_enabled"));Map<String,Object> p=config();policy.save("VERIFY",1,p,"test");assertEquals(1,number(policy.get("VERIFY",1),"revision"));assertThrows(IllegalArgumentException.class,()->policy.save("VERIFY",1,p,"test"));assertEquals(0,number(policy.get("OTHER",1),"auto_enabled"));}
    @Test void shopCannotOverrideMissingWarehouseAppendCapability(){when(policy.returns.parcelCapabilities(eq("VERIFY"),anyString())).thenReturn(R.ok(Collections.singletonList(m("store_code","V","usable",1,"shared_return_waybill",1,"shared_return_append",0))));Map<String,Object> p=config();p.put("allow_append",1);assertThrows(IllegalArgumentException.class,()->policy.save("VERIFY",1,p,"test"));assertEquals(0,f.f.db.queryForObject("SELECT COUNT(*) FROM oms_refund_parcel_config",Integer.class));}
    @Test void automaticModeRequiresExplicitStoresSourcesAndDependencySwitches(){Map<String,Object> p=config();p.put("store_codes",Collections.emptyList());assertThrows(IllegalArgumentException.class,()->policy.save("VERIFY",1,p,"test"));final Map<String,Object> invalid=config();invalid.put("auto_append",1);assertThrows(IllegalArgumentException.class,()->policy.save("VERIFY",1,invalid,"test"));}
    @Test void unknownCarrierDoesNotGuessAPlatformName(){assertEquals("SF",RefundParcelAutomation.carrier("顺丰速运"));assertThrows(IllegalArgumentException.class,()->RefundParcelAutomation.carrier("未知物流"));}
    @Test void listMergesDefaultsInOneBatchWithoutPerShopWarehouseCapabilityCalls(){
        when(policy.catalog.channels("VERIFY")).thenReturn(Arrays.asList(m("channel_id",1,"channel_name","Default","channel_type","TM","enabled",1),m("channel_id",2,"channel_name","Configured","channel_type","TM","enabled",1),m("channel_id",3,"channel_name","Stopped","channel_type","TM","enabled",0),m("channel_id",4,"channel_name","Other platform","channel_type","JD","enabled",1)));
        when(policy.catalog.warehouses("VERIFY")).thenReturn(Collections.singletonList(m("wms_simulation_code","V","wms_simulation_name","Return warehouse")));
        policy.save("VERIFY",2,config(),"test");when(policy.returns.parcelCapabilities(eq("OTHER"),anyString())).thenReturn(R.ok(Collections.singletonList(m("store_code","V","usable",1))));policy.save("OTHER",1,config(),"foreign");clearInvocations(policy.catalog,policy.returns);
        Map<String,Object> result=policy.list("VERIFY",Collections.singletonMap("pageSize","2"));List<Map<String,Object>> rows=list(result.get("rows"));
        assertEquals(3,number(result,"total"));assertEquals(2,rows.size());assertEquals(1,number(obj(result.get("summary")),"configured"));assertEquals(1,number(obj(result.get("summary")),"auto_enabled"));
        assertEquals(false,rows.get(0).get("configured"));assertEquals(1,number(rows.get(0),"enabled"));assertEquals(0,number(rows.get(0),"auto_enabled"));assertEquals(120,number(rows.get(0),"collect_seconds"));
        assertEquals(true,rows.get(1).get("configured"));assertEquals("Return warehouse",text(list(rows.get(1).get("warehouses")).get(0),"name"));
        verify(policy.catalog,times(1)).channels("VERIFY");verify(policy.catalog,times(1)).warehouses("VERIFY");verifyNoInteractions(policy.returns);
        clearInvocations(policy.catalog);Map<String,String> q=new HashMap<>();q.put("pageNum","2");q.put("pageSize","2");Map<String,Object> last=policy.list("VERIFY",q);
        assertEquals(3,number(list(last.get("rows")).get(0),"channel_id"));assertEquals(0,number(list(last.get("rows")).get(0),"shop_enabled"));verify(policy.catalog,never()).warehouses(anyString());
        assertEquals(2,number(policy.list("VERIFY",Collections.singletonMap("configState","DEFAULT")),"total"));assertEquals(1,number(policy.list("VERIFY",Collections.singletonMap("autoEnabled","1")),"total"));
        assertEquals(0,number(policy.list("VERIFY",Collections.singletonMap("channelId","999")),"total"));
        assertEquals(2,f.f.db.queryForObject("SELECT COUNT(*) FROM oms_refund_parcel_config",Integer.class),"listing defaults must not create config records");
    }
    @Test void warehouseNameFailurePreservesConfiguredCodesAndDoesNotHideRules(){
        when(policy.catalog.channels("VERIFY")).thenReturn(Collections.singletonList(m("channel_id",1,"channel_name","Shop","channel_type","TM","enabled",1)));policy.save("VERIFY",1,config(),"test");
        when(policy.catalog.warehouses("VERIFY")).thenThrow(new IllegalStateException("unavailable"));Map<String,Object> result=policy.list("VERIFY",Collections.emptyMap());
        assertEquals(true,result.get("warehouse_names_unavailable"));assertEquals("V",text(list(list(result.get("rows")).get(0).get("warehouses")).get(0),"name"));
        assertThrows(IllegalArgumentException.class,()->policy.list("VERIFY",Collections.singletonMap("pageSize","10000")));assertThrows(IllegalArgumentException.class,()->policy.list("VERIFY",Collections.singletonMap("configState","UNKNOWN")));
    }
    @Test void stoppedShopRulesAreReadableButNotEditable(){
        when(policy.catalog.channels("VERIFY")).thenReturn(Collections.singletonList(m("channel_id",1,"channel_type","TM","enabled",0)));
        when(policy.catalog.validateShop("VERIFY",1)).thenThrow(new IllegalArgumentException("shop stopped"));
        assertEquals(false,policy.detail("VERIFY",1).get("editable"));assertThrows(IllegalArgumentException.class,()->policy.save("VERIFY",1,config(),"test"));
        assertThrows(IllegalArgumentException.class,()->policy.detail("VERIFY",999));
    }
    @Test void partialAllocationRequiresRuleModeAndPersistsSeparatelyFromMergeSwitch(){Map<String,Object> p=config();p.put("allow_partial_allocation",1);assertThrows(IllegalArgumentException.class,()->policy.save("VERIFY",1,p,"test"));p.put("allocation_mode","RULE");p.put("auto_enabled",0);p.put("enabled",0);policy.save("VERIFY",1,p,"test");Map<String,Object> saved=policy.get("VERIFY",1);assertEquals("RULE",saved.get("allocation_mode"));assertEquals(1,number(saved,"allow_partial_allocation"));assertEquals(0,number(saved,"auto_enabled"));assertEquals("REFUND_CREATED",saved.get("allocation_order"));}
}
