package com.oms.inventory.aop;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.oms.inventory.holder.ChannelInventoryContextHolder;
import com.oms.inventory.holder.InventoryContextHolder;
import com.oms.inventory.holder.RelationSnHolder;
import com.oms.inventory.mapper.OmsChannelInventoryMapper;
import com.oms.inventory.mapper.RelationSn;
import com.oms.inventory.mapper.history.ChannelInventoryChangeHistoryMapper;
import com.oms.inventory.model.dto.Inventory.OmsChannelInventoryDto;
import com.oms.inventory.model.entity.OmsChannelInventory;
import com.oms.inventory.model.entity.history.ChannelInventoryChangeHistory;
import com.ruoyi.common.security.utils.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.math.BigDecimal;
import java.util.Arrays;

@Slf4j
@Aspect
@Component
public class OmsChannelInventoryAspect {

    @Resource
    private ChannelInventoryChangeHistoryMapper changeHistoryMapper;
    @Resource
    private OmsChannelInventoryMapper channelInventoryMapper;

    @Pointcut("execution(* com.oms.inventory.service.IOmsChannelInventoryService.*(..))")
    public void allMethods() {}

    /**
     * 在执行任何方法之前调用此方法，主要针对库存操作进行前置处理
     * @param joinPoint 切入点，用于获取方法参数和签名
     */
    @Before("allMethods()")
    public void beforeInsertOrUpdate(JoinPoint joinPoint) {
        String methodName = joinPoint.getSignature().getName(); // 获取函数名
        log.debug("beforeInsertOrUpdate 函数名: {}", methodName);
        log.debug("beforeInsertOrUpdate 原始数据 {}", joinPoint);
        // 获取方法参数
        Object[] args = joinPoint.getArgs();
        log.debug("方法参数: {}", Arrays.toString(args));

        // 解析 @RelationSn 注解
        String relationSn = "";
        // 获取目标方法的 Method 对象
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        // 遍历方法参数，寻找带有@RelationSn注解的参数
        for (int i = 0; i < method.getParameters().length; i++) {
            Parameter parameter = method.getParameters()[i];
            if (parameter.getAnnotation(RelationSn.class) != null) {
                relationSn = (String) joinPoint.getArgs()[i];
                break;
            }
        }

        // 记录解析到的relationSn值
        log.debug("relationSn：{}", relationSn);
        // 将relationSn值存储到线程上下文中，供后续使用
        RelationSnHolder.set(relationSn);


        switch (methodName){
            case "allocationInventory":
                log.debug("beforeInsertOrUpdate：{}", args[0]);
                Integer channelId = (Integer) args[1];
                String SkuSn = (String) args[2];
                //获取查询参数
                QueryWrapper wrapper = new QueryWrapper();
                wrapper.eq("channel_id", channelId);
                wrapper.eq("sku_sn", SkuSn);
                // 从数据库中获取当前库存信息
                OmsChannelInventory beforeEntity = channelInventoryMapper.selectOne(wrapper);
                log.debug("beforeInsertOrUpdate：{}", beforeEntity);
                // 如果数据库中不存在当前库存信息，则创建一个新的OmsChannelInventory对象
                if (beforeEntity == null){
                    beforeEntity = new OmsChannelInventory();
                    beforeEntity.setChannelId(channelId);
                    beforeEntity.setSkuSn(SkuSn);
                    beforeEntity.setAllocatedStock(BigDecimal.ZERO);
                    beforeEntity.setReservedStock(BigDecimal.ZERO);
                    beforeEntity.setAvailableStock(BigDecimal.ZERO);
                    beforeEntity.setFrozenStock(BigDecimal.ZERO);
                    beforeEntity.setVersion(0);
                }
                // 将前置实体对象存储到线程上下文中，供后续使用
                OmsChannelInventoryDto omsChannelInventoryDto = new OmsChannelInventoryDto();
                omsChannelInventoryDto.setOmsChannelInventory(beforeEntity);
                omsChannelInventoryDto.setMethodName(methodName);
                omsChannelInventoryDto.setRelatedId(SkuSn);
                ChannelInventoryContextHolder.setBeforeEntity(omsChannelInventoryDto);
                break;
        }
    }

    /**
     * 在库存管理服务的方法执行后调用此方法，记录库存变化历史
     * 该方法主要负责根据方法执行结果和前后库存状态，创建并保存库存历史记录
     *
     * @param joinPoint 切入点对象，提供关于方法执行的信息
     * @param result 方法执行结果，用于判断方法执行是否成功
     * @throws Throwable 如果记录过程出现异常
     */
    @AfterReturning(pointcut = "allMethods()", returning = "result")
    public void afterInsertOrUpdate(JoinPoint joinPoint, Object result) throws Throwable {
        log.debug("afterInsertOrUpdate 原始数据 {}", joinPoint);
        // 如果方法执行结果为非正整数，清除上下文并直接返回
        if (result instanceof Integer && ((Integer) result) <= 0) {
            InventoryContextHolder.clear();
            return;
        }
        // 获取变更前的库存实体
        OmsChannelInventoryDto beforeEntity = ChannelInventoryContextHolder.getBeforeEntity();
        // 如果变更前的库存实体为空，清除上下文并直接返回
        if (beforeEntity == null) {
            InventoryContextHolder.clear();
            return;
        }
        log.debug("afterInsertOrUpdate：{}", beforeEntity);
        OmsChannelInventory beforeOmsChannelInventory = beforeEntity.getOmsChannelInventory();
        if (beforeOmsChannelInventory == null){
            InventoryContextHolder.clear();
            return;
        }
        QueryWrapper wrapper = new QueryWrapper();
        wrapper.eq("channel_id",beforeOmsChannelInventory.getChannelId());
        wrapper.eq("sku_sn", beforeOmsChannelInventory.getSkuSn());
        // 从数据库中获取当前库存信息
        OmsChannelInventory afterEntity = channelInventoryMapper.selectOne(wrapper);
        // 获取方法名称，用于判断操作类型
        String methodName = joinPoint.getSignature().getName();
        log.debug("methodName：{}", methodName);
        //根据 methodName 获取 operationType （ALLOCATE/RELEASE/FROZEN/UNFROZEN/ADJUST/ORDER_DEDUCT/RETURN_RESTORE/MANUAL_ADJUST）
        String operationType = getOperationType(methodName);
        ChannelInventoryChangeHistory history = new ChannelInventoryChangeHistory();
        history.setOperationType(operationType);
        history.setChannelId(afterEntity.getChannelId());
        history.setSkuSn(beforeOmsChannelInventory.getSkuSn());
        history.setOldAvailableStock(beforeOmsChannelInventory.getAvailableStock().intValue());
        history.setNewAvailableStock(afterEntity.getAvailableStock().intValue());
        history.setOldReservedStock(beforeOmsChannelInventory.getReservedStock().intValue());
        history.setNewReservedStock(afterEntity.getReservedStock().intValue());
        history.setOldFrozenStock(beforeOmsChannelInventory.getFrozenStock().intValue());
        history.setNewFrozenStock(afterEntity.getFrozenStock().intValue());
        //根据 methodName 获取 changeReason
        String changeReason = getChangeReason(methodName);
        history.setChangeReason(changeReason);
        //更具methodName 获取 relatedType
        Integer relatedType = getRelatedType(methodName);
        history.setRelatedType(relatedType);
        history.setRelatedId(RelationSnHolder.get());
        history.setOperator(SecurityUtils.getUsername());
        history.setVersion(afterEntity.getVersion());
        changeHistoryMapper.insert(history);
        // 清除库存上下文
        InventoryContextHolder.clear();
        RelationSnHolder.remove();
    }

    private Integer getRelatedType(String methodName) {
        switch (methodName) {
            case "allocationInventory":
                return 1;
            default:
                return 0;
        }
    }

    private String getOperationType(String methodName) {
        switch (methodName) {
            case "allocationInventory":
                return "ALLOCATE";
            default:
                return "UNKNOWN";
        }
    }

    private String getChangeReason(String methodName) {
        switch (methodName) {
            case "allocationInventory":
                return "分配库存";
            default:
                return "未知原因";
        }
    }
}
