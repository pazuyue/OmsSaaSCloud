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

@Service public class GoodsSkuSnInfoServiceImpl extends ServiceImpl<GoodsSkuSnInfoMapper,GoodsSkuSnInfo> implements GoodsSkuSnInfoService {
    @Resource private GoodsWorkspaceService workspace;
    public boolean toExamine(String batch,String company){workspace.confirm(GoodsCompany.check(company),batch,SecurityUtils.getUsername());return true;}
    public GoodsSkuSnInfo selectGoodsSkuSnInfoById(Long id){return workspace.convert(workspace.goodsDetail(GoodsCompany.current(),id),GoodsSkuSnInfo.class);}
    public GoodsSkuSnInfo selectGoodsSkuSnInfo(GoodsSkuSnInfo filter){List<GoodsSkuSnInfo> rows=selectGoodsSkuSnInfoList(filter);return rows.isEmpty()?null:rows.get(0);}
    public List<GoodsSkuSnInfo> selectGoodsSkuSnInfoList(GoodsSkuSnInfo filter){return workspace.goodsExport(GoodsCompany.current(),workspace.map(filter)).stream().map(r->workspace.convert(r,GoodsSkuSnInfo.class)).collect(Collectors.toList());}
    public int insertGoodsSkuSnInfo(GoodsSkuSnInfo data){workspace.saveGoods(GoodsCompany.current(),workspace.map(data),SecurityUtils.getUsername());return 1;}
    public int updateGoodsSkuSnInfo(GoodsSkuSnInfo data){workspace.saveGoods(GoodsCompany.current(),workspace.map(data),SecurityUtils.getUsername());return 1;}
    public int deleteGoodsSkuSnInfoByIds(Long[] ids){return workspace.deleteGoods(GoodsCompany.current(),Arrays.asList(ids));}
    public int deleteGoodsSkuSnInfoById(Long id){return workspace.deleteGoods(GoodsCompany.current(),Collections.singletonList(id));}
}
