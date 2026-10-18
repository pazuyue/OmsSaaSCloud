package com.oms.supplychain.service.wms;
import org.springframework.stereotype.Service;
@Service public class ExchangeReturnService extends WarehouseReturnService { public ExchangeReturnService(){super(Kind.EXCHANGE);} }
