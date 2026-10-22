package com.oms.order.controller;

import com.oms.order.service.OrderRepository;
import com.oms.order.service.OrderDocumentInfo;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import com.ruoyi.common.security.utils.SecurityUtils;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.util.Map;
import static com.oms.order.service.OrderValues.*;

@RestController @RequestMapping("/orders/business")
public class OrderDocumentController {
    @Resource private OrderRepository repository;
    @RequiresPermissions("order:order:update") @PostMapping("/{id}/notes")
    public AjaxResult note(@PathVariable long id,@RequestBody Map<String,Object> body){
        OrderDocumentInfo.addNote(repository,company(SecurityUtils.getLoginUser().getCompanyCode()),id,text(body,"note"),SecurityUtils.getUsername());return AjaxResult.success();
    }
}
