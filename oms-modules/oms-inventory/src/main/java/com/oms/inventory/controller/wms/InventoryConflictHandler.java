package com.oms.inventory.controller.wms;

import com.ruoyi.common.core.web.domain.AjaxResult;
import org.springframework.core.annotation.Order;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(0)
@RestControllerAdvice(assignableTypes={WmsInventoryController.class,WmsInventoryBatchController.class})
public class InventoryConflictHandler {
    @ExceptionHandler(PessimisticLockingFailureException.class)
    public AjaxResult conflict(PessimisticLockingFailureException ignored) {
        return AjaxResult.error("库存正在被其他操作更新，本次调整已回滚，请稍后重试");
    }
}
