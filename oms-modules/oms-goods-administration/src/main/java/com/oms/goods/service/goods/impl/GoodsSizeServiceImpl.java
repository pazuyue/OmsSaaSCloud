package com.oms.goods.service.goods.impl;
import com.oms.goods.service.goods.*;
import com.oms.goods.model.entity.goods.*;
import com.oms.goods.mapper.*;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ruoyi.common.security.utils.SecurityUtils;
import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;

@Service public class GoodsSizeServiceImpl extends ServiceImpl<GoodsSizeMapper,GoodsSize> implements GoodsSizeService {
    @Resource private GoodsWorkspaceService workspace;
    public Integer selectOrSaveBySizeName(String name,String company) {
        String scope=GoodsCompany.check(company);
        List<Map<String,Object>> rows=workspace.masterList(scope,"size",Collections.emptyMap()).stream().filter(r->name.equals(r.get("sizeName"))).collect(Collectors.toList());
        if(rows.size()!=1)throw new IllegalArgumentException("请先维护唯一的Size基础资料："+name);
        return ((Number)rows.get(0).get("id")).intValue();
    }
    public GoodsSize selectGoodsSizeById(Integer id){return workspace.convert(workspace.masterDetail(GoodsCompany.current(),"size",id),GoodsSize.class);}
    public List<GoodsSize> selectGoodsSizeList(GoodsSize filter){return workspace.masterList(GoodsCompany.current(),"size",workspace.map(filter)).stream().map(r->workspace.convert(r,GoodsSize.class)).collect(Collectors.toList());}
    public int insertGoodsSize(GoodsSize data){workspace.saveMaster(GoodsCompany.current(),"size",workspace.map(data));return 1;}
    public int updateGoodsSize(GoodsSize data){workspace.saveMaster(GoodsCompany.current(),"size",workspace.map(data));return 1;}
    public int deleteGoodsSizeByIds(Integer[] ids){return workspace.deleteMaster(GoodsCompany.current(),"size",Arrays.stream(ids).map(Integer::longValue).collect(Collectors.toList()));}
    public int deleteGoodsSizeById(Integer id){return workspace.deleteMaster(GoodsCompany.current(),"size",Collections.singletonList(id.longValue()));}
}
