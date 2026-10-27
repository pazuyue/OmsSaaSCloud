package com.oms.supplychain;
import com.oms.supplychain.service.wms.WmsCarrierBridge;
import java.util.*;
import static org.mockito.Mockito.*;
/** Stock/receipt suites isolate carrier configuration; dedicated carrier suites exercise its real implementation. */
public final class CarrierFixture {
    public static WmsCarrierBridge bridge(){WmsCarrierBridge b=mock(WmsCarrierBridge.class);Map<String,Object> result=new LinkedHashMap<>();result.put("state","RESOLVED");result.put("available",true);result.put("standard_id",1);result.put("external_code","SF");when(b.choose(anyString(),anyString(),anyLong(),anyString(),anyString())).thenReturn(result);when(b.freeze(anyString(),anyString(),anyMap(),anyString())).thenReturn(result);when(b.inbound(anyString(),anyMap(),anyMap())).thenReturn(result);return b;}
}
