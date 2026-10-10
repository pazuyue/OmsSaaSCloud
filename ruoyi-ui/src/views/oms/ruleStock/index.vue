<template>
  <div class="app-container allocation-workspace">
    <el-button v-if="inventoryReturn" type="text" icon="el-icon-arrow-left" @click="$router.push(inventoryReturn)">返回批次详情</el-button>
    <el-button v-else-if="productReturn" type="text" icon="el-icon-arrow-left" @click="$router.push(productReturn)">返回商品库存</el-button>
    <el-button v-else-if="channelReturn" type="text" icon="el-icon-arrow-left" @click="$router.push(channelReturn)">返回渠道库存</el-button>
    <template v-if="!opened">
      <div class="allocation-heading">
        <div><span class="eyebrow">INVENTORY ALLOCATION</span><h1>分货管理</h1><p>为渠道配置库存配额，查看每个商品的执行结果。</p></div>
        <el-button v-hasPermi="['ruleStock:info:add']" type="primary" icon="el-icon-plus" @click="create">新建分货</el-button>
      </div>
      <div class="allocation-panel filters">
        <el-input v-model="query.keyword" clearable placeholder="名称前缀 / 完整编号" prefix-icon="el-icon-search" @keyup.enter.native="search" />
        <el-select v-model="query.status" clearable placeholder="全部状态" @change="search"><el-option v-for="(label, value) in statuses" :key="value" :label="label" :value="Number(value)" /></el-select>
        <el-select v-model="query.allocationType" clearable placeholder="全部库存方式" @change="search"><el-option label="普通配额" :value="1" /><el-option label="锁库分货" :value="2" /></el-select>
        <el-button @click="search">查询</el-button>
      </div>
      <div class="allocation-panel">
        <div v-if="listError" class="load-error">加载失败 <el-button type="text" @click="loadList">重新加载</el-button></div>
        <el-table v-else v-loading="loading" :data="rows" empty-text="暂无分货单，点击右上角新建">
          <el-table-column label="分货单" min-width="250"><template slot-scope="s"><el-button type="text" class="rule-name" @click="open(s.row.id)">{{ s.row.ruleName }}</el-button><div class="secondary">{{ s.row.ruleCode }}</div></template></el-table-column>
          <el-table-column label="状态" width="115"><template slot-scope="s"><el-tag size="small" :type="statusColor(s.row.status)">{{ statuses[s.row.status] || '状态异常' }}</el-tag></template></el-table-column>
          <el-table-column label="执行方式" width="130"><template slot-scope="s">{{ ruleTypes[s.row.ruleType] || '未知规则' }}</template></el-table-column>
          <el-table-column label="日常调度" min-width="220"><template slot-scope="s"><template v-if="s.row.ruleType === 1"><div>{{ s.row.dailyEnabled ? '已启用' : '未启用' }} · 每 {{ s.row.intervalMinutes }} 分钟</div><div class="secondary">{{ s.row.startTime }} 至 {{ s.row.endTime }}</div><div class="secondary">{{ s.row.activeRunId ? '当前轮次：' + s.row.activeRunId : '下次：' + (s.row.nextRunAt || '—') }}</div></template><span v-else>—</span></template></el-table-column>
          <el-table-column label="库存方式" width="110"><template slot-scope="s">{{ s.row.allocationType === 2 ? '锁库分货' : '普通配额' }}</template></el-table-column>
          <el-table-column label="锁库进度" min-width="270"><template slot-scope="s">
            <template v-if="s.row.allocationType === 2 && s.row.reservationBalance">
              <div>已释放 <strong>{{ s.row.reservationBalance.releasedQuantity }}</strong> · 可释放 <strong>{{ s.row.reservationBalance.releasableQuantity }}</strong></div>
              <div class="secondary">原锁库 {{ s.row.reservationBalance.originalQuantity }} · 订单占用 {{ s.row.reservationBalance.occupiedQuantity }} · 已出库 {{ s.row.reservationBalance.consumedQuantity }}</div>
            </template>
            <span v-else class="secondary">{{ s.row.allocationType !== 2 ? '—' : '暂无锁库明细' }}</span>
          </template></el-table-column>
          <el-table-column label="分配策略" min-width="145"><template slot-scope="s">{{ s.row.ruleMode === 2 ? '按优先级分配' : s.row.ruleMode === 1 ? '渠道独立配额' : '待配置' }}</template></el-table-column>
          <el-table-column label="商品范围" width="110"><template slot-scope="s">{{ s.row.ruleRange === 1 ? '全部商品' : '指定商品' }}</template></el-table-column>
          <el-table-column label="最近执行" width="170"><template slot-scope="s">{{ s.row.lastUpdateTime || '尚未执行' }}</template></el-table-column>
          <el-table-column label="操作" width="100" :fixed="mobile ? false : 'right'"><template slot-scope="s"><el-button type="text" @click="open(s.row.id)">{{ [4, 9].includes(s.row.status) ? '查看 / 继续' : '查看详情' }}</el-button></template></el-table-column>
        </el-table>
        <pagination v-show="total > 0" :total="total" :page.sync="query.pageNum" :limit.sync="query.pageSize" @pagination="loadList" />
      </div>
    </template>
    <template v-else>
      <div class="allocation-heading">
        <div><el-button type="text" icon="el-icon-arrow-left" @click="back">返回分货列表</el-button><h1>{{ form.id ? form.ruleName : '新建分货' }} <el-tag v-if="form.id" size="small" :type="statusColor(form.status)">{{ statuses[form.status] }}</el-tag></h1><p>{{ form.ruleCode || '配置仓库、商品和渠道，确认预览后执行。' }}</p></div>
        <el-button v-if="form.id" :disabled="busy || running" icon="el-icon-refresh" @click="refresh">刷新详情</el-button>
      </div>
      <el-alert v-if="form.ruleType === 1" title="日常分货由系统定时任务调度，仅使用正品可用库存；锁库不参与。到期或停用后保留最后一次配额。" type="info" :closable="false" show-icon />
      <div v-if="form.ruleType === 1 && form.id" class="allocation-panel daily-summary"><span>{{ form.dailyEnabled ? '调度已启用' : '调度未启用' }}</span><span>有效期：{{ form.startTime }} 至 {{ form.endTime }}</span><span>每 {{ form.intervalMinutes }} 分钟 · 优先级 {{ form.dailyPriority }}</span><span>{{ form.activeRunId ? '当前轮次：' + form.activeRunId : '下次执行：' + (form.nextRunAt || '—') }}</span></div>
      <el-alert v-if="runError" :title="runError" type="warning" :closable="false" show-icon />
      <el-alert v-if="[4,9].includes(form.status)" :title="running ? '正在处理，每个商品独立提交。成功项不会重复执行。' : '还有待处理商品。点击继续处理可从中断处继续，成功记录已保存。'" type="info" :closable="false" show-icon />
      <div v-if="Number(counts.total)" class="allocation-panel execution-summary">
        <div><span>商品总数</span><strong>{{ counts.total }}</strong></div><div><span>分货成功</span><strong>{{ counts.success }}</strong></div><div><span>分货失败</span><strong :class="{ danger: Number(counts.failed) }">{{ counts.failed }}</strong></div><div><span>{{ releasing ? '待释放' : '待处理' }}</span><strong>{{ releasing ? counts.releasePending : counts.pending }}</strong></div><div v-if="form.allocationType === 2"><span>已释放 / 释放失败</span><strong>{{ counts.released }} / {{ counts.releaseFailed }}</strong></div>
      </div>
      <el-progress v-if="running && Number(counts.total)" :percentage="progress" :stroke-width="5" />
      <div v-loading="detailLoading" class="allocation-panel allocation-detail">
        <el-tabs v-model="tab" @tab-click="loadTab">
          <el-tab-pane label="1. 基本信息" name="basic">
            <el-form label-position="top" :disabled="!editable || busy">
              <div class="form-grid">
                <el-form-item label="分货单名称"><el-input v-model="form.ruleName" maxlength="100" show-word-limit placeholder="例如：秋季新品渠道配额" /></el-form-item>
                <el-form-item label="执行方式"><el-select v-model="form.ruleType" @change="changeRuleType"><el-option v-for="(label, value) in ruleTypes" :key="value" :label="label" :value="Number(value)" /></el-select><div class="field-help">{{ form.ruleType === 1 ? '审核启用后，在有效期内按间隔持续重算普通配额。' : '审核通过后执行一次，失败商品可单独重试。' }}</div></el-form-item>
                <el-form-item label="库存方式"><el-radio-group v-model="form.allocationType" :disabled="form.ruleType !== 2"><el-radio :label="1">普通配额</el-radio><el-radio :label="2">锁库分货</el-radio></el-radio-group><div class="field-help">{{ form.allocationType === 2 ? '实际预留仓库和批次库存，记录到渠道锁库量；不会直接增加渠道普通可售量。' : '重算渠道可售配额，不实际预留仓库库存。' }}</div></el-form-item>
                <template v-if="form.ruleType === 1">
                  <el-form-item label="生效时间"><el-date-picker v-model="form.startTime" type="datetime" value-format="yyyy-MM-dd HH:mm:ss" placeholder="选择生效时间" /></el-form-item>
                  <el-form-item label="结束时间"><el-date-picker v-model="form.endTime" type="datetime" value-format="yyyy-MM-dd HH:mm:ss" placeholder="选择结束时间" /></el-form-item>
                  <el-form-item label="执行间隔（分钟）"><el-input-number v-model="form.intervalMinutes" :min="1" :max="1440" :precision="0" /><div class="field-help">上一轮结束后再间隔指定分钟，避免两轮重叠；调度由系统定时任务统一控制。</div></el-form-item>
                  <el-form-item label="规则优先级"><el-input-number v-model="form.dailyPriority" :min="1" :max="9999" :precision="0" /><div class="field-help">同一商品和渠道由数字较小的生效规则负责；相同时按较早的单据 ID 优先。低优先级规则记录跳过原因。</div></el-form-item>
                </template>
                <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" :rows="3" maxlength="1000" placeholder="说明本次分货用途" /></el-form-item>
              </div>
            </el-form>
          </el-tab-pane>
          <el-tab-pane label="2. 仓库与商品" name="scope">
            <el-form label-position="top" :disabled="!editable || busy">
              <el-form-item label="参与分货的虚仓"><el-select v-model="form.stores" multiple filterable remote :remote-method="searchStores" :loading="optionsLoading" placeholder="输入仓库编码或名称搜索" class="full-width"><el-option v-for="s in storeOptions" :key="s.wmsSimulationCode" :value="s.wmsSimulationCode" :label="`${s.wmsSimulationName} · ${s.wmsSimulationCode}`" /></el-select><div class="field-help">以所选虚仓的正品可用库存合计作为分货基数。</div></el-form-item>
              <el-form-item label="商品范围"><el-radio-group v-model="form.ruleRange" @change="loadGoods"><el-radio :label="1">所选仓库全部商品</el-radio><el-radio :label="2">导入指定商品</el-radio></el-radio-group></el-form-item>
            </el-form>
            <div v-if="form.ruleRange === 2">
              <div class="section-toolbar"><span>已导入 {{ form.goodsCount || 0 }} 个 SKU</span><div><el-button v-if="editable" v-hasPermi="['ruleStock:info:edit']" :disabled="busy" @click="pickFile">{{ form.goodsCount ? '替换商品清单' : '导入商品' }}</el-button><el-button type="text" @click="template">下载模板</el-button></div></div>
              <p class="field-help">Excel 最多 5 万行、10 MB。校验成功后替换原清单，重复 SKU 自动去重；失败时保留原清单。</p>
              <input ref="file" type="file" accept=".xlsx,.xls" hidden @change="upload">
              <el-input v-model="goodsQuery.skuSn" placeholder="精确查询 SKU" clearable class="sku-search" @keyup.enter.native="searchGoods"><el-button slot="append" icon="el-icon-search" @click="searchGoods" /></el-input>
              <el-table v-loading="goodsLoading" :data="goods" empty-text="尚未导入商品"><el-table-column prop="skuSn" label="SKU" /></el-table>
              <pagination v-show="goodsTotal > 0" :total="goodsTotal" :page.sync="goodsQuery.pageNum" :limit.sync="goodsQuery.pageSize" @pagination="loadGoods" />
            </div>
          </el-tab-pane>
          <el-tab-pane label="3. 渠道分配" name="channels">
            <el-form label-position="top" :disabled="!editable || busy">
              <el-form-item label="分配策略"><el-radio-group v-model="form.ruleMode"><el-radio :label="2">按优先级分配</el-radio><el-radio :label="1">渠道独立配额</el-radio></el-radio-group><div class="field-help">{{ form.ruleMode === 2 ? '各渠道按同一库存基数计算，依次分配，合计不超过可用量。' : '各渠道独立计算，普通配额合计可能超过实物库存；锁库总量仍不能超过可用量。' }}</div></el-form-item>
              <el-form-item label="分货渠道"><el-select :value="channelIds" multiple filterable remote :remote-method="searchChannels" placeholder="输入渠道名称搜索" no-data-text="没有可选渠道，请先在渠道管理中启用渠道" class="full-width" @change="changeChannels"><el-option v-for="c in channelOptions" :key="c.channelId" :label="c.channelName" :value="c.channelId" /></el-select><div class="field-help">仅可选择当前公司已启用的渠道。</div></el-form-item>
            </el-form>
            <el-table ref="channelTable" :data="form.channels" row-key="channelId" class="channel-table">
              <el-table-column label="顺序" width="115"><template slot-scope="s"><span :class="{ 'drag-handle': editable && form.ruleMode === 2 }">{{ s.$index + 1 }} <i v-if="editable && form.ruleMode === 2" class="el-icon-rank" /></span><template v-if="editable && form.ruleMode === 2"><el-button type="text" :disabled="busy || s.$index === 0" aria-label="上移" @click="moveChannel(s.$index, -1)">↑</el-button><el-button type="text" :disabled="busy || s.$index === form.channels.length - 1" aria-label="下移" @click="moveChannel(s.$index, 1)">↓</el-button></template></template></el-table-column>
              <el-table-column label="渠道" prop="channelName" min-width="160" />
              <el-table-column label="计算方式" width="125"><template slot-scope="s"><el-select v-model="s.row.ruleType" :disabled="!editable || busy" @change="s.row.percentage = 0"><el-option label="按比例" :value="1" /><el-option label="固定数量" :value="2" /></el-select></template></el-table-column>
              <el-table-column label="分配值" min-width="185"><template slot-scope="s"><el-input-number v-model="s.row.percentage" :disabled="!editable || busy" :min="0" :max="s.row.ruleType === 1 ? 100 : 100000000" :precision="s.row.ruleType === 1 ? 2 : 0" controls-position="right" /> {{ s.row.ruleType === 1 ? '%' : '件' }}</template></el-table-column>
              <el-table-column label="取整方式" width="140"><template slot-scope="s"><el-select v-model="s.row.decimalHandleType" :disabled="!editable || busy || s.row.ruleType === 2"><el-option label="向下取整" :value="1" /><el-option label="向上取整" :value="2" /><el-option label="四舍五入" :value="3" /></el-select></template></el-table-column>
            </el-table>
          </el-tab-pane>
          <el-tab-pane label="4. 预览与结果" name="results">
            <template v-if="!Number(counts.total)">
              <div class="section-toolbar"><div><h3>分货预览</h3><p class="field-help">预览不占库存；正式执行按当时库存和生效规则重新计算，每个商品独立提交。</p></div><el-button :loading="previewLoading" :disabled="busy" @click="preview">{{ editable ? '保存并预览' : '刷新预览' }}</el-button></div>
              <el-table key="preview" v-loading="previewLoading" :data="previews" empty-text="完成仓库和渠道配置后，点击预览">
                <el-table-column type="expand"><template slot-scope="s"><allocation-lines :rows="s.row.channels || []" :locking="form.allocationType === 2" /></template></el-table-column>
                <el-table-column label="SKU" prop="skuSn" min-width="150" /><el-table-column label="可分库存" prop="available" width="110" /><el-table-column label="渠道配额合计" prop="total" width="120" /><el-table-column label="预计锁定" prop="lockQuantity" width="110" /><el-table-column label="说明" min-width="200"><template slot-scope="s"><span :class="{ danger: s.row.error }">{{ s.row.error || (s.row.shared ? '共享库存：渠道配额合计超过实物库存' : '展开查看各渠道变化') }}</span></template></el-table-column>
              </el-table>
              <pagination v-show="previewTotal > 0" :total="previewTotal" :page.sync="previewQuery.pageNum" :limit.sync="previewQuery.pageSize" :page-sizes="[10,20]" @pagination="loadPreview" />
            </template>
            <template v-else>
              <div class="section-toolbar"><h3>{{ releasing ? '分货与释放记录' : '执行结果' }}</h3><el-switch v-model="onlyFailed" active-text="仅看失败" @change="filterResults" /></div>
              <el-table key="results" v-loading="resultsLoading" :data="results" empty-text="暂无符合条件的执行记录">
                <el-table-column type="expand"><template slot-scope="s"><allocation-lines :rows="s.row.detail ? s.row.detail.channels : []" :locking="form.allocationType === 2" /><div class="field-help">操作人：{{ s.row.operatorName || '—' }} · 尝试 {{ s.row.attempts }} 次 · {{ s.row.modifyTime }}<span v-if="s.row.releaseOperator"> · 释放人：{{ s.row.releaseOperator }}</span></div></template></el-table-column>
                <el-table-column label="SKU" prop="skuSn" min-width="150" /><el-table-column label="分货结果" width="100"><template slot-scope="s"><el-tag size="small" :type="s.row.status === 'FAILED' ? 'danger' : s.row.status === 'SUCCESS' ? 'success' : 'info'">{{ resultNames[s.row.status] }}</el-tag></template></el-table-column>
                <el-table-column :label="form.allocationType === 2 ? '原锁库' : '配额合计'" prop="allocatedQuantity" width="100" />
                <template v-if="form.allocationType === 2">
                  <el-table-column v-for="c in balanceColumns" :key="c.key" :label="c.label" width="105"><template slot-scope="s">{{ s.row[c.key] }}</template></el-table-column>
                  <el-table-column label="释放结果" width="115"><template slot-scope="s">{{ releaseNames[s.row.releaseStatus] }}</template></el-table-column>
                </template>
                <el-table-column label="处理说明" min-width="220"><template slot-scope="s"><span class="danger">{{ s.row.releaseError || s.row.errorMessage }}</span><span v-if="!s.row.releaseError && !s.row.errorMessage">{{ s.row.status === 'SUCCESS' ? '已提交，展开查看渠道明细' : '等待处理' }}</span></template></el-table-column>
              </el-table>
              <pagination v-show="resultTotal > 0" :total="resultTotal" :page.sync="resultQuery.pageNum" :limit.sync="resultQuery.pageSize" @pagination="loadResults" />
            </template>
          </el-tab-pane>
          <el-tab-pane v-if="form.ruleType === 1 && form.id" label="执行记录" name="dailyRuns"><daily-runs v-if="tab === 'dailyRuns'" :key="form.id" :rule-id="Number(form.id)" /></el-tab-pane>
        </el-tabs>
      </div>
      <div v-if="editable || [2,4,7,8,9].includes(form.status) || canRelease || (form.ruleType === 1 && [3,12].includes(form.status))" class="allocation-footer">
        <span class="field-help">{{ editable ? '草稿可随时保存；提交审核后配置锁定。' : form.ruleType === 1 ? '由系统定时任务持续处理，停用或到期保留已提交配额。' : '仅释放本单未占用余额；订单取消后腾出的余额可再次释放。' }}</span>
        <div class="footer-actions">
          <el-button v-if="editable && form.id" v-hasPermi="['ruleStock:info:remove']" :disabled="busy" type="text" class="danger" @click="remove">删除草稿</el-button>
          <el-button v-if="editable" v-hasPermi="[form.id ? 'ruleStock:info:edit' : 'ruleStock:info:add']" :loading="busy" @click="save">保存草稿</el-button>
          <el-button v-if="editable" v-hasPermi="['ruleStock:info:edit']" type="primary" :disabled="busy" @click="submit">提交审核</el-button>
          <el-button v-if="form.status === 2" v-hasPermi="['ruleStock:info:edit']" :disabled="busy" @click="withdraw">撤回修改</el-button>
          <el-button v-if="form.status === 2 && form.ruleType !== 1" v-hasPermi="['ruleStock:info:edit']" type="primary" :loading="busy" @click="execute('EXECUTE')">审核并执行</el-button>
          <template v-if="form.ruleType === 1">
            <el-button v-if="!form.dailyEnabled && [2,3,12].includes(form.status)" v-hasPermi="['ruleStock:info:edit']" type="primary" :loading="busy" @click="controlDaily('ENABLE')">{{ form.status === 2 ? '审核并启用' : '启用规则' }}</el-button>
            <el-button v-if="form.dailyEnabled" v-hasPermi="['ruleStock:info:edit']" :disabled="busy" @click="controlDaily('PAUSE')">停用规则</el-button>
            <el-button v-if="form.dailyEnabled" v-hasPermi="['ruleStock:info:edit']" type="primary" :disabled="busy || !!form.activeRunId" @click="controlDaily('RUN')">立即执行一轮</el-button>
          </template>
          <el-button v-if="[4,9].includes(form.status)" v-hasPermi="['ruleStock:info:edit']" type="primary" :loading="running" @click="pump">继续处理</el-button>
          <el-button v-if="[7,8].includes(form.status) && !releasing" v-hasPermi="['ruleStock:info:edit']" :disabled="busy" type="primary" @click="execute('RETRY')">仅重试失败商品</el-button>
          <el-button v-if="canRelease" v-hasPermi="['ruleStock:info:edit']" :disabled="busy" @click="execute('RELEASE')">释放剩余锁库</el-button>
        </div>
      </div>
    </template>
  </div>
</template>

<script>
import Sortable from 'sortablejs'
import { listRules, ruleDetail, ruleOptions, saveRule, submitRule, deleteRule, previewRule, startRule, stepRule, ruleResults, ruleGoods, importRuleGoods, dailyCommand } from '@/api/ruleStock/workspace'
import AllocationLines from './components/AllocationLines'
import DailyRuns from './components/DailyRuns'

const blank = () => ({ id: null, revision: 1, ruleType: 2, ruleName: '', remark: '', allocationType: 1, ruleRange: 1, ruleMode: 2, status: 1, startTime: null, endTime: null, intervalMinutes: 5, dailyPriority: 100, dailyEnabled: 0, stores: [], channels: [], goodsCount: 0, counts: {}})
export default {
  name: 'AllocationWorkspace',
  components: { AllocationLines, DailyRuns },
  beforeRouteLeave(to, from, next) { this.confirmLeave().then(() => { this.alive = false; next() }).catch(() => next(false)) },
  data() {
    return {
      statuses: { 1: '草稿', 2: '待审核', 3: '已启用 / 待执行', 4: '执行中', 5: '已完成', 6: '已作废', 7: '部分成功', 8: '执行失败', 9: '释放中', 10: '释放完成', 11: '部分释放', 12: '已停用', 13: '已到期' },
      ruleTypes: { 1: '日常分货', 2: '一次性分货', 3: '锁库时分货' },
      resultNames: { PENDING: '待处理', SUCCESS: '成功', FAILED: '失败' }, releaseNames: { NONE: '未释放', PENDING: '待释放', RELEASED: '释放完成', PARTIAL: '部分释放', FAILED: '释放失败' },
      balanceColumns: [{ key: 'occupiedQuantity', label: '订单占用' }, { key: 'consumedQuantity', label: '已出库' }, { key: 'releasedQuantity', label: '已释放' }, { key: 'releasableQuantity', label: '可释放' }],
      query: { keyword: '', status: null, allocationType: null, pageNum: 1, pageSize: 20 },
      rows: [], total: 0, loading: false, listError: false, opened: false, detailLoading: false,
      form: blank(), baseline: '', tab: 'basic', busy: false, running: false, runError: '', alive: true,
      storeOptions: [], channelOptions: [], optionsLoading: false, optionsSequence: { stores: 0, channels: 0 },
      previews: [], previewTotal: 0, previewLoading: false, previewQuery: { pageNum: 1, pageSize: 20 }, previewRevision: null,
      results: [], resultTotal: 0, resultsLoading: false, resultQuery: { pageNum: 1, pageSize: 20 }, onlyFailed: false,
      goods: [], goodsTotal: 0, goodsLoading: false, goodsQuery: { pageNum: 1, pageSize: 20, skuSn: '' }
    }
  },
  computed: {
    channelReturn() { const value = this.$route.query.channelReturn; return typeof value === 'string' && /^\/oms-inventory\/channelInventory(?:\?|$)/.test(value) ? value : '' },
    inventoryReturn() { const value = this.$route.query.inventoryReturn; return typeof value === 'string' && /^\/oms-inventory\/wmsInventory(?:\?|$)/.test(value) ? value : '' },
    productReturn() { const value = this.$route.query.productReturn; return typeof value === 'string' && /^\/oms-inventory\/productInventory(?:\?|$)/.test(value) ? value : '' },
    mobile() { return this.$store.state.app.device === 'mobile' },
    editable() { return this.form.status === 1 },
    channelIds() { return this.form.channels.map(c => c.channelId) },
    counts() { return this.form.counts || {} },
    releasing() { return this.form.runAction === 'RELEASE' },
    canRelease() { return this.form.allocationType === 2 && [5, 7, 8, 11].includes(this.form.status) && Number(this.counts.success) > Number(this.counts.released || 0) },
    progress() { return Math.min(100, Math.round(100 * (Number(this.counts.total) - Number(this.releasing ? this.counts.releasePending : this.counts.pending)) / Number(this.counts.total))) },
    dirty() { return this.editable && JSON.stringify(this.payload()) !== this.baseline }
  },
  watch: { tab() { this.$nextTick(this.initDrag) }, 'form.ruleMode'() { this.$nextTick(this.initDrag) }, '$route.query.ruleId'() { if (this.$route.path === '/oms-inventory/ruleStock') this.openLinkedRule() } },
  created() { this.loadList(); this.openLinkedRule() },
  beforeDestroy() { this.alive = false; if (this.sortable) this.sortable.destroy(); clearTimeout(this.storeTimer); clearTimeout(this.channelTimer) },
  methods: {
    async openLinkedRule() { const id = this.$route.query.ruleId; if (/^\d+$/.test(id || '')) { try { await this.open(Number(id)) } catch (_) { /* Request layer displays missing or unauthorized records. */ } } },
    statusColor(s) { return [7, 8].includes(s) ? 'danger' : [5, 10].includes(s) ? 'success' : [2, 4, 9, 11].includes(s) ? 'warning' : 'info' },
    async loadList() { this.loading = true; this.listError = false; try { const r = await listRules(this.query); this.rows = r.rows; this.total = r.total } catch (_) { this.listError = true } finally { this.loading = false } },
    search() { this.query.pageNum = 1; this.loadList() },
    create() { this.form = blank(); this.resetDetail(); this.opened = true; this.baseline = JSON.stringify(this.payload()); this.loadOptions('stores'); this.loadOptions('channels') },
    resetDetail() { this.tab = 'basic'; this.previews = []; this.previewTotal = 0; this.previewRevision = null; this.results = []; this.goods = []; this.runError = ''; this.resultQuery.pageNum = 1; this.previewQuery.pageNum = 1; this.goodsQuery.pageNum = 1; this.onlyFailed = false },
    async open(id) { this.detailLoading = true; try { const r = await ruleDetail(id); this.resetDetail(); this.apply(r.data); this.opened = true; if (this.form.ruleType === 1 && ![1, 2].includes(this.form.status)) this.tab = 'dailyRuns'; else if (Number(this.counts.total)) { this.tab = 'results'; this.loadResults() } this.loadOptions('stores'); this.loadOptions('channels') } finally { this.detailLoading = false } },
    apply(data) { this.form = { ...blank(), ...data }; this.baseline = JSON.stringify(this.payload()); this.mergeSelectedOptions(); this.$nextTick(this.initDrag) },
    payload() { return { id: this.form.id, revision: this.form.revision, ruleName: this.form.ruleName, remark: this.form.remark, ruleType: this.form.ruleType, startTime: this.form.startTime, endTime: this.form.endTime, intervalMinutes: this.form.intervalMinutes, dailyPriority: this.form.dailyPriority, allocationType: this.form.allocationType, ruleRange: this.form.ruleRange, ruleMode: this.form.ruleMode, stores: this.form.stores, channels: this.form.channels.map(c => ({ channelId: c.channelId, channelName: c.channelName, ruleType: c.ruleType, percentage: c.percentage, decimalHandleType: c.decimalHandleType })) } },
    changeRuleType(type) { this.form.allocationType = type === 3 ? 2 : 1; this.previews = []; this.previewRevision = null },
    async controlDaily(action) { if (this.busy) return; const messages = { ENABLE: '启用后在有效期内自动重算普通配额，锁库库存不参与。', PAUSE: '停止后续处理，已提交的配额保留；当前正在提交的商品可能完成。', RUN: '立即创建一轮后台执行，按当前可用库存重算普通配额。' }; try { await this.$confirm(messages[action], '日常分货', { type: 'warning' }) } catch (_) { return } this.busy = true; try { const r = await dailyCommand(this.form.id, this.form.revision, action); this.apply(r.data); this.tab = 'basic'; await this.$nextTick(); this.tab = 'dailyRuns'; this.$message.success(action === 'RUN' ? '已提交后台处理' : '规则状态已更新') } catch (_) { /* request layer shows details */ } finally { this.busy = false } },
    async confirmLeave() { if (this.busy || this.running || this.dirty) await this.$confirm(this.running ? '离开后暂停后续处理，当前请求可能继续提交。可在详情中继续处理。' : '还有未保存或正在提交的内容，确定离开？', '离开分货详情', { type: 'warning' }) },
    async back() { try { await this.confirmLeave(); this.opened = false; this.loadList() } catch (_) { /* keep current work */ } },
    async refresh() { if (this.dirty) { try { await this.$confirm('刷新会丢弃未保存的配置，是否继续？', '刷新详情') } catch (_) { return } } await this.open(this.form.id) },
    mergeSelectedOptions() {
      const stores = new Map(this.storeOptions.map(s => [s.wmsSimulationCode, s])); this.form.stores.forEach(code => { if (!stores.has(code)) stores.set(code, { wmsSimulationCode: code, wmsSimulationName: code }) }); this.storeOptions = [...stores.values()]
      const channels = new Map(this.channelOptions.map(c => [c.channelId, c])); this.form.channels.forEach(c => { if (!channels.has(c.channelId)) channels.set(c.channelId, c) }); this.channelOptions = [...channels.values()]
    },
    async loadOptions(type, keyword = '') { const sequence = ++this.optionsSequence[type]; this.optionsLoading = true; try { const r = await ruleOptions(type, keyword); if (sequence !== this.optionsSequence[type]) return; this[type === 'stores' ? 'storeOptions' : 'channelOptions'] = r.data; this.mergeSelectedOptions() } catch (_) { /* request layer displays error */ } finally { this.optionsLoading = false } },
    searchStores(keyword) { clearTimeout(this.storeTimer); this.storeTimer = setTimeout(() => this.loadOptions('stores', keyword), 250) },
    searchChannels(keyword) { clearTimeout(this.channelTimer); this.channelTimer = setTimeout(() => this.loadOptions('channels', keyword), 250) },
    changeChannels(ids) { const old = new Map(this.form.channels.map(c => [c.channelId, c])); this.form.channels = ids.map(id => old.get(id) || { channelId: id, channelName: (this.channelOptions.find(c => c.channelId === id) || {}).channelName, percentage: 0, ruleType: 1, decimalHandleType: 1 }); this.$nextTick(this.initDrag) },
    moveChannel(index, delta) { const rows = [...this.form.channels]; const [row] = rows.splice(index, 1); rows.splice(index + delta, 0, row); this.form.channels = rows },
    initDrag() { if (this.sortable) { this.sortable.destroy(); this.sortable = null } if (!this.editable || this.form.ruleMode !== 2 || this.tab !== 'channels' || !this.$refs.channelTable) return; const body = this.$refs.channelTable.$el.querySelector('.el-table__body-wrapper tbody'); this.sortable = Sortable.create(body, { handle: '.drag-handle', onEnd: e => this.moveChannel(e.oldIndex, e.newIndex - e.oldIndex) }) },
    async save() { if (this.busy) return false; if (!this.form.ruleName.trim()) { this.$message.warning('请先填写分货单名称'); this.tab = 'basic'; return false } this.busy = true; try { const r = await saveRule(this.payload()); this.apply(r.data); this.previews = []; this.previewRevision = null; this.$message.success('草稿已保存'); return true } catch (_) { return false } finally { this.busy = false } },
    async submit() { if (!(await this.save())) return; this.busy = true; try { const r = await submitRule(this.form.id, this.form.revision); this.apply(r.data); this.tab = 'results'; await this.loadPreview() } catch (_) { /* request layer displays error */ } finally { this.busy = false } },
    async withdraw() { this.busy = true; try { const r = await submitRule(this.form.id, this.form.revision, true); this.apply(r.data); this.previewRevision = null } catch (_) { /* request layer displays error */ } finally { this.busy = false } },
    async remove() { try { await this.$confirm('仅删除当前草稿及其配置，确定删除？', '删除草稿', { type: 'warning' }) } catch (_) { return } this.busy = true; try { await deleteRule(this.form.id, this.form.revision); this.opened = false; await this.loadList() } catch (_) { /* request layer displays error */ } finally { this.busy = false } },
    async preview() { if (this.editable && !(await this.save())) return; this.previewQuery.pageNum = 1; await this.loadPreview() },
    async loadPreview() { if (!this.form.id) return; this.previewLoading = true; this.previewRevision = null; try { const r = await previewRule(this.form.id, { ...this.previewQuery, revision: this.form.revision }); this.previews = r.rows; this.previewTotal = r.total; this.previewRevision = this.form.revision } catch (_) { this.previews = [] } finally { this.previewLoading = false } },
    async execute(action) {
      if (this.busy || this.running) return
      if (action === 'EXECUTE' && this.previewRevision !== this.form.revision) { this.tab = 'results'; await this.loadPreview(); this.$message.info('请先查看预览，再点击审核并执行'); return }
      try { await this.$confirm(action === 'RELEASE' ? '仅释放本单未被订单占用的剩余库存。已出库数量不返还，订单占用继续保留。本单停止接受新占用；原订单取消后可再次释放腾出的余额。' : action === 'RETRY' ? '仅重新处理失败商品，按当前库存重新计算，成功商品保持原结果。' : '按执行时库存分货，允许部分商品成功。成功结果会立即生效，失败商品可重试。', action === 'RELEASE' ? '释放剩余锁库' : '确认执行', { type: 'warning', confirmButtonText: '确认执行' }) } catch (_) { return }
      this.busy = true
      try { const r = await startRule(this.form.id, this.form.revision, action); this.apply(r.data); this.tab = 'results' } catch (_) { return } finally { this.busy = false }
      await this.pump()
    },
    async pump() {
      if (this.running || ![4, 9].includes(this.form.status)) return
      this.running = true; this.runError = ''; const id = this.form.id
      try {
        while (this.alive && this.opened && this.form.id === id && [4, 9].includes(this.form.status)) {
          const r = await stepRule(id)
          if (!this.alive || !this.opened || this.form.id !== id) break
          this.apply(r.data); await this.loadResults()
        }
      } catch (_) { this.runError = '处理已中断，成功记录已保存。请刷新状态后继续处理。' } finally { this.running = false }
    },
    filterResults() { this.resultQuery.pageNum = 1; this.loadResults() },
    async loadResults() { if (!this.form.id) return; this.resultsLoading = true; try { const r = await ruleResults(this.form.id, { ...this.resultQuery, status: this.onlyFailed ? 'FAILED' : '' }); this.results = r.rows; this.resultTotal = r.total } catch (_) { /* request layer displays error */ } finally { this.resultsLoading = false } },
    loadTab() { if (this.tab === 'scope') this.loadGoods(); if (this.tab === 'results' && Number(this.counts.total)) this.loadResults() },
    searchGoods() { this.goodsQuery.pageNum = 1; this.loadGoods() },
    async loadGoods() { if (!this.form.id || this.form.ruleRange !== 2) return; this.goodsLoading = true; try { const r = await ruleGoods(this.form.id, this.goodsQuery); this.goods = r.rows; this.goodsTotal = r.total } catch (_) { /* request layer displays error */ } finally { this.goodsLoading = false } },
    async pickFile() { if (this.dirty || !this.form.id) { if (!(await this.save())) return } this.$refs.file.click() },
    async upload(event) { const file = event.target.files[0]; event.target.value = ''; if (!file) return; if (file.size > 10 * 1024 * 1024) { this.$message.warning('文件不能超过 10 MB'); return } if (this.form.goodsCount) { try { await this.$confirm('校验通过后，将替换当前商品清单。是否继续？', '替换商品清单') } catch (_) { return } } this.busy = true; try { const r = await importRuleGoods(this.form.id, this.form.revision, file); this.apply(r.data); this.$message.success(`已导入 ${r.data.imported} 个 SKU，去重 ${r.data.duplicates} 行`); this.previewRevision = null; await this.loadGoods() } catch (_) { /* original list stays intact */ } finally { this.busy = false } },
    template() { this.download('/inventory/allocation/template', {}, '分货商品模板.xlsx') }
  }
}
</script>

<style scoped>
.allocation-workspace { max-width: 1680px; margin: 0 auto; color: #1d1d1f; }
.daily-summary { display:flex; flex-wrap:wrap; gap:12px 28px; color:#657084; font-size:13px; margin-top:16px; }
.allocation-heading { display: flex; justify-content: space-between; align-items: center; gap: 20px; margin-bottom: 24px; }
.allocation-heading > div:first-child { min-width: 0; flex: 1; overflow-wrap: anywhere; }
.allocation-heading > .el-button { flex-shrink: 0; }
.allocation-heading h1 { font-size: 28px; font-weight: 650; letter-spacing: -.7px; margin: 8px 0; }
.allocation-heading p, .secondary, .field-help { color: #7a7e87; font-size: 13px; line-height: 1.7; }
.allocation-heading p { margin: 0; }.eyebrow { font-size: 10px; letter-spacing: 2px; color: #868b94; }
.allocation-panel { background: #fff; border: 1px solid #e9ebef; border-radius: 16px; padding: 22px; margin-bottom: 18px; }
.filters { display: flex; gap: 12px; }.filters > .el-input { max-width: 340px; }.filters > .el-select { width: 170px; }
.rule-name { font-size: 14px; font-weight: 600; padding: 4px 0; }.secondary { font-size: 11px; word-break: break-all; }
.execution-summary { display: flex; gap: 40px; margin-top: 18px; flex-wrap: wrap; }.execution-summary span { display: block; color: #7a7e87; font-size: 12px; }.execution-summary strong { display: block; font-size: 25px; margin-top: 8px; font-weight: 600; }
.allocation-detail { margin-top: 18px; min-height: 350px; }.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 8px 36px; max-width: 1050px; }.field-help { margin-top: 8px; }.full-width { width: 100%; max-width: 900px; }
.section-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin: 12px 0; }.section-toolbar h3 { margin: 0; font-size: 17px; font-weight: 600; }.sku-search { max-width: 320px; margin: 14px 0; }.drag-handle { cursor: grab; margin-right: 5px; color: #007aff; }
.allocation-footer { position: sticky; bottom: 12px; z-index: 5; display: flex; justify-content: space-between; align-items: center; gap: 20px; padding: 16px 22px; background: rgba(255,255,255,.96); border: 1px solid #e9ebef; border-radius: 14px; box-shadow: 0 5px 25px rgba(20,30,50,.06); }.footer-actions { display: flex; flex-wrap: wrap; gap: 8px; }.footer-actions .el-button { margin-left: 0; }.danger { color: #d84b4b !important; }.load-error { padding: 24px; text-align: center; }
::v-deep .el-tabs__nav-wrap::after { height: 1px; background: #eef0f4; } ::v-deep .el-tabs__item { height: 52px; font-size: 14px; } ::v-deep .el-table__expanded-cell { background: #f8f9fb; } ::v-deep .el-table th { background: #fafbfc; } ::v-deep .channel-table .el-input-number { width: 145px; }
@media(max-width: 768px) { .allocation-heading { flex-wrap: wrap; align-items: flex-start; }.allocation-heading > div:first-child { flex-basis: 100%; }.allocation-heading h1 { font-size: 23px; }.allocation-panel { padding: 14px; }.filters { flex-wrap: wrap; }.filters > .el-input { max-width: none; }.filters > .el-select { width: calc(50% - 6px); }.form-grid { grid-template-columns: 1fr; gap: 0; }.allocation-footer { flex-direction: column; align-items: stretch; padding: 12px; gap: 8px; }.allocation-footer > .field-help { display: none; }.execution-summary { gap: 20px; }.section-toolbar { flex-wrap: wrap; } }
</style>
