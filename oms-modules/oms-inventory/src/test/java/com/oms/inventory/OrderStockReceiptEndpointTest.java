package com.oms.inventory;
import com.oms.inventory.controller.wms.api.OrderStockController;
import com.oms.inventory.service.impl.OrderFulfillmentAdjustmentService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
/** Covers the complete controller route used by the order Feign client, without issuing a stock command. */
class OrderStockReceiptEndpointTest {
 @Test void originalAdjustmentCanBeReadAtTheExactClientPath()throws Exception{
  OrderStockController controller=new OrderStockController();OrderFulfillmentAdjustmentService adjustments=mock(OrderFulfillmentAdjustmentService.class);ReflectionTestUtils.setField(controller,"adjustments",adjustments);Map<String,Object> result=new HashMap<>();result.put("state","MISSING");result.put("request_id","CF_RECEIPT_CONTRACT_1");when(adjustments.receipt("VERIFY","CF_RECEIPT_CONTRACT_1")).thenReturn(result);
  MockMvcBuilders.standaloneSetup(controller).build().perform(get("/orderStock/internal/fulfillment-adjustment/CF_RECEIPT_CONTRACT_1").param("company_code","VERIFY")).andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("MISSING"));
  verify(adjustments).receipt("VERIFY","CF_RECEIPT_CONTRACT_1");verifyNoMoreInteractions(adjustments);
 }
}
