package com.oms.supplychain.service.warehouse;

import com.baomidou.mybatisplus.extension.service.IService;
import com.oms.supplychain.model.entity.warehouse.OwnerInfo;
import com.oms.supplychain.model.vo.warehouse.OwnerInfoVO;

import java.util.List;

public interface OwnerInfoService  extends IService<OwnerInfo> {

    public boolean save(OwnerInfoVO vo,String companyCode);

    public OwnerInfo findOneByOwnerInfoVO(OwnerInfoVO vo);

    public List<OwnerInfo> list(String owner_code, Integer page, Integer pageSize);
}
