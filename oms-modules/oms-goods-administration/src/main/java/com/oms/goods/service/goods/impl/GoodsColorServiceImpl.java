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

@Service public class GoodsColorServiceImpl extends ServiceImpl<GoodsColorMapper,GoodsColor> implements GoodsColorService {
    @Resource private GoodsWorkspaceService workspace;
    public Integer selectOrSaveByColorName(String name,String company) {
        String scope=GoodsCompany.check(company);
        List<Map<String,Object>> rows=workspace.masterList(scope,"color",Collections.emptyMap()).stream().filter(r->name.equals(r.get("colorName"))).collect(Collectors.toList());
        if(rows.size()!=1)throw new IllegalArgumentException("请先维护唯一的Color基础资料："+name);
        return ((Number)rows.get(0).get("id")).intValue();
    }
    public GoodsColor selectGoodsColorById(Integer id){return workspace.convert(workspace.masterDetail(GoodsCompany.current(),"color",id),GoodsColor.class);}
    public List<GoodsColor> selectGoodsColorList(GoodsColor filter){return workspace.masterList(GoodsCompany.current(),"color",workspace.map(filter)).stream().map(r->workspace.convert(r,GoodsColor.class)).collect(Collectors.toList());}
    public int insertGoodsColor(GoodsColor data){workspace.saveMaster(GoodsCompany.current(),"color",workspace.map(data));return 1;}
    public int updateGoodsColor(GoodsColor data){workspace.saveMaster(GoodsCompany.current(),"color",workspace.map(data));return 1;}
    public int deleteGoodsColorByIds(Integer[] ids){return workspace.deleteMaster(GoodsCompany.current(),"color",Arrays.stream(ids).map(Integer::longValue).collect(Collectors.toList()));}
    public int deleteGoodsColorById(Integer id){return workspace.deleteMaster(GoodsCompany.current(),"color",Collections.singletonList(id.longValue()));}
}
