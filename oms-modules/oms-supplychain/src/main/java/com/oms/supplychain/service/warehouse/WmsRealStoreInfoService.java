package com.oms.supplychain.service.warehouse;

import com.baomidou.mybatisplus.extension.service.IService;
import com.oms.supplychain.model.entity.warehouse.WmsRealStoreInfo;
import com.oms.supplychain.model.vo.warehouse.WmsRealStoreInfoVO;

import java.util.List;

/**
 * <p>
 * 实仓表 服务类
 * </p>
 *
 * @author 月光光
 * @since 2023-07-18
 */
public interface WmsRealStoreInfoService extends IService<WmsRealStoreInfo> {

    public boolean save(WmsRealStoreInfoVO vo,String companyCode);

    public WmsRealStoreInfo findOneByOwnerInfoVO(WmsRealStoreInfoVO vo);

    /**
     * 查询实仓
     *
     * @param id 实仓主键
     * @return 实仓
     */
    public WmsRealStoreInfo selectWmsRealStoreInfoById(Long id);

    /**
     * 查询实仓列表
     *
     * @param wmsRealStoreInfo 实仓
     * @return 实仓集合
     */
    public List<WmsRealStoreInfo> selectWmsRealStoreInfoList(WmsRealStoreInfoVO wmsRealStoreInfo);

    /**
     * 修改实仓
     *
     * @param wmsRealStoreInfo 实仓
     * @return 结果
     */
    public boolean updateWmsRealStoreInfo(WmsRealStoreInfoVO wmsRealStoreInfo);

    /**
     * 批量删除实仓
     *
     * @param ids 需要删除的实仓主键集合
     * @return 结果
     */
    public int deleteWmsRealStoreInfoByIds(Long[] ids);

    /**
     * 删除实仓信息
     *
     * @param id 实仓主键
     * @return 结果
     */
    public int deleteWmsRealStoreInfoById(Long id);
}
