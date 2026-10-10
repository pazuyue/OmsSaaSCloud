package com.oms.supplychain.service.warehouse.impl;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.oms.supplychain.mapper.warehouse.OwnerInfoMapper;
import com.oms.supplychain.model.entity.warehouse.OwnerInfo;
import com.oms.supplychain.model.vo.warehouse.*;
import com.oms.supplychain.service.warehouse.*;
import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;
@Service
public class OwnerInfoServiceImpl extends ServiceImpl<OwnerInfoMapper,OwnerInfo> implements OwnerInfoService {
    @Resource private WarehouseWorkspaceService workspace;
    public OwnerInfo findOneByOwnerInfo(OwnerInfo value){List<Map<String,Object>> rows=workspace.list(WarehouseCompany.current(),"owner",WarehouseWorkspaceService.map("ownerCode",value.getOwnerCode()));return rows.isEmpty()?null:workspace.convert(rows.get(0),OwnerInfo.class);}
    public int insertOwnerInfo(OwnerInfo value,String company){workspace.save(WarehouseCompany.check(company),"owner",workspace.asMap(value));return 1;}
    public List<OwnerInfo> listOwner(String company){return workspace.list(WarehouseCompany.check(company),"owner",WarehouseWorkspaceService.map("isEnable",2)).stream().map(r->workspace.convert(r,OwnerInfo.class)).collect(Collectors.toList());}
    public OwnerInfo selectOwnerInfoById(Integer id){return workspace.convert(workspace.detail(WarehouseCompany.current(),"owner",id.longValue()),OwnerInfo.class);}
    public List<OwnerInfo> selectOwnerInfoList(OwnerInfo value){return workspace.list(WarehouseCompany.current(),"owner",workspace.asMap(value)).stream().map(r->workspace.convert(r,OwnerInfo.class)).collect(Collectors.toList());}
    public boolean updateOwnerInfo(OwnerInfo value){workspace.save(WarehouseCompany.current(),"owner",workspace.asMap(value));return true;}
    public int deleteOwnerInfoByIds(Integer[] ids){workspace.delete(WarehouseCompany.current(),"owner",Arrays.stream(ids).map(Number::longValue).collect(Collectors.toList()));return ids.length;}
    public int deleteOwnerInfoById(Integer id){return deleteOwnerInfoByIds(new Integer[]{id});}
}
