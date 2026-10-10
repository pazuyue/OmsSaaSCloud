package com.oms.inventory.controller.rule;

import com.ruoyi.common.core.web.domain.AjaxResult;
import org.springframework.core.annotation.Order;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.web.bind.annotation.*;

@Order(0)
@RestControllerAdvice(assignableTypes={AllocationWorkspaceController.class,DailyAllocationController.class,RuleStockInfoController.class,RuleStockInfoHandleController.class})
public class AllocationExceptionHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    public AjaxResult invalid(IllegalArgumentException e){return AjaxResult.error(e.getMessage());}
    @ExceptionHandler(PessimisticLockingFailureException.class)
    public AjaxResult busy(PessimisticLockingFailureException e){return AjaxResult.error("分货或库存正在被其他操作更新，本次操作已回滚，请稍后重试");}
}
