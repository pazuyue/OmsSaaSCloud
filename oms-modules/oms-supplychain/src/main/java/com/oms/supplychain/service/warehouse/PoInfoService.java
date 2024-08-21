package com.oms.supplychain.service.warehouse;

import com.baomidou.mybatisplus.extension.service.IService;
import com.oms.supplychain.model.entity.warehouse.PoInfo;

import java.util.List;

/**
 * <p>
 * 采购单主表 服务类
 * </p>
 *
 * @author 月光光
 * @since 2023-07-20
 */
public interface PoInfoService extends IService<PoInfo> {

    /**
     * 查询采购单
     *
     * @param id 采购单主键
     * @return 采购单
     */
    public PoInfo selectPoInfoById(Integer id);

    /**
     * 查询采购单列表
     *
     * @param poInfo 采购单
     * @return 采购单集合
     */
    public List<PoInfo> selectPoInfoList(PoInfo poInfo);

    /**
     * 新增采购单
     *
     * @param poInfo 采购单
     * @return 结果
     */
    public int insertPoInfo(PoInfo poInfo, String companyCode);

    /**
     * 修改采购单
     *
     * @param poInfo 采购单
     * @return 结果
     */
    public int updatePoInfo(PoInfo poInfo);

    /**
     * 删除采购单
     *
     * @param id 采购单主键
     * @return 结果
     */
    public boolean deletePoInfoById(Integer id);

    /**
     * 批量删除采购单
     *
     * @param ids 需要删除的数据主键集合
     * @return 结果
     */
    public boolean deletePoInfoByIds(Integer[] ids);

}
