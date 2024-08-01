package com.oms.goods.controller.goods;

import cn.hutool.core.util.ObjectUtil;
import com.oms.goods.factory.GoodsPluginFactory;
import com.oms.goods.model.enums.GoodsPluginEnum;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.core.utils.poi.ExcelUtil;
import com.ruoyi.common.core.web.controller.BaseController;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.core.web.page.TableDataInfo;
import com.ruoyi.common.security.utils.SecurityUtils;
import com.oms.goods.model.entity.goods.GoodsSkuSnInfoTmp;
import com.oms.goods.model.vo.export.GoodsVO;
import com.oms.goods.service.goods.GoodsSkuSnInfoTmpService;
import com.ruoyi.system.api.RemoteSysCompanyModelAssociationConfigService;
import com.ruoyi.system.api.domain.SysCompanyModelAssociationConfig;
import com.ruoyi.system.api.domain.SysUser;
import com.ruoyi.system.api.model.LoginUser;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/goodsAdministration")
public class GoodsController extends BaseController {

    @Resource
    private GoodsSkuSnInfoTmpService goodsSkuSnInfoTmpService;
    @Resource
    private GoodsPluginFactory goodsPluginFactory;
    @Resource
    private RemoteSysCompanyModelAssociationConfigService remoteSysCompanyModelAssociationConfigService;

    /**
     * 商品导入
     * @param file
     * @return
     */
    @SneakyThrows
    @PostMapping(value = "/import")
    public AjaxResult export(MultipartFile file,String import_batch,String company_code) {
        try{
            //@Cleanup
            //InputStream inputStream = file.getInputStream();
            ExcelUtil<GoodsVO> util = new ExcelUtil<>(GoodsVO.class);
            List<GoodsVO> goodsList = util.importExcel(file.getInputStream());
            String importBatch;
            if (ObjectUtil.isEmpty(import_batch)){
                importBatch= goodsPluginFactory.getBean(company_code).export(goodsList);
            }else {
                importBatch = goodsPluginFactory.getBean(company_code).export(goodsList,import_batch);
            }
            if (!ObjectUtil.isEmpty(importBatch)){
                return success(importBatch);
            }
            return success("导入失败");
        }catch (Exception e){
            return error(e.getMessage());
        }
    }

    /**
     * 导入明细
     * @param importBatch
     * @return
     */
    @SneakyThrows
    @PostMapping(value = "/list")
    public TableDataInfo exportList(@RequestParam(value = "import_batch") String importBatch)
    {
        startPage();
        List<GoodsSkuSnInfoTmp> list = goodsSkuSnInfoTmpService.exportList(importBatch);
        return getDataTable(list);
    }

    @SneakyThrows
    @PostMapping(value = "/goodsTest")
    public AjaxResult goodsTest() {
        R<SysCompanyModelAssociationConfig> qm = remoteSysCompanyModelAssociationConfigService.getInfoByCompanyCode("qm");
        return success(qm);
    }

    @PostMapping("/importTemplate")
    public void importTemplate(HttpServletResponse response) throws IOException
    {
        ExcelUtil<GoodsVO> util = new ExcelUtil<>(GoodsVO.class);
        util.importTemplateExcel(response, "商品导入模板数据");
    }
}
