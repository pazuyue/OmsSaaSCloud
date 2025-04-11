package com.oms.inventory.aop;

import com.oms.inventory.holder.InventoryContextHolder;
import com.oms.inventory.holder.RelationSnHolder;
import com.oms.inventory.mapper.OmsInventoryMapper;
import com.oms.inventory.mapper.RelationSn;
import com.oms.inventory.mapper.history.InventoryHistoryMapper;
import com.oms.inventory.model.entity.InventoryHistory;
import com.oms.inventory.model.entity.OmsInventory;
import com.ruoyi.common.security.utils.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.*;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
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

    /**
     * 在执行任何方法之前调用此方法，主要针对库存操作进行前置处理
     * @param joinPoint 切入点，用于获取方法参数和签名
     */
    @Before("allMethods()")
    public void beforeInsertOrUpdate(JoinPoint joinPoint) {
        // 记录方法执行前的日志
        log.debug("beforeInsertOrUpdate 原始数据 {}", joinPoint);
        // 获取方法参数
        Object[] args = joinPoint.getArgs();
        log.debug("方法参数: {}", Arrays.toString(args));
        // 检查参数是否包含OmsInventory类型
        if (args.length == 0 || !(args[1] instanceof OmsInventory)) {
            return;
        }

        // 获取并记录OmsInventory类型的参数
        OmsInventory omsInventory = (OmsInventory) args[1];
        log.debug("原始参数：{}", omsInventory);
        OmsInventory beforeEntity;
        // 从数据库中获取当前库存信息
        beforeEntity = mapper.selectBySkuSn(omsInventory.getSkuSn());
        log.debug("beforeInsertOrUpdate：{}", beforeEntity);
        // 如果数据库中不存在当前库存信息，则创建一个新的OmsInventory对象
        if (beforeEntity == null){
            beforeEntity = new OmsInventory();
            beforeEntity.setSkuSn(omsInventory.getSkuSn());
        }

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
        // 将前置实体对象存储到线程上下文中，供后续使用
        InventoryContextHolder.setBeforeEntity(beforeEntity);
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
        // 获取原始库存数据
        log.debug("result：{}", result);
        // 如果方法执行结果为非正整数，清除上下文并直接返回
        if (result instanceof Integer && ((Integer) result) <= 0) {
            InventoryContextHolder.clear();
            return;
        }

        // 获取变更前的库存实体
        OmsInventory beforeEntity = InventoryContextHolder.getBeforeEntity();
        // 如果变更前的库存实体为空，清除上下文并直接返回
        if (beforeEntity == null) {
            InventoryContextHolder.clear();
            return;
        }
        log.debug("afterInsertOrUpdate：{}", beforeEntity);
        // 根据SKU编号获取变更后的库存实体
        OmsInventory afterEntity = mapper.selectBySkuSn(beforeEntity.getSkuSn());
        log.debug("afterEntity：{}", afterEntity);
        // 获取方法名称，用于判断操作类型
        String methodName = joinPoint.getSignature().getName();
        log.debug("methodName：{}", methodName);
        // 获取关联编号，并在使用后移除以避免内存泄漏
        String relationSn = RelationSnHolder.get();
        RelationSnHolder.remove();

        // 创建库存历史记录对象并设置基本属性
        InventoryHistory history = new InventoryHistory();
        history.setRelationSn(relationSn);
        history.setSkuSn(beforeEntity.getSkuSn());

        // 设置变更前后的可用库存
        history.setBeforeAvailable(beforeEntity.getAvailableStock());
        history.setAfterAvailable(afterEntity.getAvailableStock());
        // 设置变更前后的预留库存
        history.setBeforeReserved(beforeEntity.getReservedStock());
        history.setAfterReserved(afterEntity.getReservedStock());
        // 设置变更前后的冻结库存
        history.setBeforeFrozen(beforeEntity.getFrozenStock());
        history.setAfterFrozen(afterEntity.getFrozenStock());
        // 设置变更前后的最小库存
        history.setBeforeMinStock(beforeEntity.getMinStock());
        history.setAfterMinStock(afterEntity.getMinStock());
        // 计算库存变化量
        history.setChangeAmount(afterEntity.getAvailableStock() - beforeEntity.getAvailableStock());

        // 根据方法名称和历史记录对象确定操作类型
        String operationType = determineOperationType(methodName,history);
        history.setOperationType(operationType);
        // 设置操作者ID和名称
        history.setOperatorId(SecurityUtils.getUserId());
        history.setOperatorName(SecurityUtils.getUsername());
        // 设置变更时间
        history.setChangeTime(LocalDateTime.now());
        log.debug("历史库存数据：{}", history);
        // 插入库存历史记录
        inventoryHistoryMapper.insert(history);
        // 清除库存上下文
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
