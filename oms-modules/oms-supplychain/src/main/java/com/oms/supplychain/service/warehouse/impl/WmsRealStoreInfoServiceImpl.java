package com.oms.supplychain.service.warehouse.impl;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.oms.supplychain.mapper.warehouse.WmsRealStoreInfoMapper;
import com.oms.supplychain.model.entity.warehouse.WmsRealStoreInfo;
import com.oms.supplychain.model.vo.warehouse.*;
import com.oms.supplychain.service.warehouse.*;
import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;
@Service
public class WmsRealStoreInfoServiceImpl extends ServiceImpl<WmsRealStoreInfoMapper,WmsRealStoreInfo> implements WmsRealStoreInfoService {
    @Resource private WarehouseWorkspaceService workspace;
    public WmsRealStoreInfo findOneByOwnerInfoVO(WmsRealStoreInfoVO value){List<Map<String,Object>> rows=workspace.list(WarehouseCompany.current(),"realStore",WarehouseWorkspaceService.map("realStoreCode",value.getRealStoreCode()));return rows.isEmpty()?null:workspace.convert(rows.get(0),WmsRealStoreInfo.class);}
    public boolean save(WmsRealStoreInfoVO value,String company){workspace.save(WarehouseCompany.check(company),"realStore",workspace.asMap(value));return true;}

    public WmsRealStoreInfo selectWmsRealStoreInfoById(Long id){return workspace.convert(workspace.detail(WarehouseCompany.current(),"realStore",id.longValue()),WmsRealStoreInfo.class);}
    public List<WmsRealStoreInfo> selectWmsRealStoreInfoList(WmsRealStoreInfoVO value){return workspace.list(WarehouseCompany.current(),"realStore",workspace.asMap(value)).stream().map(r->workspace.convert(r,WmsRealStoreInfo.class)).collect(Collectors.toList());}
    public boolean updateWmsRealStoreInfo(WmsRealStoreInfoVO value){workspace.save(WarehouseCompany.current(),"realStore",workspace.asMap(value));return true;}
    public int deleteWmsRealStoreInfoByIds(Long[] ids){workspace.delete(WarehouseCompany.current(),"realStore",Arrays.stream(ids).map(Number::longValue).collect(Collectors.toList()));return ids.length;}
    public int deleteWmsRealStoreInfoById(Long id){return deleteWmsRealStoreInfoByIds(new Long[]{id});}
}
