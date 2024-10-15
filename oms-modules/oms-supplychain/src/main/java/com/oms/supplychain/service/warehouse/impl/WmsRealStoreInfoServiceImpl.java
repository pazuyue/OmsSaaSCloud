package com.oms.supplychain.service.warehouse.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.oms.supplychain.mapper.warehouse.WmsRealStoreInfoMapper;
import com.oms.supplychain.model.entity.warehouse.OwnerInfo;
import com.oms.supplychain.model.entity.warehouse.WmsRealStoreInfo;
import com.oms.supplychain.model.vo.warehouse.WmsRealStoreInfoVO;
import com.oms.supplychain.service.warehouse.WmsRealStoreInfoService;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Arrays;
import java.util.List;

/**
 * <p>
 * 实仓表 服务实现类
 * </p>
 *
 * @author 月光光
 * @since 2023-07-18
 */
@Service
public class WmsRealStoreInfoServiceImpl extends ServiceImpl<WmsRealStoreInfoMapper, WmsRealStoreInfo> implements WmsRealStoreInfoService {

    @Override
    public boolean save(WmsRealStoreInfoVO vo,String companyCode) {
        WmsRealStoreInfo infoVO = findOneByOwnerInfoVO(vo);
        if (!ObjectUtil.isEmpty(infoVO))
            throw new RuntimeException("实仓编码"+infoVO.getRealStoreCode()+"已存在");
        WmsRealStoreInfo wmsRealStoreInfo = new WmsRealStoreInfo();
        BeanUtil.copyProperties(vo,wmsRealStoreInfo);
        wmsRealStoreInfo.setCompanyCode(companyCode);
        if (this.save(wmsRealStoreInfo))
            return true;
        return false;
    }

    @Override
    public WmsRealStoreInfo findOneByOwnerInfoVO(WmsRealStoreInfoVO vo) {
        String realStoreCode = vo.getRealStoreCode();
        QueryWrapper<WmsRealStoreInfo> queryWrapper = new QueryWrapper<>();
        if (!StrUtil.isBlank(realStoreCode)){
            queryWrapper.eq("real_store_code",realStoreCode);
        }
        return this.getOne(queryWrapper);
    }

    @Override
    public WmsRealStoreInfo selectWmsRealStoreInfoById(Long id) {
        return this.baseMapper.selectById(id);
    }

    @Override
    public List<WmsRealStoreInfo> selectWmsRealStoreInfoList(WmsRealStoreInfoVO wmsRealStoreInfo) {
        QueryWrapper<WmsRealStoreInfo> queryWrapper = new QueryWrapper<>();
        if (!StrUtil.isBlank(wmsRealStoreInfo.getRealStoreCode())){
            queryWrapper.eq("real_store_code",wmsRealStoreInfo.getRealStoreCode());
        }
        if (!StrUtil.isBlank(wmsRealStoreInfo.getWmsName())){
            queryWrapper.like("wms_name",wmsRealStoreInfo.getWmsName());
        }
        if (!ObjectUtil.isEmpty(wmsRealStoreInfo.getWmsType())){
            queryWrapper.eq("wms_type",wmsRealStoreInfo.getWmsType());
        }

        queryWrapper.orderByDesc("modify_time");
        return this.list(queryWrapper);
    }

    @Override
    public boolean updateWmsRealStoreInfo(WmsRealStoreInfoVO vo) {
        WmsRealStoreInfo wmsRealStoreInfo = new WmsRealStoreInfo();
        BeanUtil.copyProperties(vo,wmsRealStoreInfo);
        return this.updateById(wmsRealStoreInfo);
    }

    @Override
    public int deleteWmsRealStoreInfoByIds(Long[] ids) {
        return this.baseMapper.deleteBatchIds(Arrays.asList(ids));
    }

    @Override
    public int deleteWmsRealStoreInfoById(Long id) {
        return this.baseMapper.deleteById(id);
    }
}
