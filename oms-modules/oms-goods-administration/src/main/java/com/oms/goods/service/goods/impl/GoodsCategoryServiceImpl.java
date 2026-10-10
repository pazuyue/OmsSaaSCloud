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

@Service public class GoodsCategoryServiceImpl implements GoodsCategoryService {
    @Resource private GoodsWorkspaceService workspace;
    public int save(GoodsCategory data,String company){workspace.saveMaster(GoodsCompany.check(company),"category",workspace.map(data));return 1;}
    public int deleteById(int id){return workspace.deleteMaster(GoodsCompany.current(),"category",Collections.singletonList((long)id));}
    public int deleteGoodsCategoryByIds(Integer[] ids){return workspace.deleteMaster(GoodsCompany.current(),"category",Arrays.stream(ids).map(Integer::longValue).collect(Collectors.toList()));}
    public Integer selectCategoryCode(String name){
        List<Map<String,Object>> rows=workspace.masterList(GoodsCompany.current(),"category",Collections.emptyMap()).stream().filter(r->name.equals(r.get("name")) && ((Number)r.get("level")).intValue()==3).collect(Collectors.toList());
        if(rows.size()!=1)throw new IllegalArgumentException("分类不存在或名称存在歧义，请使用完整分类路径");return ((Number)rows.get(0).get("id")).intValue();
    }
    public GoodsCategory selectGoodsCategoryById(Integer id){return workspace.convert(workspace.masterDetail(GoodsCompany.current(),"category",id),GoodsCategory.class);}
    public List<GoodsCategory> selectGoodsCategoryList(GoodsCategory filter){return workspace.masterList(GoodsCompany.current(),"category",workspace.map(filter)).stream().map(r->workspace.convert(r,GoodsCategory.class)).collect(Collectors.toList());}
    public int updateGoodsCategory(GoodsCategory data){workspace.saveMaster(GoodsCompany.current(),"category",workspace.map(data));return 1;}
}
