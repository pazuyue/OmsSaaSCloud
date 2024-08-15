package com.oms.supplychain.controller.warehouse;


import cn.hutool.core.lang.Console;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.oms.supplychain.model.entity.warehouse.OwnerInfo;
import com.oms.supplychain.model.vo.warehouse.OwnerInfoVO;
import com.oms.supplychain.service.warehouse.OwnerInfoService;
import com.ruoyi.common.core.web.controller.BaseController;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.core.web.page.TableDataInfo;
import lombok.SneakyThrows;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.List;

@RestController
@RequestMapping("/owner")
public class OwnerInfoController extends BaseController {

    @Resource
    private OwnerInfoService ownerInfoService;
    @Value("${pageable.page.size:10}")
    private Integer pageSize;

    @SneakyThrows
    @PostMapping(value = "/save")
    public AjaxResult save(@Validated OwnerInfoVO ownerInfoVO, @RequestParam(value = "company_code",required = false) String companyCode){
        Console.log(ownerInfoVO.toString());
        if (ownerInfoService.save(ownerInfoVO,companyCode))
            return success();
        return error("保存失败");
    }

    @SneakyThrows
    @PostMapping(value = "/list")
    public TableDataInfo list(@RequestParam(required = false) String owner_code, @RequestParam(value = "page",defaultValue = "1") Integer page){
        List<OwnerInfo> list = ownerInfoService.list(owner_code, page, pageSize);
        return getDataTable(list);
    }
}

