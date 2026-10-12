<template>
  <div class="app-container order-settings">
    <el-tabs v-model="tab" @tab-click="changeTab">
      <el-tab-pane label="转单设置" name="conversion">
        <div class="toolbar"><p class="hint">按店铺维护转单、审单及逻辑预占策略。</p><el-button size="small" @click="load">刷新</el-button></div>
        <el-table v-loading="loading" :data="conversions">
          <el-table-column label="OMS 店铺" min-width="180"><template slot-scope="s">{{ s.row.channel_name }}<div class="hint">渠道 ID：{{ s.row.channel_id }}</div></template></el-table-column>
          <el-table-column label="自动转单" width="100"><template slot-scope="s"><el-tag size="small" :type="s.row.auto_convert ? 'success' : 'info'">{{ s.row.auto_convert ? '开启' : '关闭' }}</el-tag></template></el-table-column>
          <el-table-column label="允许交易类型" min-width="220"><template slot-scope="s">{{ s.row.allowed_trade_types.join('、') }}</template></el-table-column>
          <el-table-column label="审单方式" width="120"><template slot-scope="s">{{ s.row.review_mode === 'AUTO' ? '自动审核' : '人工审核' }}</template></el-table-column>
          <el-table-column label="人工匹配需复核" width="140"><template slot-scope="s">{{ s.row.manual_match_review ? '开启' : '关闭' }}</template></el-table-column>
          <el-table-column label="公共库存逻辑补占" width="150"><template slot-scope="s">{{ s.row.allow_public_stock ? '允许' : '不允许' }}</template></el-table-column>
          <el-table-column label="操作" width="110" fixed="right"><template slot-scope="s"><el-button type="text" @click="$refs.conversion.open(s.row)">转单设置</el-button></template></el-table-column>
        </el-table>
      </el-tab-pane>
      <el-tab-pane label="分仓规则" name="allocation">
        <div class="toolbar"><p class="hint">按店铺维护允许仓、优先顺序及多仓、部分发货策略；审核通过后在订单列表执行分仓。</p><el-button size="small" @click="load">刷新</el-button></div>
        <el-table v-loading="loading" :data="allocations">
          <el-table-column label="OMS 店铺" min-width="180"><template slot-scope="s">{{ s.row.channel_name }}<div class="hint">渠道 ID：{{ s.row.channel_id }}</div></template></el-table-column>
          <el-table-column label="规则状态" width="110"><template slot-scope="s"><el-tag size="small" :type="s.row.policy.enabled ? 'success' : 'info'">{{ !s.row.configured ? '未配置' : s.row.policy.enabled ? '已启用' : '已关闭' }}</el-tag></template></el-table-column>
          <el-table-column label="货主编码" min-width="130"><template slot-scope="s">{{ s.row.policy.owner_code || '—' }}</template></el-table-column>
          <el-table-column label="允许仓 / 默认顺序" min-width="200"><template slot-scope="s">{{ s.row.policy.warehouse_order.join(' → ') || '—' }}</template></el-table-column>
          <el-table-column label="省份优先规则" width="130"><template slot-scope="s">{{ Object.keys(s.row.policy.province_priorities).length }} 个省份</template></el-table-column>
          <el-table-column label="发货策略" min-width="230"><template slot-scope="s">{{ s.row.policy.allow_multi ? '允许多仓' : '单仓发货' }} · {{ s.row.policy.allow_partial ? '允许部分发货' : '整单发货' }}<div class="hint">{{ s.row.policy.allow_split_sku ? '同 SKU 可跨仓拆分' : '同 SKU 固定实体仓' }}</div></template></el-table-column>
          <el-table-column label="操作" width="110" fixed="right"><template slot-scope="s"><el-button type="text" @click="$refs.allocation.open(s.row)">配置规则</el-button></template></el-table-column>
        </el-table>
      </el-tab-pane>
      <el-tab-pane label="快递资料" name="carriers" lazy><order-carrier-settings v-if="tab === 'carriers'" kind="catalog" /></el-tab-pane>
      <el-tab-pane label="快递规则" name="carrier-rules" lazy><order-carrier-settings v-if="tab === 'carrier-rules'" kind="rules" /></el-tab-pane>
    </el-tabs>
    <p v-if="!loading && !conversions.length" class="hint">暂无已接入店铺，请先在<router-link to="/oms-order/management">拉单管理</router-link>完成店铺接入。</p>
    <order-conversion-config ref="conversion" @changed="load" />
    <order-allocation-config ref="allocation" @changed="load" />
  </div>
</template>
<script>
import { conversionConfigs, allocationConfigs } from '@/api/order'
import OrderConversionConfig from './OrderConversionConfig'
import OrderAllocationConfig from './OrderAllocationConfig'
import OrderCarrierSettings from './OrderCarrierSettings'
export default {
  name: 'OrderSettings',
  components: { OrderConversionConfig, OrderAllocationConfig, OrderCarrierSettings },
  data() { return { tab: 'conversion', loading: false, ready: false, conversions: [], allocations: [], handledKey: '' } },
  watch: { '$route.query': { handler() { this.applyRoute() }, deep: true } },
  created() { this.tab = this.routeTab(); this.load() },
  methods: {
    routeTab() { return ['allocation', 'carriers', 'carrier-rules'].includes(this.$route.query.tab) ? this.$route.query.tab : 'conversion' },
    async load() { this.loading = true; try { const [conversion, allocation] = await Promise.all([conversionConfigs(), allocationConfigs()]); this.conversions = conversion.data; this.allocations = allocation.data; this.ready = true; this.applyRoute() } finally { this.loading = false } },
    changeTab() { this.$refs.conversion.visible = false; this.$refs.allocation.visible = false; if (this.$route.query.tab !== this.tab || this.$route.query.channelId) this.$router.replace({ path: '/oms-order/settings', query: { tab: this.tab } }) },
    applyRoute() {
      this.tab = this.routeTab(); if (!this.ready) return;
      if (['carriers', 'carrier-rules'].includes(this.tab)) return;
      const id = this.$route.query.channelId; if (!id) { this.handledKey = ''; return }
      const key = `${this.tab}:${id}`; if (this.handledKey === key) return; this.handledKey = key;
      this.$refs.conversion.visible = false; this.$refs.allocation.visible = false;
      const row = (this.tab === 'allocation' ? this.allocations : this.conversions).find(s => String(s.channel_id) === String(id));
      if (row) this.$nextTick(() => this.$refs[this.tab].open(row)); else this.$message.warning('该店铺尚未接入，请先在拉单管理完成店铺接入');
    }
  }
}
</script>
<style scoped>
.toolbar{display:flex;align-items:center;justify-content:space-between;gap:16px;margin:12px 0 20px}.hint{font-size:13px;color:#8490a2;margin:6px 0}.hint a{color:#409eff}
</style>
