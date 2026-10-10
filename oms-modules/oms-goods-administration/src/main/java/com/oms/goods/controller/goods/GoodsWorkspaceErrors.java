package com.oms.goods.controller.goods;

import com.ruoyi.common.core.web.domain.AjaxResult;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(basePackages="com.oms.goods.controller.goods")
public class GoodsWorkspaceErrors {
    @ExceptionHandler(IllegalArgumentException.class)
    public AjaxResult invalid(IllegalArgumentException error){return AjaxResult.error(error.getMessage());}
}
