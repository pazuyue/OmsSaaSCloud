package com.oms.supplychain.service.warehouse;

import com.baomidou.mybatisplus.extension.service.IService;
import com.oms.supplychain.model.entity.warehouse.OwnerInfo;
import com.oms.supplychain.model.entity.warehouse.SupplierInfo;

import java.util.List;

/**
 * <p>
 * 供应商主表 服务类
 * </p>
 *
 * @author 月光光
 * @since 2023-07-19
 */
public interface SupplierInfoService extends IService<SupplierInfo> {



    public SupplierInfo findOneBySupplierInfo(SupplierInfo vo);

    /**
     * 查询供应商主
     *
     * @param id 供应商主主键
     * @return 供应商主
     */
    public SupplierInfo selectSupplierInfoById(Integer id);

    /**
     * 查询供应商主列表
     *
     * @param supplierInfo 供应商主
     * @return 供应商主集合
     */
    public List<SupplierInfo> selectSupplierInfoList(SupplierInfo supplierInfo);

    public List<SupplierInfo> listSupplier(String companyCode);

    /**
     * 新增供应商主
     *
     * @param supplierInfo 供应商主
     * @return 结果
     */
    public int insertSupplierInfo(SupplierInfo supplierInfo, String companyCode);

    /**
     * 修改供应商主
     *
     * @param supplierInfo 供应商主
     * @return 结果
     */
    public int updateSupplierInfo(SupplierInfo supplierInfo);

    /**
     * 批量删除供应商主
     *
     * @param ids 需要删除的供应商主主键集合
     * @return 结果
     */
    public int deleteSupplierInfoByIds(Integer[] ids);

    /**
     * 删除供应商主信息
     *
     * @param id 供应商主主键
     * @return 结果
     */
    public int deleteSupplierInfoById(Integer id);
}
