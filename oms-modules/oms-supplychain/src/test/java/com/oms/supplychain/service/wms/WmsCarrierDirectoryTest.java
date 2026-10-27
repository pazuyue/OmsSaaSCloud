package com.oms.supplychain.service.wms;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class WmsCarrierDirectoryTest {
    @Test void officialResponseKeepsExactCodesAndUnspecifiedStatusRequiresHumanConfirmation(){Map<String,Object> result=WmsCarrierDirectory.parse("<response><flag>success</flag><expressInfos><express_info><expressCode>001/sF.a</expressCode><expressName>测试快递</expressName><status>C123</status></express_info></expressInfos></response>");Map<?,?> row=(Map<?,?>)((List<?>)result.get("rows")).get(0);assertEquals("001/sF.a",row.get("external_code"));assertEquals(0,row.get("enabled"));assertTrue(row.get("disabled_reason").toString().contains("C123"));}
    @Test void queryFailureOrIncompleteResponseMustNotBeInterpretedAsAnEmptySuccessfulDirectory(){assertThrows(IllegalArgumentException.class,()->WmsCarrierDirectory.parse("<response><flag>failure</flag></response>"));assertThrows(IllegalArgumentException.class,()->WmsCarrierDirectory.parse("<response><flag>success</flag></response>"));assertThrows(IllegalArgumentException.class,()->WmsCarrierDirectory.parse("<!DOCTYPE r [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><response><flag>success</flag><expressInfos>&x;</expressInfos></response>"));}
}
