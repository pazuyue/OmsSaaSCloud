<template>
  <div class="app-container warehouse-workspace">
    <div class="warehouse-nav"><el-button v-for="item in navigation" :key="item.kind" v-hasPermi="[item.permission + ':list']" size="small" :type="kind === item.kind ? 'primary' : ''" @click="go(item.kind)">{{ item.title }}</el-button></div>
    <div class="warehouse-nav"><el-button v-hasPermi="['warehouse:wms:config']" size="small" @click="$refs.connections.open()">仓库对接配置</el-button><el-button v-hasPermi="['warehouse:wms:log']" size="small" @click="$refs.logs.open()">仓库交互日志</el-button></div>
    <wms-connections ref="connections" @changed="loadConnectionChoices" /><wms-logs ref="logs" />
    <el-alert title="实体仓库与货主可多对多关联；每个虚仓固定归属一个货主与实仓组合。" type="info" :closable="false" show-icon class="relationship-tip" />
    <filter-panel v-show="showSearch" :model="query" :primary-fields="[config.name, config.code, config.status]">
      <el-form :model="query" inline size="small">
        <el-form-item :label="config.title + '名称'"><el-input v-model="query[config.name]" clearable placeholder="支持模糊搜索" @keyup.enter.native="search" /></el-form-item>
        <el-form-item :label="config.title + '编码'"><el-input v-model="query[config.code]" clearable placeholder="输入完整编码" @keyup.enter.native="search" /></el-form-item>
        <el-form-item label="启用状态"><el-select v-model="query[config.status]" clearable placeholder="全部"><el-option label="启用" :value="2" /><el-option label="停用" :value="1" /></el-select></el-form-item>
        <el-form-item v-if="kind !== 'realStore'" class="filter-advanced" label="实体仓库"><el-select v-model="query.realStoreId" filterable clearable placeholder="名称或编码"><el-option v-for="w in choices.realStore" :key="w.id" :value="w.id" :label="w.wmsName + ' · ' + w.realStoreCode" /></el-select></el-form-item>
        <el-form-item v-if="kind !== 'owner'" class="filter-advanced" label="货主"><el-select v-model="query.ownerId" filterable clearable placeholder="名称或编码"><el-option v-for="o in choices.owner" :key="o.id" :value="o.id" :label="o.ownerName + ' · ' + o.ownerCode" /></el-select></el-form-item>
        <el-form-item v-if="kind === 'realStore'" class="filter-advanced" label="仓库类型"><el-select v-model="query.wmsType" clearable placeholder="全部"><el-option v-for="(label, value) in warehouseTypes" :key="value" :label="label" :value="Number(value)" /></el-select></el-form-item>
        <el-form-item v-if="kind === 'simulationStore'" class="filter-advanced" label="业务可用"><el-select v-model="query.effectiveEnabled" clearable placeholder="全部"><el-option label="可用" :value="1" /><el-option label="不可用" :value="0" /></el-select></el-form-item>
        <el-form-item class="filter-advanced" label="修改开始日期"><el-date-picker v-model="query.beginTime" type="date" value-format="yyyy-MM-dd" placeholder="开始日期" /></el-form-item>
        <el-form-item class="filter-advanced" label="修改结束日期"><el-date-picker v-model="query.endTime" type="date" value-format="yyyy-MM-dd" placeholder="结束日期" /></el-form-item>
        <el-form-item class="query-actions"><el-button type="primary" icon="el-icon-search" @click="search">搜索</el-button><el-button @click="reset">重置</el-button></el-form-item>
      </el-form>
    </filter-panel>
    <el-alert v-if="optionsError" type="warning" title="关联资料加载失败，请刷新后再选择货主与仓库" :closable="false" class="mb8"><el-button type="text" @click="loadOptions">重试</el-button></el-alert>
    <el-row :gutter="10" class="mb8">
      <el-col :span="1.5"><el-button v-hasPermi="[config.permission + ':add']" type="primary" plain size="small" icon="el-icon-plus" @click="edit()">新增{{ config.title }}</el-button></el-col>
      <el-col :span="1.5"><el-button v-hasPermi="[config.permission + ':remove']" type="danger" plain size="small" icon="el-icon-delete" :disabled="!selected.length" @click="remove()">删除</el-button></el-col>
      <el-col :span="1.5"><el-button v-hasPermi="[config.permission + ':export']" plain size="small" icon="el-icon-download" @click="exportRows">导出筛选结果</el-button></el-col>
      <right-toolbar :showSearch.sync="showSearch" @queryTable="refresh" />
    </el-row>
    <el-alert v-if="listError" title="列表加载失败，请重试" type="error" :closable="false" />
    <el-table v-loading="loading" :data="rows" row-key="id" @selection-change="selected = $event" :empty-text="listError ? '加载失败' : '暂无符合条件的资料'">
      <el-table-column type="selection" width="48" :selectable="removable" />
      <el-table-column :label="config.title + ' / 编码'" min-width="220"><template slot-scope="s"><el-button v-if="can('query')" type="text" class="name-button" @click="showDetail(s.row)">{{ s.row[config.name] }}</el-button><span v-else>{{ s.row[config.name] }}</span><div class="muted">{{ s.row[config.code] }}</div></template></el-table-column>
      <el-table-column label="启用状态" width="100"><template slot-scope="s"><el-tag size="small" :type="Number(s.row[config.status]) === 2 ? 'success' : 'info'">{{ stateLabel(s.row[config.status]) }}</el-tag></template></el-table-column>
      <template v-if="kind === 'owner'">
        <el-table-column label="关联实体仓库" width="125"><template slot-scope="s"><el-button type="text" @click="manage(s.row)">{{ s.row.warehouseCount }} 个 · 查看</el-button></template></el-table-column>
        <el-table-column label="虚拟仓库" width="110"><template slot-scope="s"><el-button v-if="has('warehouse:simulationStoreInfo:list')" type="text" @click="go('simulationStore', { ownerId: s.row.id })">{{ s.row.virtualCount }} 个 · 查看</el-button><span v-else>{{ s.row.virtualCount }} 个</span></template></el-table-column>
      </template>
      <template v-if="kind === 'realStore'">
        <el-table-column label="仓库类型" width="105"><template slot-scope="s">{{ warehouseTypes[s.row.wmsType] || '待维护' }}</template></el-table-column>
        <el-table-column label="负责人 / 电话" min-width="155"><template slot-scope="s"><div>{{ s.row.director || '—' }}</div><div class="muted">{{ s.row.mobilePhone || '—' }}</div></template></el-table-column>
        <el-table-column label="地区" min-width="150" show-overflow-tooltip><template slot-scope="s">{{ [s.row.province, s.row.city, s.row.district].filter(Boolean).join(' / ') || '—' }}</template></el-table-column>
        <el-table-column label="关联资料" width="140"><template slot-scope="s"><el-button v-if="has('warehouse:owner:list')" type="text" @click="go('owner', { realStoreId: s.row.id })">货主 {{ s.row.ownerCount }}</el-button><el-button v-if="has('warehouse:simulationStoreInfo:list')" type="text" @click="go('simulationStore', { realStoreId: s.row.id })">虚仓 {{ s.row.virtualCount }}</el-button></template></el-table-column>
      </template>
      <template v-if="kind === 'simulationStore'">
        <el-table-column label="入库 / 出库方式" min-width="155"><template slot-scope="s"><div>入库：{{ modeLabel(s.row.inboundMode) }}</div><div class="muted">出库：{{ modeLabel(s.row.outboundMode) }}</div></template></el-table-column>
        <el-table-column label="所属货主" min-width="170"><template slot-scope="s"><div>{{ s.row.ownerName || '关联缺失' }}</div><span class="muted">{{ s.row.ownerCode }}</span></template></el-table-column>
        <el-table-column label="所属实体仓库" min-width="180"><template slot-scope="s"><div>{{ s.row.wmsName || '关联缺失' }}</div><span class="muted">{{ s.row.realStoreCode }}</span></template></el-table-column>
        <el-table-column label="业务可用" min-width="150"><template slot-scope="s"><el-tag :type="Number(s.row.effectiveEnabled) === 1 ? 'success' : 'warning'" size="small">{{ Number(s.row.effectiveEnabled) === 1 ? '可用' : '不可用' }}</el-tag><div v-if="Number(s.row.effectiveEnabled) !== 1" class="muted">{{ unavailableReason(s.row) }}</div></template></el-table-column>
      </template>
      <el-table-column label="修改时间" min-width="160"><template slot-scope="s">{{ parseTime(s.row.modifyTime) || '—' }}</template></el-table-column>
      <el-table-column label="操作" :width="kind === 'owner' ? 195 : 155" :fixed="mobile ? false : 'right'"><template slot-scope="s"><el-button v-hasPermi="[config.permission + ':edit']" type="text" @click="edit(s.row)">修改</el-button><el-button v-if="kind === 'owner'" v-hasPermi="['warehouse:owner:edit']" type="text" @click="manage(s.row)">关联仓库</el-button><el-button v-if="kind === 'simulationStore'" v-hasPermi="['wmsInventory:inventory:list']" type="text" @click="inventory(s.row)">库存</el-button><el-tooltip :disabled="removable(s.row)" content="已有下级关联，请先处理关联或使用停用"><span><el-button v-hasPermi="[config.permission + ':remove']" type="text" :disabled="!removable(s.row)" @click="remove(s.row)">删除</el-button></span></el-tooltip></template></el-table-column>
    </el-table>
    <pagination v-show="total > 0" :total="total" :page.sync="query.pageNum" :limit.sync="query.pageSize" @pagination="navigate" />

    <el-dialog :title="(form.id ? '修改' : '新增') + config.title" :visible.sync="dialog" width="740px" custom-class="workspace-form-dialog" append-to-body :close-on-click-modal="false" :before-close="closeForm">
      <el-alert v-if="kind === 'simulationStore'" title="虚仓归属创建后固定；库存迁移需通过调拨处理。" type="info" :closable="false" class="mb8" />
      <el-alert v-if="kind === 'owner' && !form.id" title="先建立货主，再关联一个或多个实体仓库。执行方式和平台对接由虚仓分别配置。" type="info" :closable="false" class="mb8" />
      <el-form ref="form" :model="form" :rules="rules" label-position="top">
        <div class="form-section-heading">基础资料</div>
        <el-form-item :label="config.title + '编码'" :prop="config.code"><el-input v-model="form[config.code]" :disabled="!!form.id" maxlength="64" placeholder="创建后不可修改" /></el-form-item>
        <el-form-item :label="config.title + '名称'" :prop="config.name"><el-input v-model="form[config.name]" maxlength="100" show-word-limit /></el-form-item>
        <el-form-item label="启用状态" :prop="config.status"><el-radio-group v-model="form[config.status]"><el-radio :label="2">启用</el-radio><el-radio :label="1">停用</el-radio></el-radio-group></el-form-item>
        <template v-if="kind === 'realStore'">
          <el-form-item label="仓库类型" prop="wmsType"><el-select v-model="form.wmsType"><el-option v-for="(label, value) in warehouseTypes" :key="value" :label="label" :value="Number(value)" /></el-select></el-form-item>
          <div class="form-section-heading">联系方式与地址</div>
          <el-form-item label="负责人"><el-input v-model="form.director" maxlength="100" /></el-form-item>
          <el-form-item label="联系电话" prop="mobilePhone"><el-input v-model="form.mobilePhone" maxlength="40" placeholder="手机号或固定电话" /></el-form-item>
          <el-form-item v-for="field in addressFields" :key="field.key" :label="field.label"><el-input v-model="form[field.key]" maxlength="255" /></el-form-item>
        </template>
        <template v-if="kind === 'simulationStore'">
          <div class="form-section-heading">执行方式</div>
          <el-form-item label="入库执行方式" prop="inboundMode"><el-select v-model="form.inboundMode"><el-option label="WMS 回传入库" :value="1" /><el-option label="自动虚拟入库" :value="2" /></el-select></el-form-item>
          <el-form-item label="出库执行方式" prop="outboundMode"><el-select v-model="form.outboundMode"><el-option label="WMS 回传出库" :value="1" /><el-option label="自动虚拟出库" :value="2" /></el-select><div class="muted">本期实现采购入库，出库方式仅保存配置。</div></el-form-item>
          <template v-if="form.inboundMode === 1 || form.outboundMode === 1">
            <el-form-item label="仓库对接配置" prop="connectionId" :rules="[{ required: true, message: '请选择对接配置', trigger: 'change' }]"><el-select v-model="form.connectionId" filterable placeholder="选择平台和账号"><el-option v-for="c in connectionChoices" :key="c.id" :value="c.id" :label="c.name + ' · ' + (c.provider === 'QIMEN' ? '奇门' : '京东虎符') + (c.enabled ? '' : '（停用）')" /></el-select></el-form-item>
            <el-form-item label="外部仓库编码" prop="externalWarehouse" :rules="[{ required: true, message: '请填写外部仓库编码', trigger: 'blur' }]"><el-input v-model.trim="form.externalWarehouse" maxlength="100" /></el-form-item>
            <el-form-item label="WMS 货主编码" prop="externalOwner" :rules="[{ required: true, message: '请填写WMS货主编码', trigger: 'blur' }]"><el-input v-model.trim="form.externalOwner" maxlength="100" /></el-form-item>
          </template>
          <div class="form-section-heading">固定归属</div>
          <el-form-item label="货主与实体仓库" prop="ownerWarehouseId"><el-select v-model="form.ownerWarehouseId" filterable :disabled="!!form.id" placeholder="搜索货主或实仓名称、编码"><el-option v-for="r in choices.ownerWarehouse" :key="r.id" :value="r.id" :label="relationLabel(r)" :disabled="Number(r.effectiveEnabled) !== 1" /></el-select></el-form-item>
          <div v-if="chosenRelation" class="relation-summary">货主：{{ chosenRelation.ownerName }}（{{ chosenRelation.ownerCode }}）<br>实体仓库：{{ chosenRelation.wmsName }}（{{ chosenRelation.realStoreCode }}）</div>
          <el-alert v-if="!form.id && !availableRelations.length" title="暂无可用关联，请先启用货主、实体仓库及它们的关联。" type="warning" :closable="false" class="mb8"><el-button v-hasPermi="['warehouse:owner:list']" type="text" @click="maintainOwners">前往维护货主与关联</el-button></el-alert>
        </template>
      </el-form>
      <div slot="footer"><el-button :disabled="saving" @click="dialog = false">取消</el-button><el-button type="primary" :loading="saving" :disabled="kind === 'simulationStore' && optionsError" @click="save">保存{{ config.title }}</el-button></div>
    </el-dialog>

    <el-dialog :title="'关联实体仓库 · ' + (relationOwner ? relationOwner.ownerName : '')" :visible.sync="relationsOpen" width="1000px" custom-class="workspace-form-dialog relation-dialog" append-to-body :close-on-click-modal="false">
      <el-alert title="关联只维护货主与实仓归属；执行方式和平台映射在虚仓配置。停用关联后，下属虚仓不可用于新业务。" type="info" :closable="false" class="mb8" />
      <el-button v-hasPermi="['warehouse:owner:edit']" type="primary" plain size="small" class="mb8" @click="editRelation()">新增仓库关联</el-button>
      <el-alert v-if="relationError" title="关联加载失败" type="error" :closable="false"><el-button type="text" @click="loadRelations">重试</el-button></el-alert>
      <el-table v-loading="relationLoading" :data="relations">
        <el-table-column label="实体仓库 / 编码" min-width="180"><template slot-scope="s"><div>{{ s.row.wmsName }}</div><span class="muted">{{ s.row.realStoreCode }}</span></template></el-table-column>
        <el-table-column label="关联状态" width="100"><template slot-scope="s">{{ stateLabel(s.row.status) }}</template></el-table-column>
        <el-table-column label="仓库状态" width="100"><template slot-scope="s">{{ stateLabel(s.row.warehouseStatus) }}</template></el-table-column>
        <el-table-column label="虚仓数" width="75" prop="virtualCount" />
        <el-table-column label="操作" min-width="170"><template slot-scope="s"><el-button v-hasPermi="['warehouse:owner:edit']" type="text" @click="editRelation(s.row)">修改</el-button><el-button v-if="has('warehouse:simulationStoreInfo:list')" type="text" @click="relatedVirtuals(s.row)">查看虚仓</el-button><el-button v-hasPermi="['warehouse:owner:edit']" type="text" :disabled="s.row.virtualCount > 0" @click="removeRelation(s.row)">解除</el-button></template></el-table-column>
      </el-table>
      <div slot="footer"><el-button @click="relationsOpen = false">关闭</el-button></div>
    </el-dialog>
    <el-dialog :title="relationForm.id ? '修改仓库关联' : '新增仓库关联'" :visible.sync="relationFormOpen" width="660px" custom-class="workspace-form-dialog" append-to-body :close-on-click-modal="false" :before-close="closeRelation">
      <el-form ref="relationForm" :model="relationForm" :rules="relationRules" label-position="top">
        <el-form-item label="实体仓库" prop="realStoreId"><el-select v-model="relationForm.realStoreId" filterable :disabled="!!relationForm.id" placeholder="名称或编码"><el-option v-for="w in choices.realStore" :key="w.id" :label="w.wmsName + ' · ' + w.realStoreCode + (Number(w.status) === 2 ? '' : '（停用）')" :value="w.id" :disabled="!relationForm.id && relations.some(r => Number(r.realStoreId) === Number(w.id))" /></el-select></el-form-item>
        <el-form-item label="关联状态"><el-radio-group v-model="relationForm.status"><el-radio :label="2">启用</el-radio><el-radio :label="1">停用</el-radio></el-radio-group></el-form-item>
      </el-form>
      <div slot="footer"><el-button :disabled="relationSaving" @click="relationFormOpen = false">取消</el-button><el-button type="primary" :loading="relationSaving" @click="saveRelation">保存关联</el-button></div>
    </el-dialog>
    <el-drawer :title="config.title + '详情'" :visible.sync="drawer" :size="mobile ? '100%' : '620px'" append-to-body><div v-loading="detailLoading" class="warehouse-detail"><el-alert v-if="detailError" title="详情加载失败，请关闭后重试" type="error" :closable="false" /><template v-else-if="detail"><h2>{{ detail[config.name] }}</h2><p class="muted">{{ detail[config.code] }}</p><el-descriptions :column="1" border><el-descriptions-item v-for="field in detailFields" :key="field.key" :label="field.label">{{ displayDetail(field.key) }}</el-descriptions-item></el-descriptions><div class="detail-links"><el-button v-if="kind === 'owner'" size="small" @click="drawer = false; manage(detail)">查看关联仓库</el-button><el-button v-if="kind === 'simulationStore'" v-hasPermi="['wmsInventory:inventory:list']" size="small" @click="drawer = false; inventory(detail)">查看库存</el-button></div></template></div></el-drawer>
  </div>
</template>
<script>
import { warehouseList, warehouseDetail, warehouseSave, warehouseDelete, warehouseOptions } from '@/api/warehouse/workspace'
import WmsConnections from './WmsConnections'
import WmsLogs from './WmsLogs'
import { connections } from '@/api/warehouse/integration'
import { checkPermi } from '@/utils/permission'
const configs = {
  owner: { kind: 'owner', title: '货主', code: 'ownerCode', name: 'ownerName', status: 'isEnable', permission: 'warehouse:owner', route: 'owner' },
  realStore: { kind: 'realStore', title: '实体仓库', code: 'realStoreCode', name: 'wmsName', status: 'status', permission: 'warehouse:WmsRealStoreInfo', route: 'wmsRealStore' },
  simulationStore: { kind: 'simulationStore', title: '虚拟仓库', code: 'wmsSimulationCode', name: 'wmsSimulationName', status: 'status', permission: 'warehouse:simulationStoreInfo', route: 'simulationStore' }
}
const defaults = kind => { const c = configs[kind]; const q = { pageNum: 1, pageSize: 10, [c.code]: '', [c.name]: '', [c.status]: null, beginTime: null, endTime: null }; if (kind !== 'realStore') q.realStoreId = null; if (kind !== 'owner') q.ownerId = null; if (kind === 'realStore') { q.wmsType = null;  } if (kind === 'simulationStore') { q.effectiveEnabled = null; q.ownerWarehouseId = null } return q }
export default {
  components: { WmsConnections, WmsLogs },
  props: { kind: { type: String, required: true } },
  data() { return { query: defaults(this.kind), rows: [], total: 0, selected: [], showSearch: true, loading: false, listError: false, seq: 0, choices: { owner: [], realStore: [], ownerWarehouse: [] }, optionsError: false, dialog: false, form: {}, saving: false, drawer: false, detail: null, detailLoading: false, detailError: false, detailSeq: 0, relationsOpen: false, relationOwner: null, relations: [], relationLoading: false, relationError: false, relationSeq: 0, relationFormOpen: false, relationForm: {}, relationSaving: false, warehouseTypes: { 1: '电商仓', 2: '门店仓', 3: '零售仓' }, addressFields: [{ key: 'province', label: '省' }, { key: 'city', label: '市' }, { key: 'district', label: '区' }, { key: 'address', label: '详细地址' }], relationRules: { realStoreId: [{ required: true, message: '请选择实体仓库', trigger: 'change' }] }, connectionChoices: [] } },
  computed: {
    config() { return configs[this.kind] }, navigation() { return [configs.realStore, configs.owner, configs.simulationStore] }, mobile() { return this.$store.state.app.device === 'mobile' },
    availableRelations() { return this.choices.ownerWarehouse.filter(r => Number(r.effectiveEnabled) === 1) }, chosenRelation() { return this.choices.ownerWarehouse.find(r => Number(r.id) === Number(this.form.ownerWarehouseId)) },
    rules() { return { [this.config.code]: [{ required: true, whitespace: true, message: '请输入编码', trigger: 'blur' }, { pattern: /^\S{1,64}$/, message: '编码限 64 位且不能包含空白', trigger: 'blur' }], [this.config.name]: [{ required: true, whitespace: true, message: '请输入名称', trigger: 'blur' }], ownerWarehouseId: [{ required: true, message: '请选择货主与实仓关联', trigger: 'change' }], wmsType: [{ required: true, message: '请选择仓库类型', trigger: 'change' }], inboundMode: [{ required: true, message: '请选择入库执行方式', trigger: 'change' }], outboundMode: [{ required: true, message: '请选择出库执行方式', trigger: 'change' }], mobilePhone: [{ pattern: /^[0-9+()\- #]{3,40}$/, message: '请输入有效的手机号或固定电话', trigger: 'blur' }] } },
    detailFields() { const fields = [{ key: this.config.status, label: '启用状态' }]; if (this.kind === 'realStore') fields.push({ key: 'wmsType', label: '仓库类型' }, { key: 'director', label: '负责人' }, { key: 'mobilePhone', label: '联系电话' }, ...this.addressFields); if (this.kind === 'simulationStore') fields.push({ key: 'inboundMode', label: '入库执行方式' }, { key: 'outboundMode', label: '出库执行方式' }, { key: 'externalWarehouse', label: '外部仓库编码' }, { key: 'externalOwner', label: 'WMS 货主编码' }, { key: 'ownerName', label: '货主名称' }, { key: 'ownerCode', label: '货主编码' }, { key: 'wmsName', label: '实体仓库名称' }, { key: 'realStoreCode', label: '实体仓库编码' }, { key: 'effectiveEnabled', label: '业务可用' }); fields.push({ key: 'createTime', label: '创建时间' }, { key: 'modifyTime', label: '修改时间' }); return fields }
  },
  watch: { '$route.query'() { if (this.$route.path === '/oms-supplychain/' + this.config.route) { this.readRoute(); this.load() } } },
  created() { this.readRoute(); this.refresh() }, activated() { this.loadOptions() },
  methods: {
    has(permission) { return checkPermi([permission]) }, can(action) { return this.has(this.config.permission + ':' + action) },
    stateLabel(value) { return Number(value) === 2 ? '启用' : Number(value) === 1 ? '停用' : '待维护' }, modeLabel(value) { return Number(value) === 1 ? 'WMS 回传' : Number(value) === 2 ? '自动虚拟' : '待配置' },
    unavailableReason(row) { if (!Number(row.relationValid)) return '关联不完整'; return [[row.status, '虚仓'], [row.relationStatus, '关联'], [row.ownerStatus, '货主'], [row.warehouseStatus, '实仓']].filter(([state]) => Number(state) !== 2).map(([, label]) => label + '停用').join('、') },
    relationLabel(r) { return `${r.ownerName} · ${r.ownerCode} / ${r.wmsName} · ${r.realStoreCode}${Number(r.effectiveEnabled) === 1 ? '' : '（不可用）'}` },
    go(kind, query = {}) { this.$router.push({ path: '/oms-supplychain/' + configs[kind].route, query }) }, inventory(row) { this.$router.push({ path: '/oms-inventory/wmsInventory', query: { storeCode: row.wmsSimulationCode } }) },
    readRoute() { const q = defaults(this.kind); Object.keys(q).forEach(k => { if (this.$route.query[k] !== undefined) q[k] = /Id$/.test(k) || ['pageNum', 'pageSize', 'status', 'isEnable', 'wmsType', 'actualWarehouse', 'effectiveEnabled'].includes(k) ? Number(this.$route.query[k]) : this.$route.query[k] }); this.query = q },
    async loadOptions() { try { const r = await warehouseOptions(); this.choices = r.data; this.optionsError = false; return true } catch (_) { this.optionsError = true; return false } },
    async load() { const seq = ++this.seq; this.loading = true; this.listError = false; try { const r = await warehouseList(this.kind, this.query); if (seq === this.seq) { this.rows = r.rows; this.total = r.total; this.selected = [] } } catch (_) { if (seq === this.seq) { this.rows = []; this.total = 0; this.listError = true } } finally { if (seq === this.seq) this.loading = false } },
    refresh() { this.loadOptions(); this.load() }, navigate() { const query = {}; Object.keys(this.query).forEach(k => { const v = this.query[k]; if (v !== null && v !== undefined && v !== '') query[k] = String(v) }); if (JSON.stringify(query) === JSON.stringify(this.$route.query)) this.load(); else this.$router.replace({ path: this.$route.path, query }).catch(() => this.load()) },
    search() { this.query.pageNum = 1; this.navigate() }, reset() { this.query = defaults(this.kind); this.navigate() },
    async loadConnectionChoices() { if (this.has('warehouse:wms:config') || this.has('warehouse:simulationStoreInfo:edit') || this.has('warehouse:simulationStoreInfo:add')) this.connectionChoices = (await connections()).data },
    async edit(row) { if (this.kind === 'simulationStore') { if (!await this.loadOptions()) return; await this.loadConnectionChoices() } this.form = row ? { ...row } : { [this.config.code]: '', [this.config.name]: '', [this.config.status]: 2, wmsType: 1, inboundMode: 2, outboundMode: 2, connectionId: null, externalWarehouse: '', externalOwner: '', ownerWarehouseId: null }; this.dialog = true; this.$nextTick(() => this.$refs.form.clearValidate()) }, closeForm(done) { if (!this.saving) done() },
    save() { if (this.saving) return; this.$refs.form.validate(async valid => { if (!valid) return; this.saving = true; try { if (this.form.id && Number(this.form[this.config.status]) === 1) await this.$modal.confirm('停用后将影响下级关联的新业务使用，已有资料仍可查询。确认保存？'); const r = await warehouseSave(this.kind, this.form); const created = !this.form.id; this.$modal.msgSuccess('保存成功'); this.dialog = false; this.refresh(); if (created && this.kind === 'owner' && this.has('warehouse:owner:edit')) this.manage({ ...this.form, id: r.data }) } catch (_) { /* Preserve input after cancellation or failed validation. */ } finally { this.saving = false } }) },
    removable(row) { return this.kind === 'owner' ? !Number(row.warehouseCount) : this.kind === 'realStore' ? !Number(row.ownerCount) : true },
    async remove(row) { const selected = row ? [row] : this.selected; try { await this.$modal.confirm('确认删除 ' + selected.map(r => `${r[this.config.name]}（${r[this.config.code]}）`).join('、') + '？有库存或单据引用的资料不能删除。'); await warehouseDelete(this.kind, selected.map(r => r.id).join(',')); this.$modal.msgSuccess('删除成功'); this.refresh() } catch (_) { /* Cancel or reference guard. */ } },
    exportRows() { this.download(`supplychain/${this.kind}/export`, { ...this.query }, `${this.config.title}_${Date.now()}.xlsx`) },
    async showDetail(row) { const seq = ++this.detailSeq; this.drawer = true; this.detail = null; this.detailError = false; this.detailLoading = true; try { const r = await warehouseDetail(this.kind, row.id); if (seq === this.detailSeq) this.detail = r.data } catch (_) { if (seq === this.detailSeq) this.detailError = true } finally { if (seq === this.detailSeq) this.detailLoading = false } },
    displayDetail(key) { const value = this.detail[key]; if (key === this.config.status) return this.stateLabel(value); if (key === 'wmsType') return this.warehouseTypes[value] || '待维护'; if (['inboundMode', 'outboundMode'].includes(key)) return this.modeLabel(value); if (key === 'effectiveEnabled') return Number(value) === 1 ? '可用' : this.unavailableReason(this.detail); if (key.endsWith('Time')) return this.parseTime(value) || '—'; return value === null || value === undefined || value === '' ? '—' : value },
    manage(owner) { this.relationOwner = owner; this.relations = []; this.relationsOpen = true; this.loadRelations(); this.loadOptions() },
    async loadRelations() { const seq = ++this.relationSeq; this.relationLoading = true; this.relationError = false; try { const r = await warehouseList('ownerWarehouse', { ownerId: this.relationOwner.id }); if (seq === this.relationSeq) this.relations = r.data } catch (_) { if (seq === this.relationSeq) this.relationError = true } finally { if (seq === this.relationSeq) this.relationLoading = false } },
    async editRelation(row) { if (!await this.loadOptions()) return; this.relationForm = row ? { ...row } : { ownerId: this.relationOwner.id, realStoreId: null, status: 2 }; this.relationFormOpen = true; this.$nextTick(() => this.$refs.relationForm.clearValidate()) }, closeRelation(done) { if (!this.relationSaving) done() },
    saveRelation() { if (this.relationSaving) return; this.$refs.relationForm.validate(async valid => { if (!valid) return; this.relationSaving = true; try { if (this.relationForm.id && Number(this.relationForm.status) === 1) await this.$modal.confirm('停用关联后，该关联下的虚仓将不可用于新业务。确认保存？'); await warehouseSave('ownerWarehouse', this.relationForm); this.relationFormOpen = false; this.$modal.msgSuccess('关联保存成功'); this.loadRelations(); this.refresh() } catch (_) { /* Keep failed form. */ } finally { this.relationSaving = false } }) },
    async removeRelation(row) { try { await this.$modal.confirm(`确认解除与 ${row.wmsName}（${row.realStoreCode}）的关联？`); await warehouseDelete('ownerWarehouse', row.id); this.$modal.msgSuccess('关联已解除'); this.loadRelations(); this.refresh() } catch (_) { /* Cancel or reference guard. */ } },
    relatedVirtuals(row) { this.relationsOpen = false; this.go('simulationStore', { ownerWarehouseId: row.id }) }, maintainOwners() { this.go('owner') }
  }
}
</script>
<style scoped>
.warehouse-nav { display:flex; flex-wrap:wrap; gap:8px; margin-bottom:16px; }.warehouse-nav .el-button { margin-left:0; }.relationship-tip { margin-bottom:26px; }.warehouse-workspace > .filter-panel { margin-top:0; }
.name-button { text-align:left; white-space:normal; line-height:1.4; }.muted { color:#84909f; font-size:12px; line-height:1.6; }.warehouse-detail { padding:0 22px 24px; overflow-wrap:anywhere; }.warehouse-detail h2 { margin-top:0; }.detail-links { margin-top:20px; }.relation-summary { grid-column:1 / -1; padding:12px; background:#f5f7fa; border-radius:8px; line-height:1.8; margin-bottom:16px; }
.query-actions ::v-deep .el-form-item__content { display:flex; gap:10px; align-items:center; }.query-actions ::v-deep .el-button { height:36px; margin-left:0; }
</style>
