package com.oms.supplychain.service.wms;
import org.springframework.context.annotation.Bean;
public class OrderReceiptWakeupConfig {
    @Bean public feign.Request.Options receiptWakeupOptions(){return new feign.Request.Options(1000,2000);}
    @Bean public feign.Retryer receiptWakeupRetryer(){return feign.Retryer.NEVER_RETRY;}
}
