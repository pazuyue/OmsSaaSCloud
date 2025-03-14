package com.oms.inventory.service.impl.rule;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.oms.inventory.mapper.rule.RuleStockStoreCodeInfoMapper;
import com.oms.inventory.model.entity.rule.RuleStockStoreCodeInfo;
import com.oms.inventory.service.rule.IRuleStockStoreCodeInfoService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class RuleStockStoreCodeInfoServiceImpl extends ServiceImpl<RuleStockStoreCodeInfoMapper, RuleStockStoreCodeInfo> implements IRuleStockStoreCodeInfoService {
}
