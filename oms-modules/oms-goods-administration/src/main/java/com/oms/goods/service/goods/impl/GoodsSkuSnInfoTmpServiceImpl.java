package com.oms.goods.service.goods.impl;

import cn.hutool.core.lang.Console;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.oms.goods.mapper.GoodsSkuSnInfoTmpMapper;
import com.oms.goods.model.entity.goods.GoodsSkuSnInfo;
import com.oms.goods.model.entity.goods.GoodsSkuSnInfoTmp;
import com.oms.goods.model.vo.export.GoodsVO;
import com.oms.goods.service.goods.GoodsSkuSnInfoTmpService;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.validation.ConstraintViolation;
import javax.validation.Validation;
import javax.validation.Validator;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
public class GoodsSkuSnInfoTmpServiceImpl extends ServiceImpl<GoodsSkuSnInfoTmpMapper, GoodsSkuSnInfoTmp> implements GoodsSkuSnInfoTmpService {

    @Override
    public String export(List<GoodsVO> list,String companyCode) {
        return this.saveGoodsSkuSnInfoTmp(list,companyCode);
    }

    @Override
    public String export(List<GoodsVO> list, String importBatch,String companyCode) {
        if (!ObjectUtil.isEmpty(importBatch))
        {
            QueryWrapper<GoodsSkuSnInfoTmp> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("import_batch",importBatch);
            this.remove(queryWrapper);
        }
        return this.saveGoodsSkuSnInfoTmp(list,companyCode);
    }

    @Override
    public List<GoodsSkuSnInfoTmp> exportList(String importBatch) {
        QueryWrapper<GoodsSkuSnInfoTmp> queryWrapper = Wrappers.query();
        queryWrapper.eq("import_batch",importBatch);
        return this.list(queryWrapper);
    }

    public String saveGoodsSkuSnInfoTmp(List<GoodsVO> list,String companyCode)
    {
        List<GoodsSkuSnInfoTmp> goodsSkuSnInfoTmpList = new ArrayList<>();
        String importBatch = IdUtil.simpleUUID();
        list.forEach(vo->{
            String s = this.checkGoodsVO(vo);
            GoodsSkuSnInfoTmp goodsSkuSnInfoTmp = new GoodsSkuSnInfoTmp();
            goodsSkuSnInfoTmp.setImportBatch(importBatch);
            goodsSkuSnInfoTmp.setSkuSn(vo.getSkuSn());
            goodsSkuSnInfoTmp.setGoodsSn(vo.getGoodsSn());
            goodsSkuSnInfoTmp.setBarcodeSn(vo.getBarcodeSn());
            goodsSkuSnInfoTmp.setGoodsName(vo.getGoodsName());
            goodsSkuSnInfoTmp.setCategoryCode(vo.getCategoryName());
            goodsSkuSnInfoTmp.setColorCode(vo.getColorName());
            goodsSkuSnInfoTmp.setSizeCode(vo.getSizeName());
            goodsSkuSnInfoTmp.setMarketPrice(vo.getMarketPrice());
            goodsSkuSnInfoTmp.setGoodsDesc(vo.getGoodsDesc());
            goodsSkuSnInfoTmp.setNotes(s);
            goodsSkuSnInfoTmp.setCompanyCode(companyCode);
            goodsSkuSnInfoTmp.setIsFd(vo.getIsFd());
            goodsSkuSnInfoTmp.setIsGift(vo.getIsGift());
            goodsSkuSnInfoTmpList.add(goodsSkuSnInfoTmp);
        });
        Console.log(goodsSkuSnInfoTmpList);
        this.saveBatch(goodsSkuSnInfoTmpList);
        return importBatch;
    }

    /**
     * 校验导入文件
     * @param vo
     * @return
     */
    public String checkGoodsVO(GoodsVO vo)
    {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        Set<ConstraintViolation<GoodsVO>> violations = validator.validate(vo);
        Console.log(violations);
        if (!violations.isEmpty()) {
            ArrayList<String> messages = new ArrayList<>();
            for (ConstraintViolation<GoodsVO> violation : violations) {
                String message = violation.getMessage(); // 获取错误消息
                messages.add(message);
            }
            return messages.toString();
        }
        return "正常";
    }
}
