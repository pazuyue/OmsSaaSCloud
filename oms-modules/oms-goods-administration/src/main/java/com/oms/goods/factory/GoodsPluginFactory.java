package com.oms.goods.factory;

import com.oms.goods.service.plugin.GoodsPluginService;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class GoodsPluginFactory {
    @Resource
    private Map<String, GoodsPluginService> serviceMap = new ConcurrentHashMap<>();

    public GoodsPluginService getBean(String type) {
        return serviceMap.get(type);
    }
}
