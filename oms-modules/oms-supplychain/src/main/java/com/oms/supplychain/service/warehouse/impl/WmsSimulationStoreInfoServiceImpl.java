package com.oms.supplychain.service.warehouse.impl;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.oms.supplychain.mapper.warehouse.WmsSimulationStoreInfoMapper;
import com.oms.supplychain.model.entity.warehouse.WmsSimulationStoreInfo;
import com.oms.supplychain.model.vo.warehouse.*;
import com.oms.supplychain.service.warehouse.*;
import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;
@Service
public class WmsSimulationStoreInfoServiceImpl extends ServiceImpl<WmsSimulationStoreInfoMapper,WmsSimulationStoreInfo> implements WmsSimulationStoreInfoService {
    @Resource private WarehouseWorkspaceService workspace;

    public int insertWmsSimulationStoreInfo(WmsSimulationStoreInfo value,String company){workspace.save(WarehouseCompany.check(company),"simulationStore",workspace.asMap(value));return 1;}
    public List<WmsSimulationStoreInfo> listSimulationStore(String company){return workspace.list(WarehouseCompany.check(company),"simulationStore",WarehouseWorkspaceService.map("effectiveEnabled",1)).stream().map(r->workspace.convert(r,WmsSimulationStoreInfo.class)).collect(Collectors.toList());}
 public com.oms.supplychain.model.dto.warehouse.SimulationStoreInfoDto getSimulationStoreInfoDto(String code){return workspace.resolve(WarehouseCompany.current(),code,false);}
    public WmsSimulationStoreInfo selectWmsSimulationStoreInfoById(Long id){return workspace.convert(workspace.detail(WarehouseCompany.current(),"simulationStore",id.longValue()),WmsSimulationStoreInfo.class);}
    public List<WmsSimulationStoreInfo> selectWmsSimulationStoreInfoList(WmsSimulationStoreInfo value){return workspace.list(WarehouseCompany.current(),"simulationStore",workspace.asMap(value)).stream().map(r->workspace.convert(r,WmsSimulationStoreInfo.class)).collect(Collectors.toList());}
    public boolean updateWmsSimulationStoreInfo(WmsSimulationStoreInfo value){workspace.save(WarehouseCompany.current(),"simulationStore",workspace.asMap(value));return true;}
    public int deleteWmsSimulationStoreInfoByIds(Long[] ids){workspace.delete(WarehouseCompany.current(),"simulationStore",Arrays.stream(ids).map(Number::longValue).collect(Collectors.toList()));return ids.length;}
    public int deleteWmsSimulationStoreInfoById(Long id){return deleteWmsSimulationStoreInfoByIds(new Long[]{id});}
}
