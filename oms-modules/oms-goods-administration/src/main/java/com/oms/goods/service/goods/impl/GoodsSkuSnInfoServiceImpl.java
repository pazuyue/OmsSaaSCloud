package com.oms.goods.service.goods.impl;


import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.oms.goods.mapper.GoodsSkuSnInfoMapper;
import com.oms.goods.model.entity.goods.GoodsSkuSnInfo;
import com.oms.goods.model.entity.goods.GoodsSkuSnInfoTmp;
import com.oms.goods.service.goods.GoodsCategoryService;
import com.oms.goods.service.goods.GoodsSkuSnInfoService;
import com.ruoyi.common.core.utils.StringUtils;
import com.ruoyi.common.security.utils.SecurityUtils;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * <p>
 * 产品信息表 服务实现类
 * </p>
 *
 * @author 月光
 * @since 2024-07-30
 */
@Service
public class GoodsSkuSnInfoServiceImpl extends ServiceImpl<GoodsSkuSnInfoMapper, GoodsSkuSnInfo> implements GoodsSkuSnInfoService {

    @Resource
    private GoodsColorServiceImpl goodsColorService;
    @Resource
    private GoodsSizeServiceImpl goodsSizeService;
    @Resource
    private GoodsSkuSnInfoTmpServiceImpl goodsSkuSnInfoTmpService;
    @Resource
    GoodsCategoryService goodsCategoryService;

    public boolean toExamine(String importBatch,String companyCode){
        QueryWrapper<GoodsSkuSnInfoTmp> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("import_batch",importBatch);
        queryWrapper.ne("notes","正常");
        GoodsSkuSnInfoTmp infoTmp = goodsSkuSnInfoTmpService.getOne(queryWrapper);
        if (!ObjectUtil.isEmpty(infoTmp)){
            throw new RuntimeException("审核失败，请先处理异常导入信息");
        }
        QueryWrapper<GoodsSkuSnInfoTmp> queryWrapper2 = new QueryWrapper<>();
        queryWrapper2.eq("import_batch",importBatch);
        queryWrapper2.eq("notes","正常");
        List<GoodsSkuSnInfoTmp> list = goodsSkuSnInfoTmpService.list(queryWrapper2);
        List<GoodsSkuSnInfo> goodsSkuSnInfoArrayList = new ArrayList<>();
        String operName = SecurityUtils.getUsername();
        for(GoodsSkuSnInfoTmp tmp:list) {
            Integer colorCode = goodsColorService.selectOrSaveByColorName(tmp.getColorCode(),companyCode);
            if (ObjectUtil.isEmpty(colorCode))
                throw new RuntimeException("审核失败，色号处理异常");
            Integer sizeCode = goodsSizeService.selectOrSaveBySizeName(tmp.getSizeCode(),companyCode);
            if (ObjectUtil.isEmpty(sizeCode))
                throw new RuntimeException("审核失败，尺码处理异常");
            Integer categoryCode = goodsCategoryService.selectCategoryCode(tmp.getCategoryCode());
            if (ObjectUtil.isEmpty(categoryCode))
                throw new RuntimeException("审核失败，类名不存在");
            GoodsSkuSnInfo goods = new GoodsSkuSnInfo();
            goods.setSkuSn(tmp.getSkuSn());
            goods.setGoodsSn(tmp.getGoodsSn());
            goods.setBarcodeSn(tmp.getBarcodeSn());
            goods.setGoodsName(tmp.getGoodsName());
            goods.setCategoryCode(categoryCode);
            goods.setColorCode(colorCode);
            goods.setSizeCode(sizeCode);
            goods.setMarketPrice(tmp.getMarketPrice());
            goods.setValidity(tmp.getValidity());
            goods.setGoodsDesc(tmp.getGoodsDesc());
            goods.setIsFd(tmp.getIsFd());
            goods.setIsGift(tmp.getIsGift());
            goods.setDescription(tmp.getNotes());
            goods.setCompanyCode(companyCode);
            goods.setCreateUser(operName);
            goodsSkuSnInfoArrayList.add(goods);
        }

        return this.saveBatch(goodsSkuSnInfoArrayList);
    }

    @Override
    public GoodsSkuSnInfo selectGoodsSkuSnInfoById(Long id) {
        return this.baseMapper.selectById(id);
    }

    @Override
    public GoodsSkuSnInfo selectGoodsSkuSnInfo(GoodsSkuSnInfo goodsSkuSnInfo) {
        log.debug("selectGoodsSkuSnInfo "+goodsSkuSnInfo);
        QueryWrapper<GoodsSkuSnInfo> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq(StringUtils.isNotEmpty(goodsSkuSnInfo.getCompanyCode()),"company_code", goodsSkuSnInfo.getCompanyCode());
        queryWrapper.eq(StringUtils.isNotEmpty(goodsSkuSnInfo.getSkuSn()),"sku_sn",goodsSkuSnInfo.getSkuSn());
        queryWrapper.eq(StringUtils.isNotEmpty(goodsSkuSnInfo.getGoodsSn()),"goods_sn",goodsSkuSnInfo.getGoodsSn());
        queryWrapper.eq(StringUtils.isNotEmpty(goodsSkuSnInfo.getBarcodeSn()),"barcode_sn",goodsSkuSnInfo.getBarcodeSn());
        if (ObjectUtil.isNotEmpty(goodsSkuSnInfo.getId()))
            queryWrapper.eq("id",goodsSkuSnInfo.getId());
        return this.baseMapper.selectOne(queryWrapper);
    }

    @Override
    public List<GoodsSkuSnInfo> selectGoodsSkuSnInfoList(GoodsSkuSnInfo goodsSkuSnInfo) {
        QueryWrapper<GoodsSkuSnInfo> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq(StringUtils.isNotEmpty(goodsSkuSnInfo.getCompanyCode()),"company_code", goodsSkuSnInfo.getCompanyCode());
        queryWrapper.eq(StringUtils.isNotEmpty(goodsSkuSnInfo.getSkuSn()),"sku_sn",goodsSkuSnInfo.getSkuSn());
        queryWrapper.eq(StringUtils.isNotEmpty(goodsSkuSnInfo.getGoodsSn()),"goods_sn",goodsSkuSnInfo.getGoodsSn());
        queryWrapper.eq(StringUtils.isNotEmpty(goodsSkuSnInfo.getBarcodeSn()),"barcode_sn",goodsSkuSnInfo.getBarcodeSn());
        queryWrapper.like(StringUtils.isNotEmpty(goodsSkuSnInfo.getGoodsName()),"goods_name",goodsSkuSnInfo.getGoodsName());
        queryWrapper.like(ObjectUtil.isNotEmpty(goodsSkuSnInfo.getIsGift()),"is_gift",goodsSkuSnInfo.getIsGift());
        queryWrapper.like(ObjectUtil.isNotEmpty(goodsSkuSnInfo.getIsFd()),"is_fd",goodsSkuSnInfo.getIsFd());
        queryWrapper.like(ObjectUtil.isNotEmpty(goodsSkuSnInfo.getIsPackage()),"is_package",goodsSkuSnInfo.getIsPackage());
        return this.list(queryWrapper);
    }

    @Override
    public int insertGoodsSkuSnInfo(GoodsSkuSnInfo goodsSkuSnInfo) {
        return 0;
    }

    @Override
    public int updateGoodsSkuSnInfo(GoodsSkuSnInfo goodsSkuSnInfo) {
        return this.baseMapper.updateById(goodsSkuSnInfo);
    }

    @Override
    public int deleteGoodsSkuSnInfoByIds(Long[] ids) {
        return this.baseMapper.deleteBatchIds(Arrays.asList(ids));
    }

    @Override
    public int deleteGoodsSkuSnInfoById(Long id) {
        return this.baseMapper.deleteById(id);
    }
}
