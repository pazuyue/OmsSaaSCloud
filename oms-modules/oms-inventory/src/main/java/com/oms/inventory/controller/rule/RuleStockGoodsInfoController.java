package com.oms.inventory.controller.rule;

import com.oms.inventory.model.entity.rule.RuleStockGoodsInfo;
import com.oms.inventory.model.vo.RuleStockExcel;
import com.oms.inventory.service.rule.RuleStockGoodsInfoService;
import com.ruoyi.common.core.utils.poi.ExcelUtil;
import com.ruoyi.common.core.web.controller.BaseController;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.core.web.page.TableDataInfo;
import lombok.SneakyThrows;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

/**
 * 分货单商品信息Controller
 */
@RestController
@RequestMapping("/ruleStockGoods")
public class RuleStockGoodsInfoController extends BaseController {
    @Resource
    private RuleStockGoodsInfoService ruleStockGoodsInfoService;

    /**
     * 商品导入
     * @param file
     * @return
     */
    @SneakyThrows
    @PostMapping(value = "/import")
    public AjaxResult importHandel(MultipartFile file, @RequestParam(value = "rule_id") String ruleId, @RequestParam(value = "company_code") String companyCode) {
        logger.debug("ruleId:"+ruleId);
        logger.debug("companyCode:"+companyCode);
        try{
            ExcelUtil<RuleStockExcel> util = new ExcelUtil<>(RuleStockExcel.class);
            List<RuleStockExcel> ruleStockExcels = util.importExcel(file.getInputStream());
            logger.debug("ruleStockSkuList:"+ruleStockExcels.toString());
            List<RuleStockGoodsInfo> saveList = new ArrayList<>();
            ruleStockExcels.forEach(ruleStockExcel -> {
                RuleStockGoodsInfo ruleStockGoodsInfo = new RuleStockGoodsInfo();
                ruleStockGoodsInfo.setRuleId(Integer.parseInt(ruleId));
                ruleStockGoodsInfo.setSkuSn(ruleStockExcel.getSkuSn());
                ruleStockGoodsInfo.setCompanyCode(companyCode);
                saveList.add(ruleStockGoodsInfo);
            });
            ruleStockGoodsInfoService.saveBatch(saveList);
            return success("导入成功");
        }catch (Exception e){
            logger.error("导入失败",e);
            return error(e.getMessage());
        }
    }

    /**
     * 导入商品列表
     * 按照sku+rule_id 查询
     * SKU支持，分割多个
     */
    @PostMapping(value = "/importList/{rule_id}")
    public TableDataInfo importList(@PathVariable("rule_id") Long ruleId, @RequestParam(value = "sku_sns") String skuSns){
        startPage();
        //skuSns 逗号分割成数组
        String[] skuSnArray = skuSns.split(",");
        List<RuleStockGoodsInfo> list = ruleStockGoodsInfoService.selectRuleStockGoodsInfoList(ruleId,skuSnArray);
        return getDataTable(list);
    }
}
