<template>
  <div class="app-container">
    <goods-nav active="category" />
    <filter-panel :model="query" :primary-fields="['name']" v-show="showSearch">
      <el-form :model="query" :inline="true" size="small"><el-form-item label="分类名称"><el-input v-model="query.name" clearable placeholder="搜索分类，保留上级路径" @keyup.enter.native="load" /></el-form-item><el-form-item><el-button type="primary" icon="el-icon-search" @click="load">搜索</el-button><el-button @click="query.name = ''; load()">重置</el-button></el-form-item></el-form>
    </filter-panel>
    <el-alert title="商品关联三级分类；分类改名保留商品关联。含子分类或关联商品时不能删除。" type="info" :closable="false" show-icon class="mb8" />
    <el-row :gutter="10" class="mb8"><el-col :span="1.5"><el-button v-hasPermi="['goods:category:add']" type="primary" plain size="small" icon="el-icon-plus" @click="edit()">新增一级分类</el-button></el-col><el-col :span="1.5"><el-button size="small" @click="expand">{{ expanded ? '收起全部' : '展开全部' }}</el-button></el-col><el-col :span="1.5"><el-button v-hasPermi="['goods:category:export']" type="warning" plain size="small" icon="el-icon-download" @click="exportRows">导出筛选结果</el-button></el-col><right-toolbar :showSearch.sync="showSearch" @queryTable="load" /></el-row>
    <el-alert v-if="error" title="分类加载失败，请刷新重试" type="error" :closable="false" show-icon />
    <el-table :key="tableKey" ref="table" v-loading="loading" :data="tree" row-key="id" :default-expand-all="expanded" :tree-props="{ children: 'children' }" :empty-text="error ? '加载失败' : '暂无分类'">
      <el-table-column label="分类名称" prop="name" min-width="200" show-overflow-tooltip />
      <el-table-column label="层级" width="100"><template slot-scope="s">{{ s.row.level }} 级</template></el-table-column>
      <el-table-column label="完整路径" prop="path" min-width="240" show-overflow-tooltip />
      <el-table-column label="关联商品" width="130"><template slot-scope="s"><span>{{ s.row.goodsCount }} 个 </span><el-button v-hasPermi="['goods:info:list']" type="text" @click="goods(s.row)">查看</el-button></template></el-table-column>
      <el-table-column label="操作" width="210" :fixed="mobile ? false : 'right'"><template slot-scope="s"><el-button v-if="s.row.level < 3" v-hasPermi="['goods:category:add']" type="text" @click="edit(null, s.row)">新增下级</el-button><el-button v-hasPermi="['goods:category:edit']" type="text" @click="edit(s.row)">修改</el-button><el-button v-hasPermi="['goods:category:remove']" type="text" :disabled="s.row.goodsCount > 0 || !!(s.row.children && s.row.children.length)" @click="remove(s.row)">删除</el-button></template></el-table-column>
    </el-table>
    <el-dialog :title="form.id ? '修改商品分类' : '新增商品分类'" :visible.sync="dialog" width="560px" custom-class="workspace-form-dialog" append-to-body :close-on-click-modal="false" :before-close="close">
      <el-form ref="form" :model="form" :rules="rules" label-width="90px"><el-form-item label="上级分类" prop="pid"><treeselect v-model="form.pid" :options="parentOptions" :normalizer="normalizer" placeholder="选择上级分类" :clearable="false" /></el-form-item><el-form-item label="分类名称" prop="name"><el-input v-model="form.name" maxlength="100" show-word-limit /></el-form-item><el-form-item label="分类层级">{{ formLevel }} 级</el-form-item></el-form>
      <div slot="footer"><el-button :disabled="saving" @click="dialog = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></div>
    </el-dialog>
  </div>
</template>
<script>
import Treeselect from '@riophae/vue-treeselect'
import '@riophae/vue-treeselect/dist/vue-treeselect.css'
import GoodsNav from './GoodsNav'
import { listCategory, addCategory, updateCategory, delCategory } from '@/api/goods/category'
export default {
  components: { GoodsNav, Treeselect },
  data: () => ({ query: { name: '' }, rows: [], allRows: [], loading: false, error: false, seq: 0, showSearch: true, expanded: true, tableKey: 0, dialog: false, form: {}, saving: false, rules: { name: [{ required: true, whitespace: true, message: '请输入分类名称', trigger: 'blur' }], pid: [{ required: true, message: '请选择上级分类', trigger: 'change' }] } }),
  computed: {
    mobile() { return this.$store.state.app.device === 'mobile' },
    tree() { return this.handleTree(this.rows.map(r => ({ ...r })), 'id', 'pid') },
    parentOptions() { const excluded = new Set([this.form.id]); let changed = true; while (changed) { changed = false; this.allRows.forEach(r => { if (excluded.has(r.pid) && !excluded.has(r.id)) { excluded.add(r.id); changed = true } }) } const rows = this.allRows.filter(r => r.level < 3 && !excluded.has(r.id)); return [{ id: 0, name: '无上级（一级分类）' }, ...this.handleTree(rows.map(r => ({ ...r })), 'id', 'pid')] },
    formLevel() { const parent = this.allRows.find(r => r.id === this.form.pid); return parent ? parent.level + 1 : 1 }
  },
  created() { this.load() },
  activated() { this.load() },
  methods: {
    normalizer(node) { return { id: node.id, label: node.name, children: node.children && node.children.length ? node.children : undefined } },
    async load() { const seq = ++this.seq; this.loading = true; this.error = false; try { const r = await listCategory(this.query); if (seq !== this.seq) return; this.rows = r.rows; if (!this.query.name) this.allRows = r.rows; this.tableKey++ } catch (_) { if (seq === this.seq) { this.error = true; this.rows = [] } } finally { if (seq === this.seq) this.loading = false } },
    expand() { this.expanded = !this.expanded; this.tableKey++ },
    async edit(row, parent) { try { const r = await listCategory({}); this.allRows = r.rows; this.form = row ? { id: row.id, name: row.name, pid: row.pid || 0 } : { name: '', pid: parent ? parent.id : 0 }; this.dialog = true; this.$nextTick(() => this.$refs.form.clearValidate()) } catch (_) { /* Do not edit with incomplete choices. */ } },
    close(done) { if (!this.saving) done() },
    save() { if (this.saving) return; this.$refs.form.validate(async valid => { if (!valid) return; this.saving = true; try { await (this.form.id ? updateCategory(this.form) : addCategory(this.form)); this.$modal.msgSuccess('保存成功'); this.dialog = false; this.load() } catch (_) { /* Preserve form. */ } finally { this.saving = false } }) },
    async remove(row) { try { await this.$modal.confirm(`确认删除分类“${row.path}”？`); await delCategory(row.id); this.$modal.msgSuccess('删除成功'); this.load() } catch (_) { /* Cancel or guarded failure. */ } },
    goods(row) { this.$router.push({ path: '/oms-goods/info', query: { categoryCode: row.id } }) },
    exportRows() { this.download('goods/category/export', this.query, `goods_category_${Date.now()}.xlsx`) }
  }
}
</script>
