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

import com.oms.goods.model.vo.export.GoodsVO;
@Service public class GoodsSkuSnInfoTmpServiceImpl extends ServiceImpl<GoodsSkuSnInfoTmpMapper,GoodsSkuSnInfoTmp> implements GoodsSkuSnInfoTmpService {
    @Resource private GoodsWorkspaceService workspace;
    public String export(List<GoodsVO> rows,String company){return workspace.preview(GoodsCompany.check(company),rows,SecurityUtils.getUsername());}
    public String export(List<GoodsVO> rows,String batch,String company){return export(rows,company);}
    public List<GoodsSkuSnInfoTmp> exportList(String batch){
        List<GoodsSkuSnInfoTmp> rows=new ArrayList<>();
        for(Object item:workspace.previewRows(GoodsCompany.current(),batch,1,100,false).getRows())rows.add(workspace.convert(workspace.map(item),GoodsSkuSnInfoTmp.class));
        return rows;
    }
}
