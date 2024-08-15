package com.oms.supplychain.service.warehouse;

import com.baomidou.mybatisplus.extension.service.IService;
import com.oms.supplychain.model.entity.warehouse.OwnerInfo;
import com.oms.supplychain.model.vo.warehouse.OwnerInfoVO;

import java.util.List;

public interface OwnerInfoService  extends IService<OwnerInfo> {

    public boolean save(OwnerInfoVO vo,String companyCode);

    public OwnerInfo findOneByOwnerInfoVO(OwnerInfoVO vo);

    /**
     * 查询货主基础信息
     *
     * @param id 货主基础信息主键
     * @return 货主基础信息
     */
    public OwnerInfo selectOwnerInfoById(Integer id);

    /**
     * 查询货主基础信息列表
     *
     * @param ownerInfo 货主基础信息
     * @return 货主基础信息集合
     */
    public List<OwnerInfo> selectOwnerInfoList(OwnerInfo ownerInfo);

    /**
     * 新增货主基础信息
     *
     * @param ownerInfo 货主基础信息
     * @return 结果
     */
    public int insertOwnerInfo(OwnerInfo ownerInfo);

    /**
     * 修改货主基础信息
     *
     * @param ownerInfo 货主基础信息
     * @return 结果
     */
    public boolean updateOwnerInfo(OwnerInfo ownerInfo);

    /**
     * 批量删除货主基础信息
     *
     * @param ids 需要删除的货主基础信息主键集合
     * @return 结果
     */
    public int deleteOwnerInfoByIds(Integer[] ids);

    /**
     * 删除货主基础信息信息
     *
     * @param id 货主基础信息主键
     * @return 结果
     */
    public int deleteOwnerInfoById(Integer id);
}
