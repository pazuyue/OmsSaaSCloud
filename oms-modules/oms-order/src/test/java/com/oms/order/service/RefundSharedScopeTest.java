package com.oms.order.service;
import org.junit.jupiter.api.*;
import java.util.*;
import com.ruoyi.common.core.domain.R;
import static com.oms.order.service.RefundValues.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class RefundSharedScopeTest {
    RefundGoodsServiceTest f;RefundSharedScope scope;long plan;
    @BeforeAll static void start(){RefundGoodsServiceTest.start();}
    @BeforeEach void setup(){f=new RefundGoodsServiceTest();f.setup();plan=f.create(1);scope=new RefundSharedScope();scope.refunds=f.f.refunds;scope.returns=f.goods.returns;when(scope.returns.parcels(anyString(),anyLong(),anyString())).thenReturn(R.ok(Collections.singletonList(m("id",70,"members",Collections.singletonList(m("active",1,"plan_id",plan,"refund_id",f.refund,"quantity",1,"accepted_quantity",0))))));}
    @AfterEach void cleanup(){f.cleanup();}
    @Test void approvedSourceCanBeFrozenForSharedDocument(){assertNotNull(scope.validate("VERIFY",f.refund,plan));}
    @Test void revokedApprovalBlocksEntireSharedDocument(){f.f.db.update("UPDATE oms_refund SET review_state='PENDING' WHERE id=?",f.refund);assertThrows(IllegalArgumentException.class,()->scope.validate("VERIFY",f.refund,plan));}
    @Test void stoppedPlanRequiresExplicitRebuild(){f.f.db.update("UPDATE oms_refund_return_plan SET state='STOPPED' WHERE id=?",plan);assertThrows(IllegalArgumentException.class,()->scope.validate("VERIFY",f.refund,plan));assertNotNull(scope.validate("VERIFY",f.refund,plan,true));}
    @Test void waivedSourceCannotBeReintroducedBySharedRebuild(){f.f.db.update("UPDATE oms_refund_return_plan SET waived_quantity=1 WHERE id=?",plan);assertThrows(IllegalArgumentException.class,()->scope.validate("VERIFY",f.refund,plan,true));}
    @Test void changedShipmentSourceCannotBeReintroduced(){f.f.db.update("UPDATE oms_refund_return_plan SET shipment_line_id=99999999 WHERE id=?",plan);assertThrows(IllegalArgumentException.class,()->scope.validate("VERIFY",f.refund,plan));}
}
