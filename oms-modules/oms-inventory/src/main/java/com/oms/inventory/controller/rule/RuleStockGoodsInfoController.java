package com.oms.inventory.controller.rule;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.oms.inventory.model.entity.rule.RuleStockGoodsInfo;
import com.oms.inventory.model.vo.RuleStockExcel;
import com.oms.inventory.service.rule.RuleStockGoodsInfoService;
import com.ruoyi.common.core.utils.StringUtils;
import com.ruoyi.common.core.utils.poi.ExcelUtil;
import com.ruoyi.common.core.web.controller.BaseController;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.core.web.page.TableDataInfo;
import lombok.SneakyThrows;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
     * @param file 导入文件
     * @param ruleId 规则ID
     * @param companyCode 公司代码
     * @return 导入结果
     */
    @SneakyThrows
    @PostMapping(value = "/import")
    public AjaxResult importHandel(MultipartFile file, @RequestParam(value = "rule_id") String ruleId, @RequestParam(value = "company_code") String companyCode) {
        logger.debug("ruleId:"+ruleId);
        logger.debug("companyCode:"+companyCode);
        try{
            // 先清空该规则ID对应的历史数据
            QueryWrapper<RuleStockGoodsInfo> deleteWrapper = new QueryWrapper<>();
            deleteWrapper.eq("rule_id", ruleId);
            ruleStockGoodsInfoService.remove(deleteWrapper);
            
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

            // 构建详细的导入结果信息
            Map<String, Object> resultData = new HashMap<>();
            resultData.put("totalCount", ruleStockExcels.size());
            resultData.put("successCount", saveList.size());
            resultData.put("skuList", saveList);

            return success(resultData);
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
    
    /**
     * 查询导入商品列表
     * 支持按SKU查询，不传则查询全部
     */
    @GetMapping(value = "/list/{rule_id}")
    public TableDataInfo list(@PathVariable("rule_id") Long ruleId, @RequestParam(value = "sku_sn", required = false) String skuSn){
        startPage();
        QueryWrapper<RuleStockGoodsInfo> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("rule_id", ruleId);
        if (StringUtils.isNotEmpty(skuSn)) {
            queryWrapper.like("sku_sn", skuSn);
        }
        List<RuleStockGoodsInfo> list = ruleStockGoodsInfoService.list(queryWrapper);
        return getDataTable(list);
    }

    /**
     * 下载导入模板
     */
    @PostMapping("/importTemplate")
    public void importTemplate(HttpServletResponse response) throws IOException
    {
        ExcelUtil<RuleStockExcel> util = new ExcelUtil<>(RuleStockExcel.class);
        util.importTemplateExcel(response, "商品导入模板数据");
    }
}
