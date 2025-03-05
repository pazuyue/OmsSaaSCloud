package com.oms.inventory.aop;

import com.oms.inventory.mapper.OmsInventoryMapper;
import com.oms.inventory.mapper.history.InventoryHistoryMapper;
import com.oms.inventory.model.entity.InventoryHistory;
import com.oms.inventory.model.entity.OmsInventory;
import com.ruoyi.common.security.utils.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Arrays;

@Slf4j
@Aspect
@Component
public class InventoryHistoryAspect {

    @Resource
    private InventoryHistoryMapper inventoryHistoryMapper; // 注入 InventoryHistoryMapper
    @Resource
    private OmsInventoryMapper mapper;

    @Pointcut("execution(* com.oms.inventory.mapper.OmsInventoryMapper.insertOrUpdate(..))")
    public void allMethods() {}

    @Before("allMethods()")
    public void beforeInsertOrUpdate(JoinPoint joinPoint) {
        log.debug("beforeInsertOrUpdate 原始数据 {}", joinPoint);
        // 获取旧数据并存储到线程上下文
        Object[] args = joinPoint.getArgs();
        log.debug("方法参数: {}", Arrays.toString(args));
        if (args.length == 0 || !(args[0] instanceof OmsInventory)) {
            return;
        }
        OmsInventory omsInventory = (OmsInventory) args[0];
        log.debug("原始参数：{}", omsInventory);
        OmsInventory beforeEntity;
        beforeEntity = mapper.selectBySkuSn(omsInventory.getSkuSn());
        log.debug("beforeInsertOrUpdate：{}", beforeEntity);
        if (beforeEntity == null){
            beforeEntity = new OmsInventory();
            beforeEntity.setSkuSn(omsInventory.getSkuSn());
        }
        InventoryContextHolder.setBeforeEntity(beforeEntity);
    }


    @AfterReturning(pointcut = "allMethods()", returning = "result")
    public void afterInsertOrUpdate(JoinPoint joinPoint, Object result) throws Throwable {
        // 获取原始库存数据
        log.debug("result：{}", result);
        if (result instanceof Integer && ((Integer) result) <= 0) {
            InventoryContextHolder.clear();
            return;
        }

        OmsInventory beforeEntity = InventoryContextHolder.getBeforeEntity();
        if (beforeEntity == null) {
            InventoryContextHolder.clear();
            return;
        }
        log.debug("afterInsertOrUpdate：{}", beforeEntity);
        OmsInventory afterEntity = mapper.selectBySkuSn(beforeEntity.getSkuSn());
        log.debug("afterEntity：{}", afterEntity);
        String methodName = joinPoint.getSignature().getName();
        log.debug("methodName：{}", methodName);
        // 判断操作类型
        InventoryHistory history = new InventoryHistory();
        history.setSkuSn(beforeEntity.getSkuSn());

        history.setBeforeAvailable(beforeEntity.getAvailableStock());
        history.setAfterAvailable(afterEntity.getAvailableStock());
        history.setBeforeReserved(beforeEntity.getReservedStock());
        history.setAfterReserved(afterEntity.getReservedStock());
        history.setBeforeFrozen(beforeEntity.getFrozenStock());
        history.setAfterFrozen(afterEntity.getFrozenStock());
        history.setBeforeMinStock(beforeEntity.getMinStock());
        history.setAfterMinStock(afterEntity.getMinStock());
        history.setChangeAmount(afterEntity.getAvailableStock() - beforeEntity.getAvailableStock());

        String operationType = determineOperationType(methodName,history);
        history.setOperationType(operationType);
        history.setOperatorId(SecurityUtils.getUserId());
        history.setOperatorName(SecurityUtils.getUsername());
        history.setChangeTime(LocalDateTime.now());
        log.debug("历史库存数据：{}", history);
        inventoryHistoryMapper.insert(history);
        InventoryContextHolder.clear();
    }

    private String determineOperationType(String methodName,InventoryHistory history) {
        log.debug("determineOperationType：{}", methodName);
         if (methodName.equals("insertOrUpdate")) {
            // 需要根据实际执行的操作判断
            return determineInsertOrUpdateOperation(history);
        }
        return "UNKNOWN";
    }

    private String determineInsertOrUpdateOperation(InventoryHistory history) {
        // 查询旧数据是否存在
        log.debug("determineInsertOrUpdateOperation：{}", history);
        if (history.getChangeAmount()>0) {
            return "INCREASE";
        } else {
            return "DECREASE";
        }
    }
}
