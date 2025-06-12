package com.oms.inventory.service.impl.rule;

import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.oms.inventory.factory.StrategyFactory;
import com.oms.inventory.model.dto.AllocationRuleDto;
import com.oms.inventory.model.dto.rule.RuleDetailsInfoDto;
import com.oms.inventory.model.entity.WmsInventory;
import com.oms.inventory.model.entity.rule.RuleStockChannelInfo;
import com.oms.inventory.model.entity.rule.RuleStockInfo;
import com.oms.inventory.model.entity.rule.RuleStockStoreCodeInfo;
import com.oms.inventory.model.enums.AllocationStrategy;
import com.oms.inventory.model.enums.RuleStatus;
import com.oms.inventory.service.impl.WmsInventoryServiceImpl;
import com.oms.inventory.service.rule.*;
import com.ruoyi.common.security.utils.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 库存分货规则处理服务实现类
 * 负责处理库存分货规则的设置、审核和执行等核心业务逻辑
 *
 * @author system
 * @since 2024
 */
@Slf4j
@Service
public class RuleStockInfoHandleServicelmpl implements IRuleStockInfoHandleService {

    /** SKU范围类型：全部商品 */
    private static final int ALL_SKU_LIST = 1;
    /** SKU范围类型：指定商品 */
    private static final int APPOINT_SKU_LIST = 2;

    /** 分货单类型：日常分货 */
    private static final int RULE_TYPE_DAILY = 1;
    /** 分货单类型：一次性分货 */
    private static final int RULE_TYPE_ONETIME = 2;
    /** 分货单类型：锁库单分货 */
    private static final int RULE_TYPE_LOCK = 3;
    @Resource
    private IRuleStockInfoService ruleStockInfoService;
    @Resource
    private IRuleStockStoreCodeInfoService ruleStockStoreCodeInfoService;
    @Resource
    private IRuleStockChannelInfoService ruleStockChannelInfoService;

    @Resource
    private WmsInventoryServiceImpl wmsInventoryService;

    @Resource
    private StrategyFactory strategyFactory;

    /**
     * 设置分货规则
     * 根据传入的分货规则DTO，创建渠道信息和仓库信息，并更新规则状态为待审核
     *
     * @param dto 分货规则DTO，包含规则ID、渠道信息列表、仓库代码列表等
     * @return Boolean 设置成功返回true，失败返回false
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean setRule(AllocationRuleDto dto) {
        try {
            // 参数校验
            if (!validateSetRuleParams(dto)) {
                return false;
            }

            // 获取并校验规则信息
            RuleStockInfo ruleInfo = getRuleInfoAndValidate(dto.getRuleId());
            if (ruleInfo == null) {
                return false;
            }

            // 构建渠道信息列表
            List<RuleStockChannelInfo> channelInfoList = buildChannelInfoList(dto);
            log.debug("构建渠道信息列表完成，数量: {}", channelInfoList.size());

            // 构建仓库信息列表
            List<RuleStockStoreCodeInfo> storeCodeInfoList = buildStoreCodeInfoList(dto);
            log.debug("构建仓库信息列表完成，数量: {}", storeCodeInfoList.size());

            // 批量保存数据
            return saveRuleData(ruleInfo, channelInfoList, storeCodeInfoList, dto.getPriorityType());

        } catch (Exception e) {
            log.error("设置分货规则时发生异常，规则ID: {}", dto != null ? dto.getRuleId() : "null", e);
            throw new RuntimeException("设置分货规则失败", e);
        }
    }

    /**
     * 校验设置规则的参数
     */
    private boolean validateSetRuleParams(AllocationRuleDto dto) {
        if (dto == null) {
            log.warn("分货规则DTO为空");
            return false;
        }
        if (dto.getInfoList() == null || dto.getInfoList().isEmpty()) {
            log.warn("渠道信息列表为空");
            return false;
        }
        if (dto.getWmsSimulationCodes() == null || dto.getWmsSimulationCodes().isEmpty()) {
            log.warn("仓库代码列表为空");
            return false;
        }
        return true;
    }

    /**
     * 获取并校验规则信息
     */
    private RuleStockInfo getRuleInfoAndValidate(Integer ruleId) {
        RuleStockInfo ruleInfo = ruleStockInfoService.getById(ruleId);
        if (ruleInfo == null) {
            log.warn("规则信息不存在，规则ID: {}", ruleId);
            return null;
        }
        if (ruleInfo.getStatus() != RuleStatus.NEW) {
            log.warn("规则状态不正确，当前状态: {}，规则ID: {}", ruleInfo.getStatus(), ruleId);
            return null;
        }
        return ruleInfo;
    }

    /**
     * 构建渠道信息列表
     */
    private List<RuleStockChannelInfo> buildChannelInfoList(AllocationRuleDto dto) {
        return dto.getInfoList().stream()
                .map(item -> {
                    RuleStockChannelInfo channelInfo = new RuleStockChannelInfo();
                    channelInfo.setChannelId(item.getChannelId());
                    channelInfo.setChannelName(item.getChannelName());
                    channelInfo.setPercentage(item.getPercentage());
                    channelInfo.setDecimalHandleType(item.getDecimalHandleType());
                    channelInfo.setRuleId(dto.getRuleId());
                    channelInfo.setCompanyCode(dto.getCompanyCode());
                    return channelInfo;
                })
                .collect(Collectors.toList());
    }

    /**
     * 构建仓库信息列表
     */
    private List<RuleStockStoreCodeInfo> buildStoreCodeInfoList(AllocationRuleDto dto) {
        return dto.getWmsSimulationCodes().stream()
                .map(storeCode -> {
                    RuleStockStoreCodeInfo storeInfo = new RuleStockStoreCodeInfo();
                    storeInfo.setRuleId(dto.getRuleId());
                    storeInfo.setStoreCode(storeCode);
                    storeInfo.setCompanyCode(dto.getCompanyCode());
                    return storeInfo;
                })
                .collect(Collectors.toList());
    }

    /**
     * 保存规则相关数据
     */
    private boolean saveRuleData(RuleStockInfo ruleInfo,
                                List<RuleStockChannelInfo> channelInfoList,
                                List<RuleStockStoreCodeInfo> storeCodeInfoList,
                                AllocationStrategy priorityType) {
        // 保存仓库信息
        boolean storeResult = ruleStockStoreCodeInfoService.saveBatch(storeCodeInfoList);
        if (!storeResult) {
            log.error("保存仓库信息失败");
            return false;
        }

        // 保存渠道信息
        boolean channelResult = ruleStockChannelInfoService.saveBatch(channelInfoList);
        if (!channelResult) {
            log.error("保存渠道信息失败");
            return false;
        }

        // 更新规则状态
        ruleInfo.setStatus(RuleStatus.PENDING_REVIEW);
        ruleInfo.setRuleMode(priorityType);
        boolean updateResult = ruleStockInfoService.updateById(ruleInfo);
        if (!updateResult) {
            log.error("更新规则状态失败");
            return false;
        }

        log.info("分货规则设置成功，规则ID: {}", ruleInfo.getId());
        return true;
    }

    /**
     * 获取规则详细信息
     * 根据规则ID和公司代码获取规则的仓库信息和渠道信息
     *
     * @param id 规则ID
     * @param companyCode 公司代码
     * @return RuleDetailsInfoDto 规则详细信息DTO
     */
    @Override
    public RuleDetailsInfoDto getInfoDetails(Long id, String companyCode) {
        log.debug("获取规则详细信息，规则ID: {}, 公司代码: {}", id, companyCode);

        // 查询仓库信息
        QueryWrapper<RuleStockStoreCodeInfo> storeQueryWrapper = new QueryWrapper<>();
        storeQueryWrapper.eq("rule_id", id);
        List<RuleStockStoreCodeInfo> storeCodeList = ruleStockStoreCodeInfoService.list(storeQueryWrapper);

        // 查询渠道信息
        QueryWrapper<RuleStockChannelInfo> channelQueryWrapper = new QueryWrapper<>();
        channelQueryWrapper.eq("rule_id", id);
        List<RuleStockChannelInfo> channelList = ruleStockChannelInfoService.list(channelQueryWrapper);

        // 构建返回结果
        RuleDetailsInfoDto dto = new RuleDetailsInfoDto();
        dto.setStoreCodeInfoList(storeCodeList);
        dto.setChannelInfoList(channelList);

        log.debug("规则详细信息获取完成，仓库数量: {}, 渠道数量: {}",
                storeCodeList.size(), channelList.size());
        return dto;
    }

    /**
     * 审核分货规则
     * 根据分货单类型执行不同的审核逻辑：
     * - 日常分货：更新为待执行状态
     * - 一次性分货/锁库单分货：立即执行分货逻辑
     *
     * @param id 规则ID
     * @param companyCode 公司代码
     * @return Boolean 审核成功返回true，失败返回false
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean toExamine(Long id, String companyCode) {
        log.info("开始审核分货规则，规则ID: {}, 公司代码: {}", id, companyCode);

        // 获取并校验规则信息
        RuleStockInfo ruleInfo = validateRuleForExamine(id);

        // 设置审核信息
        setReviewInfo(ruleInfo);

        // 根据规则类型执行不同逻辑
        return processRuleByType(ruleInfo);
    }

    /**
     * 校验规则是否可以审核
     */
    private RuleStockInfo validateRuleForExamine(Long id) {
        RuleStockInfo ruleInfo = ruleStockInfoService.getById(id);
        if (ObjectUtil.isEmpty(ruleInfo)) {
            throw new RuntimeException("分货单不存在，规则ID: " + id);
        }
        if (ruleInfo.getStatus() != RuleStatus.PENDING_REVIEW) {
            throw new RuntimeException("分货单状态不正确，当前状态: " + ruleInfo.getStatus());
        }
        return ruleInfo;
    }

    /**
     * 设置审核信息
     */
    private void setReviewInfo(RuleStockInfo ruleInfo) {
        String operatorName = SecurityUtils.getUsername();
        LocalDateTime now = LocalDateTime.now();

        ruleInfo.setFirstReviewerTime(now);
        ruleInfo.setReviewerTime(now);
        ruleInfo.setReviewerUserName(operatorName);

        log.debug("设置审核信息完成，审核人: {}, 审核时间: {}", operatorName, now);
    }

    /**
     * 根据规则类型处理不同的业务逻辑
     */
    private Boolean processRuleByType(RuleStockInfo ruleInfo) {
        Integer ruleType = ruleInfo.getRuleType();
        log.info("处理规则类型: {}", ruleType);

        switch (ruleType) {
            case RULE_TYPE_DAILY: // 日常分货
                return processDailyRule(ruleInfo);
            case RULE_TYPE_ONETIME: // 一次性分货
                return processImmediateRule(ruleInfo);
            default:
                throw new RuntimeException("不支持的分货单类型: " + ruleType);
        }
    }

    /**
     * 处理日常分货规则
     */
    private Boolean processDailyRule(RuleStockInfo ruleInfo) {
        ruleInfo.setStatus(RuleStatus.PENDING_EXECUTION);
        ruleStockInfoService.updateRuleStockInfo(ruleInfo);
        log.info("日常分货规则审核完成，规则ID: {}", ruleInfo.getId());
        return true;
    }

    /**
     * 处理立即执行的规则（一次性分货、锁库单分货）
     */
    private Boolean processImmediateRule(RuleStockInfo ruleInfo) {
        ruleInfo.setStatus(RuleStatus.EXECUTING);
        ruleInfo.setLastUpdateTime(LocalDateTime.now());
        ruleStockInfoService.updateRuleStockInfo(ruleInfo);

        try {
            // 执行分货逻辑
            if (handleAllocate(ruleInfo)) {
                ruleInfo.setStatus(RuleStatus.COMPLETED);
                ruleInfo.setOverTime(LocalDateTime.now());
                ruleStockInfoService.updateRuleStockInfo(ruleInfo);
                log.info("立即执行规则完成，规则ID: {}", ruleInfo.getId());
                return true;
            } else {
                log.error("立即执行规则失败，规则ID: {}", ruleInfo.getId());
                return false;
            }
        } catch (Exception e) {
            log.error("执行分货逻辑时发生异常，规则ID: {}", ruleInfo.getId(), e);
            throw new RuntimeException("执行分货逻辑失败", e);
        }
    }

    /**
     * 处理库存分配
     * 根据规则模式获取对应的分配策略并执行分配逻辑
     *
     * @param rule 分货规则信息
     * @return Boolean 分配成功返回true，失败返回false
     */
    private Boolean handleAllocate(RuleStockInfo rule) {
        try {
            log.info("开始执行库存分配，规则ID: {}, 规则模式: {}",
                    rule.getId(), rule.getRuleMode().getRemark());

            AllocationStrategyService allocationStrategyService =
                    strategyFactory.getStrategy(rule.getRuleMode().getRemark());

            if (allocationStrategyService == null) {
                log.error("未找到对应的分配策略，规则模式: {}", rule.getRuleMode().getRemark());
                return false;
            }

            Boolean result = allocationStrategyService.allocate(rule);
            log.info("库存分配执行完成，规则ID: {}, 结果: {}", rule.getId(), result);
            return result;

        } catch (Exception e) {
            log.error("执行库存分配时发生异常，规则ID: {}", rule.getId(), e);
            return false;
        }
    }

    /**
     * 获取商品范围列表
     * 根据规则范围类型返回对应的SKU列表
     *
     * @param ruleRange 规则范围类型（1-全部商品，2-指定商品）
     * @return List<String> SKU列表
     */
    public List<String> getSkuList(int ruleRange) {
        log.debug("获取商品范围列表，规则范围: {}", ruleRange);

        switch (ruleRange) {
            case ALL_SKU_LIST:
                return getAllSkuList();
            case APPOINT_SKU_LIST:
                log.debug("指定商品模式，返回空列表（需要在具体业务中实现）");
                return Collections.emptyList();
            default:
                log.warn("不支持的规则范围类型: {}", ruleRange);
                return Collections.emptyList();
        }
    }

    /**
     * 获取全部商品SKU列表
     */
    private List<String> getAllSkuList() {
        try {
            List<WmsInventory> wmsInventories =
                    wmsInventoryService.selectWmsInventoryListBySkuSn(new WmsInventory());

            List<String> skuList = wmsInventories.stream()
                    .map(WmsInventory::getSkuSn)
                    .collect(Collectors.toList());

            log.debug("获取全部商品SKU列表完成，数量: {}", skuList.size());
            return skuList;

        } catch (Exception e) {
            log.error("获取全部商品SKU列表时发生异常", e);
            return Collections.emptyList();
        }
    }


}
