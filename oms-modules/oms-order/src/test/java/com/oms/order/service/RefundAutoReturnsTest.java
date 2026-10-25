package com.oms.order.service;

import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import static com.oms.order.service.OrderValues.*;
import static com.oms.order.service.RefundValues.*;
import static org.mockito.Mockito.*;

/** A purchase receipt alone must not close goods while the original promotion still requires a gift recovery. */
class RefundAutoReturnsTest {
    RefundServiceTest f=new RefundServiceTest();RefundAutoReturns automation;GiftService gifts;long refund;
    @BeforeAll static void start(){RefundServiceTest.start();}
    @BeforeEach void setup(){f.reset();f.shipped();refund=f.convert(f.ingest(f.source(1,"25.00","WAIT_SELLER_AGREE")));f.workspace.review("VERIFY",refund,f.review(refund,"CANCEL","RETURN"),"test");f.db.update("UPDATE oms_refund SET goods_state='WAIT_QC' WHERE id=?",refund);
        gifts=mock(GiftService.class);ObjectProvider<GiftService> provider=mock(ObjectProvider.class);when(provider.getIfAvailable()).thenReturn(gifts);ReflectionTestUtils.setField(f.repo,"giftProvider",provider);
        automation=new RefundAutoReturns();automation.refunds=f.refunds;automation.goods=mock(RefundGoodsService.class);automation.policies=mock(RefundParcelPolicy.class);automation.parcels=mock(RefundParcelAutomation.class);when(automation.policies.get("VERIFY",1)).thenReturn(m("auto_create",0));}
    @AfterEach void clear(){f.clear();}
    void outstanding(Map<String,Object> line){when(gifts.report("VERIFY",f.order)).thenReturn(m("lines",Collections.singletonList(line)));automation.advance("VERIFY",refund,m("auto_finish_return",1));verify(automation.goods,never()).finish(anyString(),anyLong(),anyMap(),anyString());}
    @Test void arrangedButNotActuallyReturnedGiftBlocksAutomaticGoodsCompletion(){outstanding(m("return_required",1,"unarranged_quantity",0));}
    @Test void unshippedGiftStillRequiringCancellationBlocksAutomaticGoodsCompletion(){outstanding(m("cancel_required",1,"return_required",0,"unarranged_quantity",0));}
}
