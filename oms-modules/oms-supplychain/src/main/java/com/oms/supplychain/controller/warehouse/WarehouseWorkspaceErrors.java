package com.oms.supplychain.controller.warehouse;

import com.ruoyi.common.core.web.domain.AjaxResult;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes={OwnerInfoController.class,WmsRealStoreInfoController.class,WmsSimulationStoreInfoController.class,OwnerWarehouseController.class})
public class WarehouseWorkspaceErrors {
    @ExceptionHandler(IllegalArgumentException.class)
    public AjaxResult invalid(IllegalArgumentException error){return AjaxResult.error(error.getMessage());}
    @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class)
    public AjaxResult duplicate(){return AjaxResult.error("编码、名称或关联已存在，请刷新后重试");}
}
