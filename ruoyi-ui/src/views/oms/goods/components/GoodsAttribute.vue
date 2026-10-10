<template>
  <div class="app-container">
    <goods-nav :active="kind" />
    <filter-panel :model="query" :primary-fields="[nameKey, codeKey]" v-show="showSearch">
      <el-form ref="queryForm" :model="query" :inline="true" size="small" label-width="80px">
        <el-form-item :label="`${label}名称`" :prop="nameKey"><el-input v-model="query[nameKey]" clearable :placeholder="`搜索${label}名称`" @keyup.enter.native="search" /></el-form-item>
        <el-form-item label="外部编码" :prop="codeKey"><el-input v-model="query[codeKey]" clearable placeholder="搜索外部编码" @keyup.enter.native="search" /></el-form-item>
        <el-form-item><el-button type="primary" icon="el-icon-search" @click="search">搜索</el-button><el-button @click="resetQuery">重置</el-button></el-form-item>
      </el-form>
    </filter-panel>
    <el-alert :title="`${label}改名后保留商品关联；已关联商品的资料不能删除。${kind === 'size' ? '排序越小越靠前。' : ''}`" type="info" :closable="false" show-icon class="mb8" />
    <el-row :gutter="10" class="mb8">
      <el-col :span="1.5"><el-button v-hasPermi="[permission('add')]" type="primary" plain size="small" icon="el-icon-plus" @click="edit()">新增{{ label }}</el-button></el-col>
      <el-col :span="1.5"><el-button v-hasPermi="[permission('remove')]" type="danger" plain size="small" icon="el-icon-delete" :disabled="!selected.length" @click="remove()">删除</el-button></el-col>
      <el-col :span="1.5"><el-button v-hasPermi="[permission('export')]" type="warning" plain size="small" icon="el-icon-download" @click="exportRows">导出筛选结果</el-button></el-col>
      <right-toolbar :showSearch.sync="showSearch" @queryTable="load" />
    </el-row>
    <el-alert v-if="error" title="加载失败，请刷新重试" type="error" :closable="false" show-icon />
    <el-table v-loading="loading" :data="rows" row-key="id" @selection-change="selected = $event" :empty-text="error ? '加载失败' : '暂无符合条件的资料'">
      <el-table-column type="selection" width="50" />
      <el-table-column :label="`${label}名称`" :prop="nameKey" min-width="180" show-overflow-tooltip />
      <el-table-column label="外部编码" :prop="codeKey" min-width="150" show-overflow-tooltip />
      <el-table-column v-if="kind === 'size'" label="排序" prop="sortOrder" width="90" />
      <el-table-column label="关联商品" width="130"><template slot-scope="s"><span>{{ s.row.goodsCount }} 个 </span><el-button v-hasPermi="['goods:info:list']" type="text" @click="goods(s.row)">查看</el-button></template></el-table-column>
      <el-table-column label="修改时间" min-width="160"><template slot-scope="s">{{ parseTime(s.row.modifyTime) || '—' }}</template></el-table-column>
      <el-table-column label="操作" width="140" :fixed="mobile ? false : 'right'"><template slot-scope="s"><el-button v-hasPermi="[permission('edit')]" type="text" @click="edit(s.row)">修改</el-button><el-button v-hasPermi="[permission('remove')]" type="text" :disabled="s.row.goodsCount > 0" @click="remove(s.row)">删除</el-button></template></el-table-column>
    </el-table>
    <pagination v-show="total > 0" :total="total" :page.sync="query.pageNum" :limit.sync="query.pageSize" @pagination="load" />
    <el-dialog :title="`${form.id ? '修改' : '新增'}商品${label}`" :visible.sync="dialog" width="560px" custom-class="workspace-form-dialog" append-to-body :close-on-click-modal="false" :before-close="close">
      <el-form ref="form" :model="form" :rules="rules" label-width="90px">
        <el-form-item :label="`${label}名称`" :prop="nameKey"><el-input v-model="form[nameKey]" maxlength="30" show-word-limit /></el-form-item>
        <el-form-item label="外部编码" :prop="codeKey"><el-input v-model="form[codeKey]" maxlength="50" show-word-limit /></el-form-item>
        <el-form-item v-if="kind === 'size'" label="排序"><el-input-number v-model="form.sortOrder" :min="0" :max="999999" :precision="0" /></el-form-item>
      </el-form>
      <div slot="footer"><el-button :disabled="saving" @click="dialog = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></div>
    </el-dialog>
  </div>
</template>
<script>
import request from '@/utils/request'
import GoodsNav from './GoodsNav'
export default {
  components: { GoodsNav },
  props: { kind: { type: String, required: true } },
  data() { const name = `${this.kind}Name`, code = this.kind === 'color' ? 'outColorCode' : 'outSizeCode'; return { query: { pageNum: 1, pageSize: 10, [name]: '', [code]: '' }, rows: [], total: 0, loading: false, error: false, selected: [], showSearch: true, dialog: false, form: {}, saving: false, seq: 0 } },
  computed: {
    nameKey() { return `${this.kind}Name` }, codeKey() { return this.kind === 'color' ? 'outColorCode' : 'outSizeCode' }, label() { return this.kind === 'color' ? '颜色' : '尺码' }, mobile() { return this.$store.state.app.device === 'mobile' },
    rules() { return { [this.nameKey]: [{ required: true, whitespace: true, message: '请输入名称', trigger: 'blur' }], [this.codeKey]: [{ required: true, whitespace: true, message: '请输入外部编码', trigger: 'blur' }] } }
  },
  created() { this.load() },
  activated() { this.load() },
  methods: {
    permission(action) { return `goods:${this.kind}:${action}` },
    async load() { const seq = ++this.seq; this.loading = true; this.error = false; try { const r = await request({ url: `/goods/${this.kind}/list`, method: 'get', params: this.query }); if (seq !== this.seq) return; this.rows = r.rows; this.total = r.total; this.selected = [] } catch (_) { if (seq === this.seq) { this.rows = []; this.total = 0; this.error = true } } finally { if (seq === this.seq) this.loading = false } },
    search() { this.query.pageNum = 1; this.load() }, resetQuery() { this.resetForm('queryForm'); this.search() },
    edit(row) { this.form = row ? { ...row } : { [this.nameKey]: '', [this.codeKey]: '', sortOrder: 0 }; this.dialog = true; this.$nextTick(() => this.$refs.form.clearValidate()) },
    close(done) { if (!this.saving) done() },
    save() { if (this.saving) return; this.$refs.form.validate(async valid => { if (!valid) return; this.saving = true; try { await request({ url: `/goods/${this.kind}`, method: this.form.id ? 'put' : 'post', data: this.form }); this.$modal.msgSuccess('保存成功'); this.dialog = false; this.load() } catch (_) { /* Keep form for correction. */ } finally { this.saving = false } }) },
    async remove(row) { const items = row ? [row] : this.selected; if (!items.length) return; if (items.some(r => r.goodsCount > 0)) { this.$modal.msgWarning('选中资料已关联商品，不能删除'); return } try { await this.$modal.confirm(`确认删除${items.map(r => r[this.nameKey]).join('、')}？`); await request({ url: `/goods/${this.kind}/${items.map(r => r.id).join(',')}`, method: 'delete' }); this.$modal.msgSuccess('删除成功'); this.load() } catch (_) { /* Cancel or API error. */ } },
    goods(row) { this.$router.push({ path: '/oms-goods/info', query: { [`${this.kind}Code`]: row.id } }) },
    exportRows() { this.download(`goods/${this.kind}/export`, { ...this.query }, `goods_${this.kind}_${Date.now()}.xlsx`) }
  }
}
</script>
