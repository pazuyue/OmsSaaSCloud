<template>
  <div class="app-container order-settings">
    <el-tabs v-model="tab" @tab-click="changeTab">
      <el-tab-pane label="转单设置" name="conversion">
        <div class="toolbar"><p class="hint">按店铺维护转单及逻辑预占策略；审单方式在审单规则单独配置。</p><el-button size="small" @click="load">刷新</el-button></div>
        <el-table v-loading="loading" :data="conversions">
          <el-table-column label="OMS 店铺" min-width="180"><template slot-scope="s">{{ s.row.channel_name }}<div class="hint">渠道 ID：{{ s.row.channel_id }}</div></template></el-table-column>
          <el-table-column label="自动转单" width="100"><template slot-scope="s"><el-tag size="small" :type="s.row.auto_convert ? 'success' : 'info'">{{ s.row.auto_convert ? '开启' : '关闭' }}</el-tag></template></el-table-column>
          <el-table-column label="允许交易类型" min-width="220"><template slot-scope="s">{{ s.row.allowed_trade_types.join('、') }}</template></el-table-column>
          <el-table-column label="公共库存逻辑补占" width="150"><template slot-scope="s">{{ s.row.allow_public_stock ? '允许' : '不允许' }}</template></el-table-column>
          <el-table-column label="操作" width="110" fixed="right"><template slot-scope="s"><el-button type="text" @click="$refs.conversion.open(s.row)">转单设置</el-button></template></el-table-column>
        </el-table>
      </el-tab-pane>
      <el-tab-pane label="审单规则" name="review">
        <div class="toolbar"><p class="hint">按店铺维护自动审核、库存条件和人工复核条件；任意复核条件命中即转人工。</p><el-button size="small" @click="load">刷新</el-button></div>
        <el-table v-loading="loading" :data="reviews">
          <el-table-column label="OMS 店铺" min-width="180"><template slot-scope="s">{{ s.row.channel_name }}<div class="hint">渠道 ID：{{ s.row.channel_id }}</div></template></el-table-column>
          <el-table-column label="审单方式" min-width="180"><template slot-scope="s"><el-tag size="small" :type="s.row.policy.mode === 'AUTO' ? 'success' : 'info'">{{ s.row.policy.mode === 'AUTO' ? '按规则自动审核' : '人工审核' }}</el-tag><div class="hint">{{ s.row.configured ? '规则 v' + s.row.revision : '未配置，默认人工' }}</div></template></el-table-column>
          <el-table-column label="库存不足时" min-width="220"><template slot-scope="s"><span v-if="s.row.policy.mode !== 'AUTO'" class="hint">由人工审核决定</span><template v-else><el-tag size="small" :type="s.row.policy.stock_condition === 'FULL_RESERVED' ? 'warning' : 'success'">{{ s.row.policy.stock_condition === 'FULL_RESERVED' ? '等待库存满足' : '仍可自动审核' }}</el-tag><div class="hint">{{ s.row.policy.stock_condition === 'FULL_RESERVED' ? '整单逻辑预占完成后再自动审核' : '库存留到分仓环节检查' }}</div></template></template></el-table-column>
          <el-table-column label="转人工的情况" min-width="280"><template slot-scope="s"><span v-if="s.row.policy.mode !== 'AUTO'" class="hint">所有新订单均需人工确认</span><template v-else><div v-if="s.row.policy.manual_match_required">人工匹配过商品</div><div v-if="s.row.policy.amount_enabled">实付金额 ≥ ¥ {{ s.row.policy.amount_threshold }}</div><div v-if="s.row.policy.quantity_enabled">总件数 ≥ {{ s.row.policy.quantity_threshold }} 件</div><div v-if="s.row.policy.review_skus.length">包含指定 SKU（{{ s.row.policy.review_skus.length }} 个）</div><span v-if="!s.row.policy.manual_match_required && !s.row.policy.amount_enabled && !s.row.policy.quantity_enabled && !s.row.policy.review_skus.length" class="hint">未设置人工复核条件</span></template></template></el-table-column>
          <el-table-column label="操作" width="130" fixed="right"><template slot-scope="s"><el-button type="text" @click="$refs.review.open(s.row)">配置审单规则</el-button></template></el-table-column>
        </el-table>
      </el-tab-pane>
      <el-tab-pane label="分仓规则" name="allocation">
        <div class="toolbar"><p class="hint">按店铺配置执行方式、允许的虚拟仓和拆分限制；分仓生成发货单，实际包裹由仓库回传。</p><el-button size="small" @click="load">刷新</el-button></div>
        <el-table v-loading="loading" :data="allocations">
          <el-table-column label="OMS 店铺" min-width="180"><template slot-scope="s">{{ s.row.channel_name }}<div class="hint">渠道 ID：{{ s.row.channel_id }}</div></template></el-table-column>
          <el-table-column label="执行方式" min-width="170"><template slot-scope="s"><el-tag size="small" :type="s.row.policy.enabled ? 'success' : 'info'">{{ !s.row.configured ? '未配置' : !s.row.policy.enabled ? '已关闭' : s.row.policy.execution_mode === 'AUTO' ? '自动分仓' : '人工分仓' }}</el-tag><div class="hint">{{ s.row.configured ? '规则 v' + s.row.revision : '配置并启用后生效' }}</div><div v-if="s.row.policy.enabled && s.row.policy.execution_mode === 'AUTO'" class="hint">{{ s.row.policy.auto_replenish ? '余量自动补充' : '余量人工处理' }}</div></template></el-table-column>
          <el-table-column label="允许仓 / 默认顺序" min-width="260"><template slot-scope="s"><div class="hint">货主：{{ s.row.policy.owner_code || '未选择' }} · {{ s.row.policy.warehouse_order.length }} 个虚拟仓</div><div v-for="(code, index) in s.row.policy.warehouse_order.slice(0, 3)" :key="code" class="warehouse-code">{{ index + 1 }}. {{ code }}</div><div v-if="s.row.policy.warehouse_order.length > 3" class="hint">另 {{ s.row.policy.warehouse_order.length - 3 }} 个虚拟仓，进入配置查看</div><span v-if="!s.row.policy.warehouse_order.length" class="hint">未选择允许仓</span></template></el-table-column>
          <el-table-column label="选仓策略" min-width="190"><template slot-scope="s"><div>{{ s.row.policy.strategy === 'REGION_FIRST' ? '优先省份配置仓' : '优先整单' }}</div><div class="hint">省份优先规则：{{ Object.keys(s.row.policy.province_priorities).length }} 个</div><div class="hint">{{ s.row.policy.strategy === 'REGION_FIRST' ? '先省份顺序，再默认顺序' : '先争取整单，再按顺序择优' }}</div></template></el-table-column>
          <el-table-column label="库存不足 / 拆分" min-width="210"><template slot-scope="s"><div>{{ s.row.policy.allow_partial ? '允许部分分仓' : '剩余需求须全部满足' }}</div><div class="hint">{{ s.row.policy.allow_multi ? '允许多虚拟仓' : '限一个虚拟仓' }} · {{ s.row.policy.allow_multi && s.row.policy.allow_split_sku ? '同 SKU 可跨仓' : '同 SKU 固定仓' }}</div><div v-if="s.row.policy.allow_partial" class="hint">{{ s.row.policy.min_first_allocation_percent ? '首次至少满足 ' + s.row.policy.min_first_allocation_percent + '%' : '首次满足比例不限制' }}</div></template></el-table-column>
          <el-table-column label="订单累计上限" min-width="180"><template slot-scope="s"><div>{{ !s.row.policy.allow_multi ? '限 1 个虚拟仓' : s.row.policy.max_virtual_warehouses ? '最多 ' + s.row.policy.max_virtual_warehouses + ' 个虚拟仓' : '虚拟仓数不额外限制' }}</div><div>{{ s.row.policy.max_shipments ? '最多 ' + s.row.policy.max_shipments + ' 张发货单' : '发货单数不额外限制' }}</div><div class="hint">含后续补充，不是包裹数</div></template></el-table-column>
          <el-table-column label="操作" width="130" fixed="right"><template slot-scope="s"><el-button type="text" @click="$refs.allocation.open(s.row)">配置分仓规则</el-button></template></el-table-column>
        </el-table>
      </el-tab-pane>
      <el-tab-pane label="快递资料" name="carriers" lazy><order-carrier-settings v-if="tab === 'carriers'" kind="catalog" /></el-tab-pane>
      <el-tab-pane label="快递规则" name="carrier-rules" lazy><order-carrier-settings v-if="tab === 'carrier-rules'" kind="rules" /></el-tab-pane>
    </el-tabs>
    <p v-if="!loading && !conversions.length" class="hint">暂无已接入店铺，请先在<router-link to="/oms-order/management">订单与售后接入</router-link>完成店铺接入。</p>
    <order-review-config ref="review" @changed="load" />
    <order-conversion-config ref="conversion" @changed="load" />
    <order-allocation-config ref="allocation" @changed="load" />
  </div>
</template>
<script>
import { conversionConfigs, allocationConfigs, reviewConfigs } from '@/api/order'
import OrderConversionConfig from './OrderConversionConfig'
import OrderReviewConfig from './OrderReviewConfig'
import OrderAllocationConfig from './OrderAllocationConfig'
import OrderCarrierSettings from './OrderCarrierSettings'
export default {
  name: 'OrderSettings',
  components: { OrderReviewConfig, OrderConversionConfig, OrderAllocationConfig, OrderCarrierSettings },
  data() { return { tab: 'conversion', loading: false, ready: false, conversions: [], allocations: [], reviews: [], handledKey: '' } },
  watch: { '$route.query': { handler() { this.applyRoute() }, deep: true } },
  created() { this.tab = this.routeTab(); this.load() },
  methods: {
    routeTab() { return ['review', 'allocation', 'carriers', 'carrier-rules'].includes(this.$route.query.tab) ? this.$route.query.tab : 'conversion' },
    async load() { this.loading = true; try { const [conversion, allocation, review] = await Promise.all([conversionConfigs(), allocationConfigs(), reviewConfigs()]); this.reviews = review.data; this.conversions = conversion.data; this.allocations = allocation.data; this.ready = true; this.applyRoute() } finally { this.loading = false } },
    changeTab() { this.$refs.conversion.visible = false; this.$refs.allocation.visible = false; this.$refs.review.visible = false; if (this.$route.query.tab !== this.tab || this.$route.query.channelId) this.$router.replace({ path: '/oms-order/settings', query: { tab: this.tab } }) },
    applyRoute() {
      this.tab = this.routeTab(); if (!this.ready) return;
      if (['carriers', 'carrier-rules'].includes(this.tab)) return;
      const id = this.$route.query.channelId; if (!id) { this.handledKey = ''; return }
      const key = `${this.tab}:${id}`; if (this.handledKey === key) return; this.handledKey = key;
      this.$refs.conversion.visible = false; this.$refs.allocation.visible = false; this.$refs.review.visible = false;
      const row = (this.tab === 'review' ? this.reviews : this.tab === 'allocation' ? this.allocations : this.conversions).find(s => String(s.channel_id) === String(id));
      if (row) this.$nextTick(() => this.$refs[this.tab].open(row)); else this.$message.warning('该店铺尚未接入，请先在订单与售后接入完成店铺接入');
    }
  }
}
</script>
<style scoped>
.toolbar{display:flex;align-items:center;justify-content:space-between;gap:16px;margin:12px 0 20px}.hint{font-size:13px;color:#8490a2;margin:6px 0}.hint a{color:#409eff}.warehouse-code{font-size:12px;line-height:1.8;overflow-wrap:anywhere}
</style>
