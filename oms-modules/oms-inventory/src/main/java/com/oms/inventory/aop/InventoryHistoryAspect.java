package com.oms.inventory.aop;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.oms.inventory.mapper.OmsInventoryMapper;
import com.oms.inventory.mapper.history.InventoryHistoryMapper;
import com.oms.inventory.model.entity.InventoryHistory;
import com.oms.inventory.model.entity.OmsInventory;
import com.oms.inventory.service.impl.OmsInventoryServiceImpl;
import com.ruoyi.common.core.context.SecurityContextHolder;
import com.ruoyi.common.security.utils.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.beans.BeanUtils;

import javax.annotation.Resource;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Map;

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
            log.debug("参数类型不正确: {}", Arrays.toString(args));
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
        log.debug("afterInsertOrUpdate：{}", beforeEntity);
        if (beforeEntity == null) {
            InventoryContextHolder.clear();
            return;
        }

        OmsInventory afterEntity = mapper.selectBySkuSn(beforeEntity.getSkuSn());
        log.debug("afterEntity：{}", afterEntity);
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

        history.setOperatorId(SecurityUtils.getUserId());
        history.setOperatorName(SecurityUtils.getUsername());
        history.setChangeTime(LocalDateTime.now());
        log.debug("历史库存数据：{}", history);
        inventoryHistoryMapper.insert(history);
        InventoryContextHolder.clear();
    }
}
