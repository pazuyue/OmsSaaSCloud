<template>
  <div class="app-container goods-workspace">
    <goods-nav active="info" />
    <filter-panel :model="query" :primary-fields="['goodsName', 'skuSn', 'categoryCode']" v-show="showSearch">
      <el-form ref="queryForm" :model="query" :inline="true" size="small" label-width="70px">
        <el-form-item label="商品名称" prop="goodsName"><el-input v-model="query.goodsName" clearable placeholder="搜索商品名称" @keyup.enter.native="search" /></el-form-item>
        <el-form-item label="SKU" prop="skuSn"><el-input v-model="query.skuSn" clearable placeholder="输入完整 SKU" @keyup.enter.native="search" /></el-form-item>
        <el-form-item label="分类" prop="categoryCode"><treeselect v-model="query.categoryCode" :options="categoryTree" :normalizer="normalizer" placeholder="选择分类（含下级）" class="category-query" /></el-form-item>
        <el-form-item class="filter-advanced" label="货号" prop="goodsSn"><el-input v-model="query.goodsSn" clearable placeholder="输入完整货号" @keyup.enter.native="search" /></el-form-item>
        <el-form-item class="filter-advanced" label="条形码" prop="barcodeSn"><el-input v-model="query.barcodeSn" clearable placeholder="输入完整条形码" @keyup.enter.native="search" /></el-form-item>
        <el-form-item class="filter-advanced" label="颜色" prop="colorCode"><el-select v-model="query.colorCode" filterable clearable placeholder="选择颜色"><el-option v-for="c in choices.color" :key="c.id" :label="c.colorName" :value="c.id" /></el-select></el-form-item>
        <el-form-item class="filter-advanced" label="尺码" prop="sizeCode"><el-select v-model="query.sizeCode" filterable clearable placeholder="选择尺码"><el-option v-for="s in choices.size" :key="s.id" :label="s.sizeName" :value="s.id" /></el-select></el-form-item>
        <el-form-item v-for="flag in flags" :key="flag.key" class="filter-advanced" :label="flag.label" :prop="flag.key"><el-select v-model="query[flag.key]" clearable placeholder="全部"><el-option label="是" :value="1" /><el-option label="否" :value="0" /></el-select></el-form-item>
        <el-form-item class="goods-query-actions"><el-button type="primary" icon="el-icon-search" @click="search">搜索</el-button><el-button @click="resetQuery">重置</el-button></el-form-item>
      </el-form>
    </filter-panel>
    <el-alert v-if="optionsError" title="分类、颜色或尺码加载失败，请刷新基础资料后重试" type="warning" :closable="false" show-icon class="mb8"><el-button type="text" @click="loadOptions">刷新基础资料</el-button></el-alert>
    <el-row :gutter="10" class="mb8">
      <el-col :span="1.5"><el-button v-hasPermi="['goods:info:add']" type="primary" plain icon="el-icon-plus" size="small" @click="edit()">新增商品</el-button></el-col>
      <el-col :span="1.5"><el-button v-hasPermi="['goods:info:import']" type="primary" plain icon="el-icon-upload2" size="small" @click="startImport">导入商品</el-button></el-col>
      <el-col :span="1.5"><el-button v-hasPermi="['goods:info:remove']" type="danger" plain icon="el-icon-delete" size="small" :disabled="!selected.length" @click="remove()">删除</el-button></el-col>
      <el-col :span="1.5"><el-button v-hasPermi="['goods:info:export']" type="warning" plain icon="el-icon-download" size="small" @click="exportRows">导出筛选结果</el-button></el-col>
      <right-toolbar :columns="columns" :showSearch.sync="showSearch" @queryTable="refresh" />
    </el-row>
    <el-alert v-if="listError" title="商品加载失败，请刷新重试" type="error" :closable="false" show-icon />
    <el-table v-loading="loading" :data="rows" row-key="id" @selection-change="selected = $event" :empty-text="listError ? '加载失败' : '暂无符合条件的商品'">
      <el-table-column type="selection" width="50" />
      <el-table-column label="商品 / SKU" min-width="240" :fixed="mobile ? false : 'left'"><template slot-scope="s"><el-button v-if="canDetail" type="text" class="goods-name" @click="openDetail(s.row)">{{ s.row.goodsName }}</el-button><span v-else>{{ s.row.goodsName }}</span><div>{{ s.row.skuSn }}</div></template></el-table-column>
      <el-table-column v-if="columns[7].visible" label="商品属性" min-width="240"><template slot-scope="s"><div class="attribute-list"><el-tag v-for="flag in displayFlags" :key="flag.key" :type="Number(s.row[flag.key]) === 1 ? flag.type : 'info'" size="small" effect="plain">{{ flag.name }}：{{ flagValue(s.row[flag.key]) }}</el-tag></div></template></el-table-column>
      <el-table-column v-if="columns[0].visible" label="货号" prop="goodsSn" min-width="150" show-overflow-tooltip />
      <el-table-column v-if="columns[1].visible" label="条形码" prop="barcodeSn" min-width="150" show-overflow-tooltip />
      <el-table-column v-if="columns[2].visible" label="分类路径" min-width="220" show-overflow-tooltip><template slot-scope="s">{{ s.row.categoryPath || missing(s.row.categoryCode) }}</template></el-table-column>
      <el-table-column v-if="columns[3].visible" label="颜色" min-width="100"><template slot-scope="s">{{ s.row.colorName || missing(s.row.colorCode) }}</template></el-table-column>
      <el-table-column v-if="columns[4].visible" label="尺码" min-width="90"><template slot-scope="s">{{ s.row.sizeName || missing(s.row.sizeCode) }}</template></el-table-column>
      <el-table-column v-if="columns[5].visible" label="市场价" width="110" align="right"><template slot-scope="s">{{ money(s.row.marketPrice) }}</template></el-table-column>
      <el-table-column v-if="columns[6].visible" label="保质期（天）" prop="validity" width="120" />
      <el-table-column v-if="columns[8].visible" label="修改时间" min-width="160"><template slot-scope="s">{{ parseTime(s.row.modifyTime) || '—' }}</template></el-table-column>
      <el-table-column label="操作" width="150" :fixed="mobile ? false : 'right'"><template slot-scope="s"><el-button v-hasPermi="['goods:info:query']" type="text" @click="openDetail(s.row)">详情</el-button><el-button v-hasPermi="['goods:info:edit']" type="text" @click="edit(s.row)">修改</el-button><el-button v-hasPermi="['goods:info:remove']" type="text" @click="remove(s.row)">删除</el-button></template></el-table-column>
    </el-table>
    <pagination v-show="total > 0" :total="total" :page.sync="query.pageNum" :limit.sync="query.pageSize" @pagination="navigateQuery" />

    <el-dialog :title="form.id ? '修改商品资料' : '新增商品资料'" :visible.sync="dialog" width="760px" custom-class="workspace-form-dialog" append-to-body :close-on-click-modal="false" :before-close="closeForm">
      <el-alert title="先维护分类、颜色和尺码，再选择对应资料。SKU 创建后不可修改，货号可供多个 SKU 共用。" type="info" :closable="false" class="mb8" />
      <el-form ref="form" :model="form" :rules="rules" label-width="100px">
        <div class="form-section-heading">基础资料</div>
        <el-form-item label="SKU" prop="skuSn"><el-input v-model="form.skuSn" :disabled="!!form.id" maxlength="30" show-word-limit /></el-form-item>
        <el-form-item label="货号" prop="goodsSn"><el-input v-model="form.goodsSn" maxlength="30" show-word-limit /></el-form-item>
        <el-form-item label="商品名称" prop="goodsName"><el-input v-model="form.goodsName" maxlength="100" show-word-limit /></el-form-item>
        <el-form-item label="条形码" prop="barcodeSn"><el-input v-model="form.barcodeSn" maxlength="30" placeholder="选填，英文数字、横线或下划线" /></el-form-item>
        <el-form-item label="商品分类" prop="categoryCode"><el-select v-model="form.categoryCode" filterable placeholder="选择三级分类" class="full-width"><el-option v-for="c in leafCategories" :key="c.id" :label="c.path" :value="c.id" /></el-select></el-form-item>
        <div class="form-section-heading">规格与属性</div>
        <el-form-item label="颜色" prop="colorCode"><el-select v-model="form.colorCode" filterable placeholder="选择颜色" class="full-width"><el-option v-for="c in choices.color" :key="c.id" :label="`${c.colorName} · ${c.outColorCode}`" :value="c.id" /></el-select></el-form-item>
        <el-form-item label="尺码" prop="sizeCode"><el-select v-model="form.sizeCode" filterable placeholder="选择尺码" class="full-width"><el-option v-for="s in choices.size" :key="s.id" :label="`${s.sizeName} · ${s.outSizeCode}`" :value="s.id" /></el-select></el-form-item>
        <el-form-item label="市场价" prop="marketPrice"><el-input-number v-model="form.marketPrice" :min="0" :precision="4" :controls="false" /></el-form-item>
        <el-form-item label="保质期（天）" prop="validity"><el-input-number v-model="form.validity" :min="0" :max="999999999" :precision="0" :controls="false" placeholder="选填" /></el-form-item>
        <el-form-item v-for="flag in flags" :key="flag.key" :label="flag.label" :prop="flag.key"><el-radio-group v-model="form[flag.key]"><el-radio :label="0">否</el-radio><el-radio :label="1">是</el-radio></el-radio-group></el-form-item>
        <div class="form-section-heading">补充信息</div>
        <el-form-item label="商品描述"><el-input v-model="form.goodsDesc" type="textarea" :rows="2" maxlength="10000" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="form.description" type="textarea" :rows="2" maxlength="10000" /></el-form-item>
      </el-form>
      <div slot="footer"><el-button :disabled="saving" @click="dialog = false">取消</el-button><el-button type="primary" :loading="saving" :disabled="optionsError" @click="save">保存商品</el-button></div>
    </el-dialog>

    <el-drawer title="商品资料详情" :visible.sync="drawer" :size="mobile ? '100%' : '680px'" append-to-body custom-class="goods-detail-drawer">
      <div v-loading="detailLoading" class="goods-detail"><el-alert v-if="detailError" title="详情加载失败，请关闭后重试" type="error" :closable="false" /><template v-else-if="detail"><h2>{{ detail.goodsName }}</h2><p class="muted">{{ detail.skuSn }}</p><el-descriptions :column="1" border><el-descriptions-item v-for="field in detailFields" :key="field.key" :label="field.label">{{ detail[field.key] === null || detail[field.key] === undefined || detail[field.key] === '' ? '—' : detail[field.key] }}</el-descriptions-item><el-descriptions-item v-for="flag in flags" :key="flag.key" :label="flag.label">{{ Number(detail[flag.key]) === 1 ? '是' : '否' }}</el-descriptions-item></el-descriptions><div class="detail-actions"><el-button v-hasPermi="['wmsInventory:inventory:list']" size="small" @click="stock(false)">商品库存</el-button><el-button v-hasPermi="['channelInventory:inventory:list']" size="small" @click="stock(true)">渠道库存</el-button></div></template></div>
    </el-drawer>

    <el-dialog title="导入商品" :visible.sync="upload.open" width="560px" custom-class="workspace-form-dialog" append-to-body :close-on-click-modal="false" :before-close="closeUpload">
      <el-steps :active="0" simple class="mb8"><el-step title="上传文件" /><el-step title="校验预览" /><el-step title="确认导入" /></el-steps>
      <el-alert title="分类使用完整路径，颜色和尺码须已维护。重复 SKU 或条码将报错；导入不会覆盖已有商品。" type="info" :closable="false" class="mb8" />
      <el-upload ref="upload" :limit="1" accept=".xls,.xlsx" :headers="upload.headers" :action="upload.url" :auto-upload="false" :disabled="upload.busy" :before-upload="beforeUpload" :on-change="fileChanged" :on-remove="fileChanged" :on-error="uploadError" :on-success="uploaded" drag><i class="el-icon-upload" /><div class="el-upload__text">将文件拖到此处，或<em>点击上传</em></div><div slot="tip" class="el-upload__tip">支持 xls、xlsx，最多 10 MB / 5000 行。<el-link type="primary" @click="template">下载模板</el-link></div></el-upload>
      <div slot="footer"><el-button :disabled="upload.busy" @click="upload.open = false">取消</el-button><el-button type="primary" :loading="upload.busy" :disabled="!upload.hasFile" @click="submitUpload">上传并校验</el-button></div>
    </el-dialog>
    <el-dialog title="商品导入校验预览" :visible.sync="upload.preview" width="92%" custom-class="goods-import-preview" append-to-body :close-on-click-modal="false" :before-close="closePreview">
      <el-steps :active="1" simple class="mb8"><el-step title="上传文件" /><el-step title="校验预览" /><el-step title="确认导入" /></el-steps>
      <el-alert v-if="upload.state" :type="upload.state.errorRows ? 'warning' : 'success'" :title="`共 ${upload.state.totalRows} 行，可导入 ${upload.state.totalRows - upload.state.errorRows} 行，错误 ${upload.state.errorRows} 行。${upload.state.errorRows ? '修正错误后重新上传，当前批次不会写入商品。' : '确认后整批新增商品。'}`" :closable="false" class="mb8" />
      <div class="preview-actions"><el-checkbox v-model="upload.query.errorsOnly" @change="previewSearch">仅看错误</el-checkbox><el-button v-if="upload.state && upload.state.errorRows" size="small" icon="el-icon-download" @click="downloadErrors">下载错误明细</el-button></div>
      <el-alert v-if="upload.error" title="预览加载失败，请重试" type="error" :closable="false"><el-button type="text" @click="loadPreview">重试</el-button></el-alert>
      <el-table v-loading="upload.loading" :data="upload.rows"><el-table-column label="原始行号" prop="rowNum" width="95" /><el-table-column label="SKU" prop="skuSn" min-width="150" /><el-table-column label="商品名称" prop="goodsName" min-width="180" show-overflow-tooltip /><el-table-column label="分类" prop="categoryName" min-width="220" show-overflow-tooltip /><el-table-column label="颜色" prop="colorName" width="100" /><el-table-column label="尺码" prop="sizeName" width="100" /><el-table-column label="市场价" prop="marketPrice" width="100" /><el-table-column label="保质期（天）" prop="validity" width="120" /><el-table-column label="校验结果" min-width="280"><template slot-scope="s"><span :class="{ 'import-error': s.row.notes !== '正常' }">{{ s.row.notes }}</span></template></el-table-column></el-table>
      <pagination v-show="upload.total > 0" :total="upload.total" :page.sync="upload.query.pageNum" :limit.sync="upload.query.pageSize" @pagination="loadPreview" />
      <div slot="footer"><el-button :disabled="upload.confirming" @click="upload.preview = false">关闭</el-button><el-button :disabled="upload.confirming" @click="reupload">修正后重新上传</el-button><el-button type="primary" :loading="upload.confirming" :disabled="!upload.state || upload.state.errorRows > 0 || upload.error || upload.loading || upload.state.status !== 'PREVIEW'" @click="confirmImport">确认导入 {{ upload.state ? upload.state.totalRows : '' }} 行商品</el-button></div>
    </el-dialog>
  </div>
</template>
<script>
import Treeselect from '@riophae/vue-treeselect'
import '@riophae/vue-treeselect/dist/vue-treeselect.css'
import GoodsNav from './GoodsNav'
import { listInfo, getInfo, addInfo, updateInfo, delInfo, goodsOptions, exportListInfo, importBatch, toExamine } from '@/api/goods/info'
import { getToken } from '@/utils/auth'
import { checkPermi } from '@/utils/permission'
const defaults = () => ({ pageNum: 1, pageSize: 10, goodsName: '', skuSn: '', goodsSn: '', barcodeSn: '', categoryCode: null, colorCode: null, sizeCode: null, isFd: null, isGift: null, isPackage: null })
export default {
  components: { GoodsNav, Treeselect },
  data: () => ({
    query: defaults(), rows: [], total: 0, selected: [], loading: false, listError: false, seq: 0, showSearch: true,
    choices: { category: [], color: [], size: [] }, optionsError: false, dialog: false, form: {}, saving: false,
    drawer: false, detail: null, detailLoading: false, detailError: false, detailSeq: 0,
    flags: [{ key: 'isFd', label: '是否福袋', name: '福袋' }, { key: 'isGift', label: '是否赠品', name: '赠品' }, { key: 'isPackage', label: '是否套装', name: '套装' }],
    columns: [{ key: 0, label: '货号', visible: true }, { key: 1, label: '条形码', visible: true }, { key: 2, label: '分类路径', visible: true }, { key: 3, label: '颜色', visible: true }, { key: 4, label: '尺码', visible: true }, { key: 5, label: '市场价', visible: true }, { key: 6, label: '保质期', visible: false }, { key: 7, label: '商品属性', visible: true }, { key: 8, label: '修改时间', visible: true }],
    detailFields: [{ key: 'goodsSn', label: '货号' }, { key: 'barcodeSn', label: '条形码' }, { key: 'categoryPath', label: '分类路径' }, { key: 'colorName', label: '颜色' }, { key: 'sizeName', label: '尺码' }, { key: 'marketPrice', label: '市场价' }, { key: 'validity', label: '保质期（天）' }, { key: 'goodsDesc', label: '商品描述' }, { key: 'description', label: '备注' }],
    rules: { skuSn: [{ required: true, whitespace: true, message: '请输入 SKU', trigger: 'blur' }, { pattern: /^\S{1,30}$/, message: 'SKU 最多 30 位，不能包含空白', trigger: 'blur' }], goodsSn: [{ required: true, whitespace: true, message: '请输入货号', trigger: 'blur' }, { pattern: /^\S{1,30}$/, message: '货号最多 30 位，不能包含空白', trigger: 'blur' }], goodsName: [{ required: true, whitespace: true, message: '请输入商品名称', trigger: 'blur' }], barcodeSn: [{ pattern: /^[A-Za-z0-9_-]{0,30}$/, message: '条码限英文数字、横线或下划线', trigger: 'blur' }], categoryCode: [{ required: true, message: '请选择三级分类', trigger: 'change' }], colorCode: [{ required: true, message: '请选择颜色', trigger: 'change' }], sizeCode: [{ required: true, message: '请选择尺码', trigger: 'change' }], marketPrice: [{ required: true, type: 'number', min: 0, message: '请输入非负市场价', trigger: 'change' }] },
    upload: { open: false, preview: false, busy: false, hasFile: false, headers: {}, url: process.env.VUE_APP_BASE_API + '/goods/goodsAdministration/import', rows: [], total: 0, state: null, loading: false, error: false, confirming: false, seq: 0, query: { import_batch: '', pageNum: 1, pageSize: 10, errorsOnly: false } }
  }),
  computed: { displayFlags() { return [{ key: 'isGift', name: '赠品', type: 'success' }, { key: 'isFd', name: '福袋', type: 'warning' }, { key: 'isPackage', name: '套装', type: '' }] }, canDetail() { return checkPermi(['goods:info:query']) }, mobile() { return this.$store.state.app.device === 'mobile' }, leafCategories() { return this.choices.category.filter(c => Number(c.level) === 3) }, categoryTree() { return this.handleTree(this.choices.category.map(c => ({ ...c })), 'id', 'pid') } },
  watch: { '$route.query': { handler() { if (this.$route.path !== '/oms-goods/info') return; this.readRoute(); this.load() } }, columns: { deep: true, handler(value) { try { localStorage.setItem('goods-info-columns', JSON.stringify(value.map(c => c.visible))) } catch (_) { /* Optional preference. */ } } } },
  created() { this.readRoute(); try { const saved = JSON.parse(localStorage.getItem('goods-info-columns')); if (Array.isArray(saved) && saved.length === this.columns.length) this.columns.forEach((c, i) => { c.visible = typeof saved[i] === 'boolean' ? saved[i] : c.visible }) } catch (_) { /* Use defaults. */ } this.refresh() },
  activated() { this.loadOptions() },
  methods: {
    normalizer(node) { return { id: node.id, label: node.name, children: node.children && node.children.length ? node.children : undefined } },
    flagValue(value) { if (value === null || value === undefined || value === '') return '待维护'; return Number(value) === 1 ? '是' : Number(value) === 0 ? '否' : '待维护' },
    missing(id) { return id ? `未维护（${id}）` : '—' }, money(value) { return value === null ? '—' : Number(value).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 4 }) },
    readRoute() { const q = defaults(); Object.keys(q).forEach(k => { const value = this.$route.query[k]; if (value !== undefined) q[k] = ['categoryCode', 'colorCode', 'sizeCode', 'isFd', 'isGift', 'isPackage', 'pageNum', 'pageSize'].includes(k) ? Number(value) || (k === 'pageNum' ? 1 : k === 'pageSize' ? 10 : 0) : String(value) }); this.query = q },
    async loadOptions() { try { const r = await goodsOptions(); this.choices = r.data; this.optionsError = false; return true } catch (_) { this.optionsError = true; return false } },
    refresh() { this.loadOptions(); this.load() },
    async load() { const seq = ++this.seq; this.loading = true; this.listError = false; try { const r = await listInfo(this.query); if (seq !== this.seq) return; this.rows = r.rows; this.total = r.total; this.selected = [] } catch (_) { if (seq === this.seq) { this.rows = []; this.total = 0; this.listError = true } } finally { if (seq === this.seq) this.loading = false } },
    navigateQuery() { const query = {}; Object.keys(this.query).forEach(key => { const value = this.query[key]; if (value !== null && value !== undefined && value !== '') query[key] = String(value) }); const old = this.$route.query; if (Object.keys(query).length === Object.keys(old).length && Object.keys(query).every(key => query[key] === String(old[key]))) this.load(); else this.$router.replace({ path: this.$route.path, query }) },
    search() { this.query.pageNum = 1; this.navigateQuery() }, resetQuery() { this.query = defaults(); if (Object.keys(this.$route.query).length) this.$router.replace({ path: this.$route.path, query: {} }); else this.load() },
    async edit(row) { if (!await this.loadOptions()) return; this.form = row ? { ...row, validity: row.validity === null || row.validity === '' ? undefined : Number(row.validity), marketPrice: row.marketPrice === null ? undefined : Number(row.marketPrice) } : { skuSn: '', goodsSn: '', goodsName: '', barcodeSn: '', categoryCode: null, colorCode: null, sizeCode: null, marketPrice: undefined, validity: undefined, isFd: 0, isGift: 0, isPackage: 0, goodsDesc: '', description: '' }; this.dialog = true; this.$nextTick(() => this.$refs.form.clearValidate()) },
    closeForm(done) { if (!this.saving) done() },
    save() { if (this.saving) return; this.$refs.form.validate(async valid => { if (!valid) return; this.saving = true; try { await (this.form.id ? updateInfo(this.form) : addInfo(this.form)); this.$modal.msgSuccess('商品保存成功'); this.dialog = false; this.load() } catch (_) { /* Preserve input. */ } finally { this.saving = false } }) },
    async remove(row) { const items = row ? [row] : this.selected; if (!items.length) return; try { await this.$modal.confirm(`确认删除 ${items.map(r => `${r.goodsName}（${r.skuSn}）`).join('、')}？有关联库存、分货或出入库记录的商品不能删除。`); await delInfo(items.map(r => r.id).join(',')); this.$modal.msgSuccess('删除成功'); this.load() } catch (_) { /* Cancel or reference guard. */ } },
    async openDetail(row) { const seq = ++this.detailSeq; this.drawer = true; this.detail = null; this.detailError = false; this.detailLoading = true; try { const r = await getInfo(row.id); if (seq === this.detailSeq) this.detail = r.data } catch (_) { if (seq === this.detailSeq) this.detailError = true } finally { if (seq === this.detailSeq) this.detailLoading = false } },
    stock(channel) { this.drawer = false; this.$router.push({ path: channel ? '/oms-inventory/channelInventory' : '/oms-inventory/productInventory', query: { skuSn: this.detail.skuSn } }) },
    exportRows() { this.download('goods/info/export', { ...this.query }, `goods_${Date.now()}.xlsx`) },
    startImport() { this.upload.headers = { Authorization: 'Bearer ' + getToken() }; this.upload.hasFile = false; this.upload.open = true; this.$nextTick(() => this.$refs.upload.clearFiles()) },
    fileChanged(file, files) { this.upload.hasFile = files.length > 0 },
    beforeUpload(file) { if (!/\.(xls|xlsx)$/i.test(file.name) || !file.size || file.size > 10 * 1024 * 1024) { this.$modal.msgError('请选择不超过 10 MB 的 xls 或 xlsx 文件'); this.upload.busy = false; return false } return true },
    submitUpload() { if (this.upload.busy || !this.upload.hasFile) return; this.upload.busy = true; this.$refs.upload.submit() },
    closeUpload(done) { if (!this.upload.busy) done() }, closePreview(done) { if (!this.upload.confirming) done() },
    uploadError() { this.upload.busy = false; this.$modal.msgError('上传失败，请重试') },
    async uploaded(response) { this.upload.busy = false; if (response.code !== 200) { this.$modal.msgError(response.msg || '导入失败'); return } this.upload.query = { import_batch: response.msg, pageNum: 1, pageSize: 10, errorsOnly: false }; this.upload.state = null; this.upload.open = false; this.upload.preview = true; this.loadPreview() },
    async loadPreview() { const seq = ++this.upload.seq; this.upload.loading = true; this.upload.error = false; try { const result = await Promise.all([exportListInfo(this.upload.query), importBatch(this.upload.query.import_batch)]); if (seq !== this.upload.seq) return; this.upload.rows = result[0].rows; this.upload.total = result[0].total; this.upload.state = result[1].data } catch (_) { if (seq === this.upload.seq) { this.upload.rows = []; this.upload.total = 0; this.upload.error = true } } finally { if (seq === this.upload.seq) this.upload.loading = false } },
    previewSearch() { this.upload.query.pageNum = 1; this.loadPreview() },
    reupload() { this.upload.preview = false; this.startImport() },
    async confirmImport() { if (this.upload.confirming || !this.upload.state || this.upload.state.errorRows) return; this.upload.confirming = true; try { const r = await toExamine({ import_batch: this.upload.query.import_batch }); this.upload.state = r.data; this.upload.preview = false; this.$alert(`已成功新增 ${r.data.totalRows} 个商品。`, '导入完成', { type: 'success' }); this.refresh() } catch (_) { /* Batch stays available for safe retry. */ } finally { this.upload.confirming = false } },
    template() { this.download('goods/goodsAdministration/importTemplate', {}, '商品导入模板.xlsx') },
    downloadErrors() { this.download('goods/goodsAdministration/errors', { import_batch: this.upload.query.import_batch }, `商品导入错误_${Date.now()}.xlsx`) }
  }
}
</script>
<style scoped>
.category-query { width:100%; font-size:13px; line-height:normal; }
.category-query ::v-deep .vue-treeselect__control { height:36px; padding-left:10px; border-color:#dfe4eb; border-radius:8px; }
.category-query ::v-deep .vue-treeselect__control:hover { border-color:#aebccd; }
.category-query.vue-treeselect--focused ::v-deep .vue-treeselect__control { border-color:#0071e3; box-shadow:0 0 0 3px #0071e310; }
.category-query ::v-deep .vue-treeselect__placeholder,
.category-query ::v-deep .vue-treeselect__single-value { line-height:34px; }
.goods-query-actions ::v-deep .el-form-item__content { display:flex; align-items:center; gap:10px; }
.goods-query-actions ::v-deep .el-button { height:36px; margin-left:0; }
.full-width { width:100%; }.goods-name { text-align:left; white-space:normal; line-height:1.4; }.attribute-list { display:flex; flex-wrap:wrap; gap:4px; }.goods-detail { padding:0 22px 24px; overflow-wrap:anywhere; }.goods-detail h2 { margin:0 0 8px; }.muted { color:#8391a5; }.detail-actions { margin-top:20px; display:flex; flex-wrap:wrap; gap:8px; }.preview-actions { display:flex; align-items:center; justify-content:space-between; margin:12px 0; }.import-error { color:#d93025; }
</style>
