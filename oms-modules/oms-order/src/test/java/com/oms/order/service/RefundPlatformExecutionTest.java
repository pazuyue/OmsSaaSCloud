package com.oms.order.service;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.oms.order.service.RefundValues.*;
import static org.junit.jupiter.api.Assertions.*;
class RefundPlatformExecutionTest {
 @Test void batchEnvelopeSuccessDoesNotProveCurrentRefundWasPaid(){assertFalse(RefundPlatformExecution.accepted("PAY",m("succ",true,"results",m("refund_mapping_result",Collections.singletonList(m("refund_id","OTHER","succ",true)))),"9007199254740993"));}
 @Test void onlyMatchingPerRefundSuccessConfirmsPaymentOperation(){assertTrue(RefundPlatformExecution.accepted("PAY",m("succ",true,"results",m("refund_mapping_result",Arrays.asList(m("refund_id","other","succ",false),m("refund_id","9007199254740993","succ",true)))),"9007199254740993"));}
 @Test void reviewAndAgreeReturnNeverUseMoneyResultFields(){assertTrue(RefundPlatformExecution.accepted("REVIEW",m("is_success",true),"1"));assertTrue(RefundPlatformExecution.accepted("AGREE_RETURN",m("is_success",true),"1"));assertFalse(RefundPlatformExecution.accepted("PAY",m("is_success",true),"1"));}
 @Test void emptyInterceptResponseIsOnlyRequestAcceptance(){assertTrue(RefundPlatformExecution.accepted("INTERCEPT",m(),"1"));assertFalse(RefundPlatformExecution.accepted("INTERCEPT_FEEDBACK",m(),"1"));assertTrue(RefundPlatformExecution.accepted("INTERCEPT_FEEDBACK",m("receive_status",true),"1"));}
 @Test void cancellationFeedbackChecksBusinessResult(){assertFalse(RefundPlatformExecution.accepted("CANCEL_FEEDBACK",m("result",m("result_data",m("success",false))),"1"));assertTrue(RefundPlatformExecution.accepted("CANCEL_FEEDBACK",m("result",m("result_data",m("success",true))),"1"));}
 @Test void returnRefusalUsesOfficialBooleanResultRatherThanRefundResponse(){assertTrue(RefundPlatformExecution.accepted("REFUSE_RETURN",m("result",true),"1"));assertFalse(RefundPlatformExecution.accepted("REFUSE_RETURN",m("result",false),"1"));assertTrue(RefundPlatformExecution.rejected("REFUSE_RETURN",m("result",false),"1"));}
 @Test void explicitBusinessFailureOverridesAnAcknowledgementField(){assertFalse(RefundPlatformExecution.accepted("PAY_CODE",m("succ",false,"msg_code","NO_PERMISSION"),"1"));assertFalse(RefundPlatformExecution.accepted("INTERCEPT",m("is_success",false),"1"));assertFalse(RefundPlatformExecution.accepted("NEGOTIATE_SUBMIT",m("success",false),"1"));}
 @Test void paymentUsesTheCurrentRefundMappingRatherThanBatchSummary(){assertTrue(RefundPlatformExecution.accepted("PAY",m("succ",false,"results",m("refund_mapping_result",Collections.singletonList(m("refund_id","1","succ",true)))),"1"));assertFalse(RefundPlatformExecution.rejected("PAY",m("succ",false,"results",m("refund_mapping_result",Collections.singletonList(m("refund_id","2","succ",false)))),"1"));}
}
