<template>
  <div class="exchange-product-picker">
    <div v-if="value" class="selected-product">
      <div class="selected-name">{{ product.name || '商品名称暂未加载' }}</div><div>{{ product.spec || '规格未提供' }}</div><div class="muted">OMS SKU：{{ value }}<span v-if="product.goodsSn"> · 货号 {{ product.goodsSn }}</span></div>
      <div class="stock-result"><strong>本次换出 {{ quantity }} 件</strong><template v-if="stock.status === 'REFERENCE'"><el-tag size="mini" :type="shortage ? 'warning' : 'success'">{{ shortage ? '参考缺口至少 ' + shortage + ' 件' : '数量参考满足' }}</el-tag><div class="muted">允许仓可用 {{ stock.allowed_warehouse_available }} · 渠道锁库 {{ stock.channel_locked_available }} · 本单仍预占 {{ stock.own_reserved }} 件</div><div class="muted">逻辑可预占参考 {{ stock.logical_capacity }} 件 · {{ stock.note }}</div><div class="muted">查询于 {{ String(stock.observed_at || '').replace('T', ' ').slice(0, 19) }}</div></template><div v-else-if="!disabled" class="muted">库存待查询，不代表零库存。</div><div v-else class="muted">已确认方案，库存与发货进度见下方。</div></div>
      <el-button v-if="!disabled" size="small" @click="open">更换商品</el-button><el-button v-if="inventoryQueryEnabled" v-hasPermi="['order:exchange:review','order:exchange:create']" type="text" size="small" :loading="loading" @click="refreshSelected">查询库存参考</el-button>
    </div>
    <el-button v-else aria-label="选择换出商品" :disabled="disabled || !hasContext" icon="el-icon-search" @click="open">选择换出商品</el-button>
    <el-dialog title="选择换出商品" :visible.sync="visible" width="min(1120px, 96vw)" append-to-body :close-on-click-modal="false">
      <el-alert type="info" :closable="false" title="按具体 SKU 选择颜色与尺码。缺货商品仍可保存方案；库存查询不预占、不改申请数量。" />
      <el-form inline size="small" class="search" @submit.native.prevent="search(true)"><el-form-item label="查找商品"><el-select v-model="field" style="width:150px"><el-option label="完整 SKU" value="skuSn" /><el-option label="商品名称" value="goodsName" /><el-option label="完整货号" value="goodsSn" /><el-option label="完整条码" value="barcodeSn" /></el-select></el-form-item><el-form-item><el-input v-model.trim="keyword" clearable :placeholder="field === 'goodsName' ? '输入商品名称关键词' : '输入完整编码'" style="width:320px" @keyup.enter.native="search(true)" /></el-form-item><el-form-item><el-button aria-label="查询" type="primary" icon="el-icon-search" :loading="loading" @click="search(true)">查询</el-button></el-form-item></el-form>
      <el-alert v-if="error" :title="error" type="error" :closable="false" /><el-alert v-else-if="stockError" :title="stockError" type="warning" :closable="false" />
      <div v-if="scope.length" class="muted">店铺允许仓：{{ scope.join('、') }}。同 SKU 跨仓及批次限制在实际分仓时校验。</div>
      <el-table v-loading="loading" :data="rows" row-key="id" :empty-text="searched ? '未找到符合条件的商品，请更换搜索条件' : '请输入搜索条件，不会自动加载全部商品'" max-height="430">
        <el-table-column label="商品 / 货号" min-width="220"><template slot-scope="s"><strong>{{ s.row.goodsName }}</strong><div class="muted">货号 {{ s.row.goodsSn || '—' }}</div><div class="muted">{{ s.row.barcodeSn ? '条码 ' + s.row.barcodeSn : '' }}</div></template></el-table-column>
        <el-table-column label="规格 / OMS SKU" min-width="230"><template slot-scope="s"><strong>{{ [s.row.colorName, s.row.sizeName].filter(Boolean).join(' / ') || '规格未提供' }}</strong><div class="sku">{{ s.row.skuSn }}</div><el-tag v-if="s.row.skuSn === originalSku" type="info" size="mini">与本次换回 SKU 相同</el-tag></template></el-table-column>
        <el-table-column label="库存参考" min-width="250"><template slot-scope="s"><template v-if="s.row.stock.status === 'REFERENCE'"><strong>{{ s.row.stock.reference_capacity }} 件</strong><el-tag size="mini" :type="Number(s.row.stock.reference_capacity) < quantity ? 'warning' : 'success'">{{ Number(s.row.stock.reference_capacity) < quantity ? '参考不足' : '参考满足' }}</el-tag><div class="muted">允许仓可用 {{ s.row.stock.allowed_warehouse_available }} · 本单预占 {{ s.row.stock.own_reserved }}</div></template><span v-else class="muted">库存待核对</span><div v-if="!s.row.selectable" class="warning">{{ s.row.selection_reason }}</div></template></el-table-column>
        <el-table-column label="操作" width="100"><template slot-scope="s"><el-button type="text" :disabled="!s.row.selectable || loading" @click="select(s.row)">选择此规格</el-button></template></el-table-column>
      </el-table><pagination v-show="total > 0" :total="total" :page.sync="page" :limit="10" :page-sizes="[10]" @pagination="search(false)" />
      <span slot="footer"><el-button @click="visible = false">取消</el-button></span>
    </el-dialog>
  </div>
</template>
<script>
import { exchangeGoodsSearch } from '@/api/order/exchange'
import { good } from './exchangePresentation'
export default {
  props: { value: String, goods: { type: Object, default: () => ({}) }, orderId: [String, Number], exchangeId: [String, Number], quantity: { type: Number, default: 1 }, originalSku: String, disabled: Boolean, inventoryQueryEnabled: { type: Boolean, default: true } },
  data() { return { visible: false, loading: false, keyword: '', field: 'skuSn', rows: [], total: 0, page: 1, searched: false, error: '', stockError: '', scope: [], selected: null, sequence: 0 } },
  computed: { hasContext() { return !!(this.orderId || this.exchangeId) }, product() { return good(this.selected || this.goods, this.value) }, stock() { return this.selected && this.selected.skuSn === this.value && this.selected.stock || {} }, shortage() { return Math.max(0, this.quantity - Number(this.stock.reference_capacity || 0)) } },
  watch: { value(v) { if (!this.selected || this.selected.skuSn !== v) this.selected = null }, exchangeId() { this.resetContext() }, orderId() { this.resetContext() } },
  methods: {
    context() { return this.exchangeId ? { exchange_id: this.exchangeId } : { order_id: this.orderId } },
    resetContext() { this.sequence++; this.selected = null; this.rows = []; this.scope = []; this.loading = false; this.visible = false },
    async open() { this.sequence++; this.loading = false; this.visible = true; this.field = 'skuSn'; this.keyword = this.value || ''; this.rows = []; this.error = ''; this.stockError = ''; this.total = 0; this.searched = false; if (this.keyword) await this.search(true) },
    async search(reset) { if (reset) this.page = 1; if (!this.keyword.trim()) { this.sequence++; this.loading = false; this.rows = []; this.total = 0; this.searched = false; return } const seq = ++this.sequence; this.loading = true; this.error = ''; this.stockError = ''; try { const d = (await exchangeGoodsSearch({ ...this.context(), field: this.field, keyword: this.keyword, pageNum: this.page, pageSize: 10 })).data; if (seq !== this.sequence) return; this.rows = d.rows || []; this.total = d.total; this.scope = d.warehouse_scope || []; this.stockError = d.stock_error || ''; this.searched = true } catch (_) { if (seq === this.sequence) { this.rows = []; this.total = 0; this.error = '商品查询失败，请重试'; this.searched = true } } finally { if (seq === this.sequence) this.loading = false } },
    select(row) { this.selected = row; this.$emit('input', row.skuSn); this.$emit('selected', row); this.visible = false },
    async refreshSelected() { if (!this.value || !this.hasContext) return; const seq = ++this.sequence; this.loading = true; try { const d = (await exchangeGoodsSearch({ ...this.context(), field: 'skuSn', keyword: this.value, pageNum: 1, pageSize: 10 })).data; if (seq !== this.sequence) return; this.selected = (d.rows || []).find(r => r.skuSn === this.value) || null; if (!this.selected) this.$message.warning('当前 SKU 未找到，请重新选择商品') } catch (_) { if (seq === this.sequence && this.selected) this.selected = { ...this.selected, stock: { status: 'UNKNOWN' } } } finally { if (seq === this.sequence) this.loading = false } }
  }
}
</script>
<style scoped>.selected-product{border:1px solid #dce6f0;border-radius:8px;padding:16px;background:#fff}.selected-name{font-size:15px;font-weight:600;color:#26364a;margin-bottom:6px}.muted{font-size:12px;color:#7b8798;line-height:1.7;overflow-wrap:anywhere}.sku{font-size:12px;overflow-wrap:anywhere;margin-top:5px}.stock-result{border-top:1px solid #edf0f5;padding-top:10px;margin-top:10px;line-height:1.8}.el-tag{margin-left:8px}.search{margin:18px 0 0}.warning{color:#a76b13;font-size:12px}</style>
