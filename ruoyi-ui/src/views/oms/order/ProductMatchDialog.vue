<template>
  <el-dialog title="匹配 OMS 商品" :visible="visible" @close="close" width="min(1150px, 96vw)" append-to-body :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving">
    <el-alert title="仅替换当前订单明细的内部商品匹配结果，保留平台原始编码；已登记库存需求的明细不能直接换品。" type="info" show-icon :closable="false" />
    <p>平台订单：{{ order && order.platform_tid }}　已选择 {{ selected.length }} / {{ lines.length }} 行</p>
    <el-table :data="lines" max-height="390" border>
      <el-table-column label="平台商品 / 规格" min-width="225"><template slot-scope="s">{{ s.row.title || '未提供商品名称' }}<div class="muted">{{ s.row.platform_spec || '未提供规格' }}</div><div class="muted">子订单 {{ s.row.platform_oid }}</div></template></el-table-column>
      <el-table-column label="原商家编码" prop="outer_sku_id" min-width="150"><template slot-scope="s">{{ s.row.outer_sku_id || '缺失' }}</template></el-table-column>
      <el-table-column label="数量" prop="quantity" width="65" />
      <el-table-column label="本次匹配 OMS 商品" min-width="230"><template slot-scope="s"><template v-if="s.row.target"><strong>{{ s.row.target.skuSn }}</strong><div>{{ s.row.target.goodsName }}</div><div class="muted">{{ s.row.target.colorName || '—' }} / {{ s.row.target.sizeName || '—' }}</div></template><span v-else class="muted">尚未选择</span></template></el-table-column>
      <el-table-column label="操作" width="140"><template slot-scope="s"><el-button type="text" :disabled="saving" @click="openPicker(s.row)">{{ s.row.target ? '重新选择' : '选择商品' }}</el-button><el-button v-if="s.row.target" type="text" :disabled="saving" @click="s.row.target=null">清除</el-button></template></el-table-column>
    </el-table>
    <el-form label-width="85px" style="margin-top:20px"><el-form-item label="匹配原因" required><el-input v-model.trim="reason" :disabled="saving" type="textarea" :rows="2" maxlength="200" show-word-limit placeholder="说明为何将平台商品匹配到所选 OMS SKU" /></el-form-item></el-form>
    <p class="muted">未选择的明细继续保留异常。提交后仍会校验金额、收货信息和交易类型；库存不足时转单成功并等待逻辑预占。</p>
    <span slot="footer"><el-button :disabled="saving" @click="close">取消</el-button><el-button type="primary" :disabled="!selected.length || !reason" :loading="saving" @click="submit">确认匹配并重试</el-button></span>

    <el-dialog title="选择 OMS 现有商品" :visible.sync="pickerVisible" width="min(1050px, 94vw)" append-to-body :close-on-click-modal="false">
      <el-form inline size="small" @submit.native.prevent="search"><el-form-item label="SKU"><el-input v-model.trim="query.skuSn" clearable placeholder="输入完整 SKU" @keyup.enter.native="search" /></el-form-item><el-form-item label="商品名称"><el-input v-model.trim="query.goodsName" clearable placeholder="输入商品名称" @keyup.enter.native="search" /></el-form-item><el-form-item label="货号"><el-input v-model.trim="query.goodsSn" clearable placeholder="输入完整货号" @keyup.enter.native="search" /></el-form-item><el-form-item><el-button type="primary" @click="search">查询</el-button></el-form-item></el-form>
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <el-table v-loading="loading" :data="goods" max-height="420" border empty-text="未找到符合条件的普通商品，请调整查询条件">
        <el-table-column label="商品名称" prop="goodsName" min-width="200" /><el-table-column label="SKU" prop="skuSn" min-width="150" /><el-table-column label="货号" prop="goodsSn" min-width="130" /><el-table-column label="颜色" prop="colorName" width="95" /><el-table-column label="尺码" prop="sizeName" width="95" /><el-table-column label="操作" width="90"><template slot-scope="s"><el-button type="text" :disabled="!!Number(s.row.isFd) || !!Number(s.row.isPackage)" @click="select(s.row)">选择</el-button></template></el-table-column>
      </el-table>
      <pagination v-show="total" :total="total" :page.sync="query.pageNum" :limit.sync="query.pageSize" @pagination="load" />
      <span slot="footer"><el-button @click="pickerVisible=false">关闭</el-button></span>
    </el-dialog>
  </el-dialog>
</template>

<script>
import { listInfo } from '@/api/goods/info'
import { matchOrderProducts } from '@/api/order'
const queryDefaults = () => ({ skuSn: '', goodsName: '', goodsSn: '', pageNum: 1, pageSize: 10, isFd: 0, isPackage: 0 })
export default {
  name: 'OrderProductMatchDialog',
  props: { visible: Boolean, order: Object },
  data: () => ({ lines: [], reason: '', saving: false, pickerVisible: false, activeOid: '', query: queryDefaults(), goods: [], total: 0, loading: false, error: '', requestId: 0 }),
  computed: { selected() { return this.lines.filter(line => line.target) } },
  watch: { visible(value) { if (value) { const errors = (this.order.exception_details || []).filter(line => line.can_manual_match); this.lines = (this.order.lines || []).filter(line => errors.some(error => error.platform_oid === line.platform_oid)).map(line => ({ ...line, target: null })); this.reason = '' } else { this.pickerVisible = false; this.requestId++ } } },
  methods: {
    close() { if (!this.saving) this.$emit('update:visible', false) },
    openPicker(line) { this.activeOid = line.platform_oid; this.query = queryDefaults(); this.goods = []; this.pickerVisible = true; this.load() },
    search() { this.query.pageNum = 1; this.load() },
    async load() { const id = ++this.requestId; this.loading = true; this.error = ''; try { const result = await listInfo({ ...this.query }); if (id !== this.requestId) return; this.goods = result.rows; this.total = result.total } catch (_) { if (id === this.requestId) { this.goods = []; this.total = 0; this.error = '商品加载失败，请重试' } } finally { if (id === this.requestId) this.loading = false } },
    select(goods) { const line = this.lines.find(line => line.platform_oid === this.activeOid); if (line && !Number(goods.isFd) && !Number(goods.isPackage)) { line.target = goods; this.pickerVisible = false } },
    async submit() { if (this.saving || !this.selected.length || !this.reason) return; this.saving = true; try { await matchOrderProducts(this.order.id, { processing_hash: this.order.processing_hash, reason: this.reason, lines: this.selected.map(line => ({ platform_oid: line.platform_oid, sku_sn: line.target.skuSn })) }); this.$emit('matched', { id: this.order.id, platform_tid: this.order.platform_tid, submitted: true, conversion_status: 'PENDING' }); this.$emit('update:visible', false) } finally { this.saving = false } }
  }
}
</script>

<style scoped>
.muted{color:#8490a2;font-size:13px;line-height:1.7}.el-table{word-break:break-word}
</style>
