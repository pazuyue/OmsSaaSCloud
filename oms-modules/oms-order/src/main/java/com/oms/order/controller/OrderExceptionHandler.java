package com.oms.order.controller;
import com.ruoyi.common.core.web.domain.AjaxResult;
import org.springframework.web.bind.annotation.*;
@RestControllerAdvice(basePackageClasses=OrderController.class)
public class OrderExceptionHandler {
    @ExceptionHandler(IllegalArgumentException.class) public AjaxResult invalid(IllegalArgumentException error){return AjaxResult.error(error.getMessage());}
    @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class) public AjaxResult duplicate(){return AjaxResult.error("相同店铺、卖家或任务已存在，请刷新后核对");}
    @ExceptionHandler(IllegalStateException.class) public AjaxResult unavailable(IllegalStateException error){return AjaxResult.error(error.getMessage());}
}
