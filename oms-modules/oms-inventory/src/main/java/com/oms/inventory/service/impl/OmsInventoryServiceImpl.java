package com.oms.inventory.service.impl;


import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.oms.inventory.mapper.OmsInventoryMapper;
import com.oms.inventory.model.entity.OmsInventory;
import com.oms.inventory.service.IOmsInventoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class OmsInventoryServiceImpl extends ServiceImpl<OmsInventoryMapper, OmsInventory> implements IOmsInventoryService {
}
