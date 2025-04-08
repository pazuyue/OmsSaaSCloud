package com.oms.inventory.service.impl.rule;

import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.github.pagehelper.PageHelper;
import com.oms.inventory.annotation.StrategyType;
import com.oms.inventory.mapper.OmsChannelInventoryMapper;
import com.oms.inventory.model.dto.rule.AllocationResult;
import com.oms.inventory.model.entity.OmsChannelInventory;
import com.oms.inventory.model.entity.WmsInventory;
import com.oms.inventory.model.entity.rule.RuleStockChannelInfo;
import com.oms.inventory.model.entity.rule.RuleStockInfo;
import com.oms.inventory.model.entity.rule.RuleStockStoreCodeInfo;
import com.oms.inventory.service.IWmsInventoryService;
import com.oms.inventory.service.rule.AllocationStrategyService;
import com.oms.inventory.service.rule.IRuleStockChannelInfoService;
import com.oms.inventory.service.rule.IRuleStockStoreCodeInfoService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@StrategyType("OVER_ALLOCATE")
@Service
public class OverAllocateStrategyServiceImpl implements AllocationStrategyService {

    @Resource
    private IRuleStockStoreCodeInfoService ruleStockStoreCodeInfoService;
    @Resource
    private IWmsInventoryService wmsInventoryService;
    @Resource
    private IRuleStockChannelInfoService ruleStockChannelInfoService;
    @Resource
    private OmsChannelInventoryMapper omsChannelInventoryMapper;

    @Override
    public AllocationResult allocate(RuleStockInfo rule) {
        log.info("OverAllocateStrategyImpl.allocate");
        log.info("rule:{}", rule);

        // 获取 store_code 集合
        List<RuleStockStoreCodeInfo> storeCodeInfos = ruleStockStoreCodeInfoService.list(
                new LambdaQueryWrapper<RuleStockStoreCodeInfo>().eq(RuleStockStoreCodeInfo::getRuleId, rule.getId())
        );
        List<String> storeCodes = Arrays.asList(storeCodeInfos.stream().map(RuleStockStoreCodeInfo::getStoreCode).toArray(String[]::new));
        log.debug("storeCodes:{}", storeCodes);
        if (rule.getRuleRange() == 1) {
            // 全部商品
            int pageSize = 1000;
            int currentPage = 1;
            while (true){
                QueryWrapper<WmsInventory> queryWrapper = new QueryWrapper<>();
                queryWrapper.select("sku_sn");
                PageHelper.startPage(currentPage, pageSize);
                List<WmsInventory> list = wmsInventoryService.list(queryWrapper);
                // 使用流处理 SKU 列表并直接调用 paginateAndProcessInventory
                log.debug("分页查询结果：{}", list);
                List<String> skuList = list.stream().map(WmsInventory::getSkuSn).collect(Collectors.toList());
                if (skuList.isEmpty()) {
                    break; // 如果没有更多数据，则退出循环
                }
                skuList.forEach(sku -> processInventoryByStoreAndSku(rule.getId(),storeCodes, sku));
                currentPage++;
            }
        } else {
            // 部分商品
            List<String> skuList = getSkuList(rule.getId());
            log.debug("skuList:{}", skuList);
            skuList.forEach(sku -> processInventoryByStoreAndSku(rule.getId(),storeCodes, sku));
        }

        return null;
    }

    public List<String> getSkuList(Long ruleId) {
      return new ArrayList<>();
    }
    @Transactional
    public Boolean processInventoryByStoreAndSku(Long ruleId,List<String> storeCodes, String sku) {
        // 参数校验
        if (storeCodes == null || storeCodes.isEmpty() || sku == null || sku.isEmpty()) {
            log.warn("输入参数无效：storeCodes={}，sku={}", storeCodes, sku);
            throw new RuntimeException("输入参数无效");
        }

        try {
            // 查询库存信息
            Map<String, Object> wmsInventory = wmsInventoryService.selectSkuTotalAvailable(storeCodes, sku);

            // 检查查询结果是否为空
            if (wmsInventory == null || wmsInventory.isEmpty()) {
                log.debug("未查询到库存信息，storeCodes={}，sku={}", storeCodes, sku);
                throw new RuntimeException("未查询到库存信息");
            }

            // 日志记录查询结果
            log.debug("查询到库存信息，storeCodes={}，sku={}，结果：{}", storeCodes, sku, wmsInventory);

            // 安全地获取 sku_sn 和 total_available
            String skuSn = (String) wmsInventory.getOrDefault("sku_sn", null);
            BigDecimal totalAvailable = (BigDecimal) wmsInventory.getOrDefault("total_available", BigDecimal.ZERO);

            // 验证获取的值是否有效
            if (skuSn == null || totalAvailable == null) {
                log.warn("库存信息缺失关键字段，storeCodes={}，sku={}，结果：{}", storeCodes, sku, wmsInventory);
                throw new RuntimeException("库存信息缺失关键字段");
            }

            // 处理库存分配
            processAllocateInventory(ruleId,skuSn, totalAvailable);

            return true;
        } catch (Exception e) {
            // 捕获异常并记录错误日志
            log.error("处理库存时发生异常，storeCodes={}，sku={}，异常信息：{}", storeCodes, sku, e.getMessage(), e);
            return false;
        }
    }


    private Boolean processAllocateInventory(Long ruleId,String skuSn, BigDecimal totalAvailable) {
        // 获取库存信息
        try {
            log.debug("skuSn={} totalAvailable={}", skuSn, totalAvailable);
            List<RuleStockChannelInfo> ruleStockChannelInfoList = ruleStockChannelInfoService.listByMap(new HashMap<String, Object>() {{
                put("rule_id", ruleId);
            }});
            log.debug("ruleStockChannelInfoList:{}", ruleStockChannelInfoList);
            if (ruleStockChannelInfoList.isEmpty()) {
                log.warn("未查询到渠道信息，ruleId={}", ruleId);
                throw new RuntimeException("未查询到渠道信息");
            }
            for (RuleStockChannelInfo ruleStockChannelInfo : ruleStockChannelInfoList) {
                if (totalAvailable.compareTo(BigDecimal.ZERO) < 0) {
                    log.warn("库存不足，skuSn={} totalAvailable={}", skuSn, totalAvailable);
                    continue;
                }
                if (ruleStockChannelInfo.getChannelId() == null) {
                    log.warn("渠道信息缺失关键字段，ruleId={}", ruleId);
                    continue;
                }

                BigDecimal percentage = ruleStockChannelInfo.getPercentage();
                BigDecimal availableStock;
                // 计算 availableStock
                if (ruleStockChannelInfo.getRuleType() == 1){ //百分百
                    availableStock = totalAvailable.multiply(percentage).divide(new BigDecimal(100));
                    availableStock = applyDecimalHandling(availableStock, ruleStockChannelInfo.getDecimalHandleType());
                }else { //整型数量

                    // 如果 totalAvailable > percentage 则 availableStock = percentage
                    if (totalAvailable.compareTo(percentage) > 0) {
                        availableStock = percentage;
                    }else {
                        availableStock = totalAvailable;
                    }
                }
                log.debug("availableStock:{}", availableStock);
                QueryWrapper wrapper = new QueryWrapper();
                wrapper.eq("channel_id", ruleStockChannelInfo.getChannelId());
                OmsChannelInventory channelInventory = omsChannelInventoryMapper.selectOne(wrapper);
                if (ObjectUtil.isEmpty(channelInventory)){
                    channelInventory = new OmsChannelInventory();
                    channelInventory.setChannelId(ruleStockChannelInfo.getChannelId());
                    channelInventory.setSkuSn(skuSn);
                    channelInventory.setAvailableStock(availableStock);
                    channelInventory.setCompanyCode(ruleStockChannelInfo.getCompanyCode());
                    omsChannelInventoryMapper.insert(channelInventory);
                    log.debug("channelInventory is null,insert channelInventory:{}", channelInventory);
                }

            }
        } catch (Exception e) {
            log.error("processAllocateInventory error:{}", e);
            throw new RuntimeException(e);
        }
        return true;
    }

    private BigDecimal applyDecimalHandling(BigDecimal availableStock, Integer decimalHandleType) {
        switch (decimalHandleType) {
            case 1:
                return availableStock.setScale(0, RoundingMode.DOWN); // 向下取整
            case 2:
                return availableStock.setScale(0, RoundingMode.UP); // 向上取整
            default:
                return availableStock.setScale(0, RoundingMode.HALF_UP); // 四舍五入
        }
    }

}
