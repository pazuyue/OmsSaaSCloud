package com.oms.order.service;
import org.springframework.context.annotation.Bean;
/** Kept out of component scanning; bounds only this warehouse bridge. */
public class OrderShipmentWmsFeignConfig {
    @Bean public feign.Request.Options shipmentWmsOptions(){return new feign.Request.Options(5000,45000);}
}
