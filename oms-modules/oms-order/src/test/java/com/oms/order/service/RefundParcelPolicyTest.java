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
}
