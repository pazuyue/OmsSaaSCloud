package com.ruoyi.goods.controller.goods;

import com.ruoyi.common.core.utils.poi.ExcelUtil;
import com.ruoyi.common.core.web.controller.BaseController;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.core.web.page.TableDataInfo;
import com.ruoyi.common.security.utils.SecurityUtils;
import com.ruoyi.goods.model.entity.goods.GoodsSkuSnInfoTmp;
import com.ruoyi.goods.model.vo.export.GoodsVO;
import com.ruoyi.goods.service.goods.GoodsSkuSnInfoTmpService;
import com.ruoyi.system.api.model.LoginUser;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/goodsAdministration")
public class GoodsController extends BaseController {

    @Resource
    private GoodsSkuSnInfoTmpService goodsSkuSnInfoTmpService;
    /**
     * 商品导入
     * @param file
     * @return
     */
    @SneakyThrows
    @PostMapping(value = "/export")
    public AjaxResult export(MultipartFile file,String import_batch) {
        try{
            //@Cleanup
            //InputStream inputStream = file.getInputStream();
            ExcelUtil<GoodsVO> util = new ExcelUtil<>(GoodsVO.class);
            List<GoodsVO> goodsList = util.importExcel(file.getInputStream());
            String operName = SecurityUtils.getUsername();

            LoginUser user = SecurityUtils.getLoginUser();
            log.debug("operName:{}", operName);
            log.debug("goodsList:{}", goodsList);
            log.debug("user.sysUser:{}", user.getSysUser());
            goodsSkuSnInfoTmpService.export(goodsList);
            return success(goodsList.toString());
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
        return success("goodsTestOK");
    }
}
