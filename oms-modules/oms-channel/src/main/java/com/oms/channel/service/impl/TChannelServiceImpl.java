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
    /**
     * 查询店铺信息
     *
     * @param channelId 店铺信息主键
     * @return 店铺信息
     */
    @Override
    public TChannel selectTChannelByChannelId(Integer channelId)
    {
        return this.baseMapper.selectById(channelId);
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
        queryWrapper.ge(ObjectUtil.isNotEmpty(tChannel.getChannelName()),"channel_name",tChannel.getChannelName());
        queryWrapper.eq(ObjectUtil.isNotEmpty(tChannel.getChannelType()),"channel_type",tChannel.getChannelType());
        queryWrapper.eq(ObjectUtil.isNotEmpty(tChannel.getOutCorrelationCode()),"out_correlation_code",tChannel.getOutCorrelationCode());
        queryWrapper.eq(ObjectUtil.isNotEmpty(tChannel.getCompanyCode()),"company_code",tChannel.getCompanyCode());
        queryWrapper.eq(ObjectUtil.isNotEmpty(tChannel.getEnabled()),"enabled",tChannel.getEnabled());
        queryWrapper.eq(ObjectUtil.isNotEmpty(tChannel.getToChannelEnabled()),"to_channel_enabled",tChannel.getToChannelEnabled());
        queryWrapper.eq(ObjectUtil.isNotEmpty(tChannel.getSyncEnabled()),"sync_enabled",tChannel.getSyncEnabled());
        queryWrapper.eq(ObjectUtil.isNotEmpty(tChannel.getMModelType()),"m_model_type",tChannel.getMModelType());
        return this.list(queryWrapper);
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
        return this.baseMapper.insert(tChannel);
    }

    /**
     * 修改店铺信息
     *
     * @param tChannel 店铺信息
     * @return 结果
     */
    @Override
    public int updateTChannel(TChannel tChannel)
    {
        tChannel.setModifyTime(DateUtils.getNowDate());
        return this.baseMapper.updateById(tChannel);
    }

    /**
     * 批量删除店铺信息
     *
     * @param channelIds 需要删除的店铺信息主键
     * @return 结果
     */
    @Override
    public int deleteTChannelByChannelIds(Integer[] channelIds)
    {
        return this.baseMapper.deleteBatchIds(Arrays.asList(channelIds));
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
        return this.baseMapper.deleteById(channelId);
    }
}
