package com.oms.supplychain.service.requestWms.impl;

import com.oms.supplychain.service.requestWms.IRequestWmsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class RequestWoftQimenService implements IRequestWmsService {
    @Override
    public boolean entryOrderCreate(String sn) {
        log.info("entryOrderCreate {}", sn);
        return false;
    }
}
