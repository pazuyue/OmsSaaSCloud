package com.oms.supplychain.service.warehouse;

import com.baomidou.mybatisplus.extension.service.IService;
import com.oms.supplychain.model.entity.warehouse.WmsSimulationStoreInfo;

import java.util.List;

/**
 * <p>
 * 虚仓表 服务类
 * </p>
 *
 * @author 月光光
 * @since 2023-08-02
 */
public interface WmsSimulationStoreInfoService extends IService<WmsSimulationStoreInfo> {

    /**
     * 查询虚仓
     *
     * @param id 虚仓主键
     * @return 虚仓
     */
    public WmsSimulationStoreInfo selectWmsSimulationStoreInfoById(Long id);

    /**
     * 查询虚仓列表
     *
     * @param wmsSimulationStoreInfo 虚仓
     * @return 虚仓集合
     */
    public List<WmsSimulationStoreInfo> selectWmsSimulationStoreInfoList(WmsSimulationStoreInfo wmsSimulationStoreInfo);

    public List<WmsSimulationStoreInfo> listSimulationStore(String companyCode);
    /**
     * 新增虚仓
     *
     * @param wmsSimulationStoreInfo 虚仓
     * @return 结果
     */
    public int insertWmsSimulationStoreInfo(WmsSimulationStoreInfo wmsSimulationStoreInfo,String companyCode);

    /**
     * 修改虚仓
     *
     * @param wmsSimulationStoreInfo 虚仓
     * @return 结果
     */
    public boolean updateWmsSimulationStoreInfo(WmsSimulationStoreInfo wmsSimulationStoreInfo);

    /**
     * 批量删除虚仓
     *
     * @param ids 需要删除的虚仓主键集合
     * @return 结果
     */
    public int deleteWmsSimulationStoreInfoByIds(Long[] ids);

    /**
     * 删除虚仓信息
     *
     * @param id 虚仓主键
     * @return 结果
     */
    public int deleteWmsSimulationStoreInfoById(Long id);

}
