package com.oms.channel.service.impl;

import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.oms.channel.mapper.TChannelMapper;
import com.oms.channel.model.entity.TChannel;
import com.oms.channel.service.ITChannelService;
import com.ruoyi.common.core.utils.DateUtils;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

/**
 * 店铺信息Service业务层处理
 *
 * @author ruoyi
 * @date 2024-11-06
 */
@Service
public class TChannelServiceImpl extends ServiceImpl<TChannelMapper, TChannel> implements ITChannelService
{
    @javax.annotation.Resource private org.springframework.jdbc.core.JdbcTemplate db;
    private String company() {String c=com.ruoyi.common.security.utils.SecurityUtils.getLoginUser().getCompanyCode();if(c==null||c.isEmpty())throw new IllegalArgumentException("请先选择登录公司");return c;}
    /**
     * 查询店铺信息
     *
     * @param channelId 店铺信息主键
     * @return 店铺信息
     */
    @Override
    public TChannel selectTChannelByChannelId(Integer channelId)
    {
        TChannel row=this.getOne(new QueryWrapper<TChannel>().eq("channel_id",channelId).eq("company_code",company()));
        if(row==null)throw new IllegalArgumentException("店铺不存在或不属于当前公司");
        return row;
    }

    /**
     * 查询店铺信息列表
     *
     * @param tChannel 店铺信息
     * @return 店铺信息
     */
    @Override
    public List<TChannel> selectTChannelList(TChannel tChannel)
    {
        QueryWrapper<TChannel> queryWrapper = new QueryWrapper<>();
        queryWrapper.like(ObjectUtil.isNotEmpty(tChannel.getChannelName()),"channel_name",tChannel.getChannelName());
        queryWrapper.eq(ObjectUtil.isNotEmpty(tChannel.getChannelType()),"channel_type",tChannel.getChannelType());
        queryWrapper.eq(ObjectUtil.isNotEmpty(tChannel.getOutCorrelationCode()),"out_correlation_code",tChannel.getOutCorrelationCode());
        queryWrapper.eq("company_code",company());
        queryWrapper.eq(ObjectUtil.isNotEmpty(tChannel.getEnabled()),"enabled",tChannel.getEnabled());
        queryWrapper.eq(ObjectUtil.isNotEmpty(tChannel.getToChannelEnabled()),"to_channel_enabled",tChannel.getToChannelEnabled());
        queryWrapper.eq(ObjectUtil.isNotEmpty(tChannel.getSyncEnabled()),"sync_enabled",tChannel.getSyncEnabled());
        queryWrapper.eq(ObjectUtil.isNotEmpty(tChannel.getMModelType()),"m_model_type",tChannel.getMModelType());
        return this.list(queryWrapper.orderByDesc("channel_id"));
    }

    /**
     * 新增店铺信息
     *
     * @param tChannel 店铺信息
     * @return 结果
     */
    @Override
    public int insertTChannel(TChannel tChannel)
    {
        tChannel.setChannelId(null);
        tChannel.setCompanyCode(company());
        return this.baseMapper.insert(tChannel);
    }

    /**
     * 修改店铺信息
     *
     * @param tChannel 店铺信息
     * @return 结果
     */
    @Override
    @org.springframework.transaction.annotation.Transactional(rollbackFor=Exception.class)
    public int updateTChannel(TChannel tChannel)
    {
        TChannel old=selectTChannelByChannelId(tChannel.getChannelId());
        db.queryForList("SELECT channel_id FROM t_channel WHERE company_code=? AND channel_id=? FOR UPDATE",company(),tChannel.getChannelId());
        if(!java.util.Objects.equals(old.getChannelType(),tChannel.getChannelType())&&db.queryForObject("SELECT COUNT(*) FROM channel_platform_binding WHERE company_code=? AND channel_id=?",Integer.class,company(),tChannel.getChannelId())>0)throw new IllegalArgumentException("已配置平台对接的店铺不能更换平台");
        tChannel.setCompanyCode(company());
        return this.baseMapper.update(tChannel,new QueryWrapper<TChannel>().eq("company_code",company()).eq("channel_id",tChannel.getChannelId()));
    }

    /**
     * 批量删除店铺信息
     *
     * @param channelIds 需要删除的店铺信息主键
     * @return 结果
     */
    @Override
    @org.springframework.transaction.annotation.Transactional(rollbackFor=Exception.class)
    public int deleteTChannelByChannelIds(Integer[] channelIds)
    {
        for(Integer id:channelIds) {
            selectTChannelByChannelId(id);
            db.queryForList("SELECT channel_id FROM t_channel WHERE company_code=? AND channel_id=? FOR UPDATE",company(),id);
            if(db.queryForObject("SELECT COUNT(*) FROM channel_platform_binding WHERE company_code=? AND channel_id=?",Integer.class,company(),id)>0)throw new IllegalArgumentException("店铺已有平台对接配置，请停用店铺以保留授权和交互记录");
        }
        return this.baseMapper.delete(new QueryWrapper<TChannel>().eq("company_code",company()).in("channel_id",Arrays.asList(channelIds)));
    }

    /**
     * 删除店铺信息信息
     *
     * @param channelId 店铺信息主键
     * @return 结果
     */
    @Override
    public int deleteTChannelByChannelId(Integer channelId)
    {
        return deleteTChannelByChannelIds(new Integer[]{channelId});
    }
}
