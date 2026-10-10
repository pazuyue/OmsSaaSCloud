<template>
  <div class="app-container purchase-workspace">
    <wms-logs ref="wmsLogs" />
    <div class="workspace-links">
      <el-button v-for="n in navigation" :key="n.kind" :type="kind === n.kind ? 'primary' : 'default'" size="small" @click="go(n.kind)">{{ n.title }}</el-button>
    </div>
    <el-alert v-if="optionsError" title="关联资料加载失败，请刷新后重试" type="error" show-icon :closable="false" />
    <el-form class="filter-panel" size="small" :inline="true" @submit.native.prevent="search">
      <el-form-item :label="kind === 'supplier' ? '供应商名称' : kind === 'purchase' ? '采购单号' : '出入库单号'">
        <el-input v-if="kind === 'supplier'" v-model="query.keyword" clearable placeholder="模糊搜索供应商" @keyup.enter.native="search" />
        <el-input v-else-if="kind === 'purchase'" v-model="query.poSn" clearable placeholder="输入完整采购单号" @keyup.enter.native="search" />
        <el-input v-else v-model="query.sn" clearable placeholder="输入完整出入库单号" @keyup.enter.native="search" />
      </el-form-item>
      <el-form-item v-if="kind === 'purchase'" label="供应商"><el-select v-model="query.supplierSn" clearable filterable placeholder="全部供应商"><el-option v-for="s in choices.suppliers" :key="s.id" :label="s.supplierName" :value="s.supplierSn" /></el-select></el-form-item>
      <el-form-item :label="kind === 'purchase' ? '单据状态' : '状态'"><el-select v-model="query.state" clearable placeholder="全部状态"><el-option v-for="(label, value) in states(kind)" :key="value" :label="label" :value="Number(value)" /></el-select></el-form-item>
      <el-form-item v-if="kind !== 'supplier'" label="收货虚仓"><el-select v-model="query.storeCode" clearable filterable placeholder="全部仓库"><el-option v-for="w in choices.warehouses" :key="w.id" :label="w.wmsSimulationName" :value="w.wmsSimulationCode" /></el-select></el-form-item>
      <el-form-item class="query-actions"><el-button type="primary" icon="el-icon-search" @click="search">搜索</el-button><el-button @click="reset">重置</el-button><el-button type="text" @click="more = !more">{{ more ? '收起筛选' : '更多筛选' }}</el-button></el-form-item>
      <div v-if="more" class="advanced-filters">
        <el-form-item v-if="kind === 'purchase'" label="采购名称"><el-input v-model="query.keyword" placeholder="模糊搜索采购名称" clearable /></el-form-item>
        <el-form-item v-if="kind === 'supplier'" label="公司全称"><el-input v-model="query.companyName" clearable /></el-form-item>
        <el-form-item v-if="kind === 'supplier'" label="供应商编码"><el-input v-model="query.supplierSn" clearable /></el-form-item>
        <template v-if="kind === 'ticket'">
          <el-form-item label="来源单号"><el-input v-model="query.poSn" clearable /></el-form-item>
          <el-form-item label="关联单号"><el-input v-model="query.noSn" clearable /></el-form-item>
          <el-form-item label="出入库类型"><el-select v-model="query.ticketType" clearable><el-option label="入库" :value="1" /><el-option label="出库" :value="2" /></el-select></el-form-item>
          <el-form-item label="入账状态"><el-select v-model="query.inventoryStatus" clearable><el-option v-for="(label, value) in entryStates" :key="value" :label="label" :value="Number(value)" /></el-select></el-form-item>
        </template>
        <el-form-item label="更新时间"><el-date-picker v-model="dates" type="daterange" value-format="yyyy-MM-dd" start-placeholder="开始日期" end-placeholder="结束日期" /></el-form-item>
      </div>
    </el-form>
    <div class="toolbar">
      <el-button v-if="kind !== 'ticket' && can(kind, 'add')" type="primary" size="small" icon="el-icon-plus" @click="edit()">{{ kind === 'supplier' ? '新增供应商' : '新增采购' }}</el-button>
      <el-button v-if="can(kind, 'export')" size="small" icon="el-icon-download" @click="exportRows">导出筛选结果</el-button>
      <el-button-group v-if="kind === 'ticket'"><el-button size="small" :type="view === 'ticket' ? 'primary' : 'default'" :aria-pressed="view === 'ticket'" @click="changeView('ticket')">单据视图</el-button><el-button size="small" :type="view === 'line' ? 'primary' : 'default'" :aria-pressed="view === 'line'" @click="changeView('line')">商品明细</el-button></el-button-group>
      <el-button v-if="canWmsLogs" size="small" @click="$refs.wmsLogs.open()">仓库交互日志</el-button>
      <el-button size="small" icon="el-icon-refresh" class="refresh" @click="refresh">刷新</el-button>
    </div>
    <div v-if="kind === 'ticket' && view === 'line'" class="line-filters"><el-input v-model="query.skuSn" size="small" placeholder="按完整 SKU 查询" clearable @keyup.enter.native="search" /><el-input v-model="query.batchCode" size="small" placeholder="按批次查询" clearable @keyup.enter.native="search" /><el-button size="small" @click="search">筛选明细</el-button></div>
    <el-alert v-if="listError" title="列表加载失败，请刷新重试" type="error" show-icon :closable="false" />
    <el-table :key="kind + '-' + view" v-loading="loading" :data="rows" empty-text="暂无符合条件的数据" row-key="id">
      <template v-if="kind === 'supplier'">
        <el-table-column label="供应商" min-width="240"><template slot-scope="s"><el-button type="text" class="name-link" :disabled="!can(kind, 'query')" @click="show(kind, s.row)">{{ s.row.supplierName }}</el-button><div class="muted code" :title="s.row.supplierSn">{{ s.row.supplierSn }}</div></template></el-table-column>
        <el-table-column label="公司全称" prop="companyName" min-width="180" show-overflow-tooltip />
        <el-table-column label="联系人 / 电话" min-width="160"><template slot-scope="s">{{ s.row.contactUser || '—' }}<div class="muted">{{ s.row.contactTel || '—' }}</div></template></el-table-column>
        <el-table-column label="所在地区" min-width="150" show-overflow-tooltip><template slot-scope="s">{{ [s.row.contactProvince, s.row.contactCity, s.row.contactArea].filter(Boolean).join(' / ') || '—' }}</template></el-table-column>
        <el-table-column label="状态" width="90"><template slot-scope="s"><el-tag :type="s.row.status === 2 ? 'success' : 'info'" size="small">{{ states(kind)[s.row.status] }}</el-tag></template></el-table-column>
        <el-table-column label="未完成采购" width="110"><template slot-scope="s"><el-button type="text" :disabled="!can('purchase', 'list')" @click="go('purchase', { supplierSn: s.row.supplierSn })">{{ s.row.openCount }}</el-button></template></el-table-column>
      </template>
      <template v-else>
        <el-table-column :label="kind === 'purchase' ? '采购单 / 名称' : '出入库单 / 来源'" :min-width="view === 'line' ? 210 : 225"><template slot-scope="s"><el-button class="name-link" type="text" :disabled="!can(kind, 'query')" @click="openRow(s.row)">{{ kind === 'purchase' ? s.row.poName : s.row.sn }}</el-button><div class="code muted" :title="kind === 'purchase' ? s.row.poSn : s.row.originalSn">{{ kind === 'purchase' ? s.row.poSn : s.row.originalSn }}</div></template></el-table-column>
        <el-table-column v-if="kind === 'purchase'" label="供应商" prop="supplierName" min-width="110" show-overflow-tooltip />
        <el-table-column v-if="view === 'line'" label="商品 / SKU" min-width="150"><template slot-scope="s">{{ s.row.goodsName }}<div class="muted">{{ s.row.skuSn }}</div></template></el-table-column>
        <el-table-column v-if="view === 'line'" label="批次" prop="batchCode" min-width="120" show-overflow-tooltip />
        <el-table-column label="货主 / 收货仓库" :min-width="view === 'line' ? 150 : 170"><template slot-scope="s">{{ s.row.ownerName || '—' }}<div class="muted">{{ s.row.realWarehouseName || '—' }} / {{ s.row.warehouseName || s.row.wmsSimulationCode }}</div></template></el-table-column>
        <el-table-column v-if="view !== 'line'" :label="kind === 'purchase' ? '单据状态 / 到货' : '执行状态'" :width="kind === 'purchase' ? 180 : 115"><template slot-scope="s">
          <el-tag size="small" :type="s.row.stateNeedsReview ? 'danger' : stateColor(kind === 'purchase' ? s.row.poState : s.row.statusTicket)">{{ documentState(kind, s.row) }}</el-tag>
          <template v-if="kind === 'purchase'"><div v-if="s.row.receiptCount"><el-button type="text" size="mini" :disabled="!can('purchase', 'query')" @click="showReceipts(s.row)">到货单 {{ s.row.receiptCount }} 张 · 查看</el-button></div><div v-else class="muted">尚无到货单</div><div v-if="s.row.receiptCount" class="muted">采购计划已锁定</div></template>
        </template></el-table-column>
        <el-table-column v-if="kind === 'ticket'" label="库存入账" width="110"><template slot-scope="s"><el-tag size="small" :type="entryType(s.row)">{{ entryLabel(s.row) }}</el-tag></template></el-table-column>
        <el-table-column :label="kind === 'purchase' ? '计划 / 已入库' : '计划 / 实收'" width="130" align="right"><template slot-scope="s">{{ s.row.numberExpected }} / {{ s.row.numberActually }}<div v-if="kind === 'purchase'" class="muted">待入库 {{ s.row.remainingQuantity }}</div></template></el-table-column>
        <el-table-column v-if="kind === 'purchase'" label="计划 / 实际金额" width="140" align="right"><template slot-scope="s">¥ {{ amount(s.row.moneyExpected) }}<div class="muted">¥ {{ amount(s.row.moneyActually) }}</div></template></el-table-column>
        <el-table-column v-if="kind === 'purchase'" label="预计到货" width="115"><template slot-scope="s">{{ date(s.row.expectedDate) }}</template></el-table-column>
        <el-table-column v-if="view === 'line'" label="差异 / 失败原因" min-width="160"><template slot-scope="s">差异 {{ s.row.numberDifActually }}<div class="error-text">{{ s.row.errorInfo }}</div></template></el-table-column>
      </template>
      <el-table-column label="操作" fixed="right" :width="kind === 'supplier' ? 170 : view === 'line' ? 90 : 115"><template slot-scope="s">
        <el-button v-if="can(kind, 'query')" type="text" size="mini" @click="openRow(s.row)">详情</el-button>
        <el-button v-if="kind === 'supplier' && can(kind, 'edit')" type="text" size="mini" @click="edit(s.row)">编辑</el-button>
        <el-tooltip v-if="kind === 'purchase' && s.row.poState === 1 && can(kind, 'edit')" :disabled="s.row.editable === true" :content="s.row.editBlockReason || '当前采购计划不可编辑'" placement="top"><span class="disabled-action"><el-button type="text" size="mini" :disabled="s.row.editable !== true" @click="edit(s.row)">编辑</el-button></span></el-tooltip>
        <el-button v-if="kind === 'supplier' && can('purchase', 'add')" :disabled="s.row.status !== 2" type="text" size="mini" @click="newForSupplier(s.row)">采购</el-button>
        <el-button v-if="kind === 'supplier' && can(kind, 'remove')" type="text" size="mini" class="danger" @click="remove(s.row)">删除</el-button>
      </template></el-table-column>
    </el-table>
    <pagination v-show="total > 0" :total="total" :page.sync="query.pageNum" :limit.sync="query.pageSize" @pagination="load" />

    <el-dialog :title="(form.id ? '编辑' : '新增') + (kind === 'supplier' ? '供应商' : '采购单')" :visible.sync="editing" :width="kind === 'supplier' ? '760px' : '92%'" append-to-body :close-on-click-modal="false" :before-close="closeEdit">
      <el-form ref="form" :model="form" :rules="rules" label-position="top" class="edit-form">
        <template v-if="kind === 'supplier'">
          <el-form-item label="供应商简称" prop="supplierName"><el-input v-model="form.supplierName" maxlength="100" /></el-form-item>
          <el-form-item label="公司全称" prop="companyName"><el-input v-model="form.companyName" maxlength="200" /></el-form-item>
          <el-form-item label="联系人"><el-input v-model="form.contactUser" maxlength="50" /></el-form-item>
          <el-form-item label="联系电话" prop="contactTel"><el-input v-model="form.contactTel" maxlength="40" /></el-form-item>
          <el-form-item label="省"><el-select v-model="form.contactProvince" filterable allow-create clearable placeholder="选择或输入省份" @change="regionChanged('province')"><el-option v-for="v in regionOptions('contactProvince')" :key="v" :label="v" :value="v" /></el-select></el-form-item>
          <el-form-item label="市"><el-select v-model="form.contactCity" filterable allow-create clearable :disabled="!form.contactProvince" placeholder="选择或输入城市" @change="regionChanged('city')"><el-option v-for="v in regionOptions('contactCity')" :key="v" :label="v" :value="v" /></el-select></el-form-item>
          <el-form-item label="区 / 县"><el-select v-model="form.contactArea" filterable allow-create clearable :disabled="!form.contactCity" placeholder="选择或输入区县"><el-option v-for="v in regionOptions('contactArea')" :key="v" :label="v" :value="v" /></el-select></el-form-item>
          <el-form-item label="详细地址"><el-input v-model="form.contactAddress" maxlength="255" /></el-form-item>
          <el-form-item label="状态"><el-radio-group v-model="form.status"><el-radio :label="2">启用</el-radio><el-radio :label="1">停用</el-radio></el-radio-group></el-form-item>
        </template>
        <template v-else>
          <el-form-item label="采购名称" prop="poName"><el-input v-model="form.poName" maxlength="100" /></el-form-item>
          <el-form-item label="供应商" prop="supplierSn"><el-select v-model="form.supplierSn" filterable placeholder="选择启用供应商"><el-option v-for="s in choices.suppliers" :key="s.id" :label="s.supplierName + (s.status === 2 ? '' : '（停用）')" :value="s.supplierSn" :disabled="s.status !== 2" /></el-select></el-form-item>
          <el-form-item label="收货虚仓" prop="wmsSimulationCode"><el-select v-model="form.wmsSimulationCode" filterable placeholder="选择业务可用虚仓"><el-option v-for="w in choices.warehouses" :key="w.id" :label="w.wmsSimulationName + ' / ' + w.wmsSimulationCode" :value="w.wmsSimulationCode" :disabled="!w.effectiveEnabled" /></el-select></el-form-item>
          <el-form-item label="预计到货日期"><el-date-picker v-model="form.expectedDate" type="date" value-format="yyyy-MM-dd" /></el-form-item>
          <div v-if="chosenWarehouse" class="span-all warehouse-summary">货主：{{ chosenWarehouse.ownerName }}　实仓：{{ chosenWarehouse.wmsName }}　入库方式：{{ chosenWarehouse.inboundMode === 2 ? '自动虚拟入库' : chosenWarehouse.inboundMode === 1 ? 'WMS 回传入库' : '待配置' }}</div>
          <el-form-item label="备注" class="span-all"><el-input v-model="form.remarks" type="textarea" maxlength="2000" /></el-form-item>
          <div class="span-all">
            <div class="toolbar"><el-button size="small" @click="openProducts">选择商品</el-button><el-button size="small" @click="addLine">手工添加 SKU</el-button><el-button size="small" :loading="importing" @click="$refs.importFile.click()">导入商品</el-button><el-button size="small" type="text" @click="template">下载模板</el-button><input ref="importFile" type="file" accept=".xlsx,.xls" hidden @change="importFile" /></div>
            <el-alert v-if="importError" :title="importError" type="error" :closable="false" />
            <el-table :data="form.lines" max-height="370"><el-table-column label="SKU" min-width="180"><template slot-scope="s"><el-input v-model.trim="s.row.skuSn" size="small" maxlength="100" /></template></el-table-column><el-table-column label="商品名称" prop="goodsName" min-width="160" /><el-table-column label="采购数量" width="180"><template slot-scope="s"><el-input-number v-model="s.row.quantity" :min="1" :max="100000000" :precision="0" size="small" /></template></el-table-column><el-table-column label="采购单价" width="180"><template slot-scope="s"><el-input-number v-model="s.row.purchasePrice" :min="0" :max="999999999" :precision="2" size="small" /></template></el-table-column><el-table-column label="行金额" width="120" align="right"><template slot-scope="s">{{ amount(s.row.quantity * s.row.purchasePrice) }}</template></el-table-column><el-table-column label="操作" width="80"><template slot-scope="s"><el-button type="text" class="danger" @click="form.lines.splice(s.$index, 1)">移除</el-button></template></el-table-column></el-table>
            <div class="totals">合计 {{ plannedQuantity }} 件，¥ {{ amount(plannedAmount) }}</div>
          </div>
        </template>
      </el-form>
      <div slot="footer"><el-button :disabled="saving" @click="editing = false">取消</el-button><el-button type="primary" :loading="saving" @click="saveForm">{{ kind === 'supplier' ? '保存供应商' : '保存草稿' }}</el-button></div>
    </el-dialog>

    <el-dialog title="选择采购商品" :visible.sync="productsOpen" width="850px" append-to-body><el-input v-model="productKeyword" placeholder="输入商品名称" @keyup.enter.native="loadProducts"><el-button slot="append" @click="loadProducts">搜索</el-button></el-input><el-table v-loading="productsLoading" :data="products"><el-table-column label="商品" prop="goodsName" /><el-table-column label="SKU" prop="skuSn" /><el-table-column label="操作" width="90"><template slot-scope="s"><el-button type="text" :disabled="form.lines.some(l => l.skuSn === s.row.skuSn)" @click="selectProduct(s.row)">添加</el-button></template></el-table-column></el-table><pagination :total="productTotal" :page.sync="productPage" :limit.sync="productSize" @pagination="loadProducts" /><div slot="footer"><el-button @click="productsOpen = false">完成选择</el-button></div></el-dialog>

    <el-drawer :title="detailTitle" :visible.sync="drawer" custom-class="purchase-detail-drawer" size="86%" append-to-body :wrapper-closable="false">
      <div v-loading="detailLoading" class="document-detail">
        <el-alert v-if="detailError" title="详情加载失败，请关闭后重试" type="error" :closable="false" />
        <template v-if="document">
          <div class="detail-heading"><h2>{{ document.poName || document.supplierName || document.noName || document.sn }}</h2><div class="detail-code"><span class="code">{{ document[codeKey(detailKind)] }}</span><el-button size="mini" type="text" @click="copy(document[codeKey(detailKind)])">复制单号</el-button></div></div>
          <div v-if="['purchase', 'receipt'].includes(detailKind)" class="status-summary">
            <div class="status-heading"><strong>{{ detailKind === 'purchase' ? '采购单状态' : '到货单状态' }}</strong><el-tag :type="document.stateNeedsReview ? 'danger' : stateColor(detailKind === 'purchase' ? document.poState : document.noState)">{{ documentState(detailKind, document) }}</el-tag></div>
            <p>{{ detailKind === 'purchase' ? purchaseNotice : receiptNotice }}</p>
            <div v-if="detailKind === 'purchase'" class="status-links"><span>计划 {{ document.numberExpected }} 件 / 已入库 {{ document.numberActually }} 件</span><el-button v-if="document.receiptCount" type="text" size="small" @click="detailTab = 'receipts'">查看 {{ document.receiptCount }} 张到货单</el-button><span v-else class="muted">尚无到货单</span><span v-if="document.editBlockReason" class="muted">{{ document.editBlockReason }}</span></div>
            <div v-else class="status-links"><span>计划 {{ document.numberExpected }} 件 / 仓库实收 {{ receiptReceived }} 件 / 已入库 {{ document.numberActually }} 件</span><el-button v-if="can('purchase', 'query') && can('purchase', 'list')" type="text" size="small" @click="showSourcePurchase">查看来源采购</el-button><el-button v-if="document.tickets.length" type="text" size="small" @click="detailTab = 'tickets'">查看 {{ document.tickets.length }} 张入库单</el-button></div>
          </div>
          <wms-inbound-panel v-if="detailKind === 'ticket' && document.actualWarehouse === 1" :key="document.id" :ticket-id="Number(document.id)" @changed="show(detailKind, document, true); load()" />
          <wms-inbound-panel v-for="t in detailKind === 'receipt' && document.actualWarehouse === 1 ? document.tickets : []" :key="'wms-' + t.id" :ticket-id="Number(t.id)" @changed="show(detailKind, document, true); load()" />
          <el-steps v-if="detailKind === 'receipt' && [2, 3, 4].includes(document.noState)" :active="receiptStep" :process-status="document.tickets.some(t => t.inventoryStatus === 3) ? 'error' : 'process'" finish-status="success" simple class="receipt-steps"><el-step title="审核到货单" /><el-step title="确认收货" /><el-step title="库存入账" /></el-steps>
          <el-descriptions :column="3" border size="small" class="summary"><el-descriptions-item v-for="f in detailFields" :key="f[0]" :label="f[1]">{{ display(f[0]) }}</el-descriptions-item></el-descriptions>
          <div class="toolbar" v-if="detailKind === 'supplier'"><el-button v-if="can('purchase', 'list')" size="small" @click="go('purchase', { supplierSn: document.supplierSn })">查看关联采购</el-button><el-button v-if="can('purchase', 'add')" :disabled="document.status !== 2" type="primary" size="small" @click="newForSupplier(document)">发起采购</el-button></div>
          <div class="toolbar">
            <template v-if="detailKind === 'purchase'">
              <el-button v-if="document.poState === 1 && !document.stateNeedsReview && can('purchase', 'approve')" type="primary" size="small" :loading="acting" @click="perform('approve', '确认审核采购计划？审核后商品、供应商和仓库将锁定。')">审核采购</el-button>
              <el-button v-if="[2, 3].includes(document.poState) && can('purchase', 'receive')" type="primary" size="small" :disabled="availableReceiptQuantity === 0" @click="openReceipt">创建到货单</el-button>
              <el-button v-if="[2, 3].includes(document.poState) && can('purchase', 'close')" size="small" :disabled="acting || document.receipts.some(n => ![-1, 4].includes(n.noState))" @click="reasonAction('close')">关闭剩余采购</el-button>
            </template>
            <el-button v-if="detailKind === 'receipt' && document.noState === 2 && can('purchase', 'approve')" type="primary" size="small" :loading="acting" @click="perform('approve', document.actualWarehouse === 2 ? '当前为虚拟出入库，审核后将按本次到货计划数量自动收货并增加库存。确认审核？' : '确认审核到货单并生成入库执行单？真实仓库需等待 WMS 回传后入账。')">审核到货单</el-button>
            <template v-if="detailKind === 'ticket'">
              <el-alert v-if="document.actualWarehouse === 1 && document.statusTicket < 2" title="真实仓库等待 WMS 对接回传，当前不能人工模拟入库。" type="info" :closable="false" />
              <el-button v-if="document.actualWarehouse === 2 && document.statusTicket === 1 && document.ticketType === 1 && can('ticket', 'retry')" type="primary" size="small" :loading="acting" @click="perform('completeVirtual', '按已审核到货计划自动收货并增加库存，确认继续？')">完成虚拟入库</el-button>
              <el-button v-if="document.statusTicket === 2 && document.ticketType === 1 && [0, 3].includes(document.inventoryStatus) && can('ticket', 'retry')" type="primary" size="small" :loading="acting" @click="perform('post', '按已确认的实收数量更新库存；重试只处理尚未成功的明细。确认继续？')">{{ document.inventoryStatus === 3 ? '重试失败明细' : '库存入账' }}</el-button>
              <el-button v-if="can('purchase', 'list')" size="small" @click="go('purchase', { poSn: document.originalSn })">查看来源采购</el-button>
              <el-button size="small" @click="inventoryLink">查看仓库库存</el-button>
            </template>
            <el-button v-if="cancellable && can(detailKind, 'remove')" size="small" type="danger" plain :disabled="acting" @click="reasonAction('cancel')">作废单据</el-button>
          </div>
          <el-tabs v-model="detailTab">
            <el-tab-pane v-if="document.lines" label="商品明细" name="lines">
              <el-table :key="detailKind + document.id" :data="document.lines" max-height="450"><el-table-column label="商品 / SKU" min-width="180"><template slot-scope="s">{{ s.row.goodsName }}<div class="muted">{{ s.row.skuSn }}</div></template></el-table-column><el-table-column label="单价" width="110" align="right"><template slot-scope="s">{{ amount(s.row.purchasePrice) }}</template></el-table-column><el-table-column label="计划数量" width="110"><template slot-scope="s">{{ lineExpected(s.row) }}</template></el-table-column><el-table-column :label="detailKind === 'ticket' ? '仓库实收数量' : '已入库数量'" width="150"><template slot-scope="s">{{ lineReceived(s.row) }}</template></el-table-column><el-table-column v-if="detailKind === 'purchase'" label="剩余可安排到货" width="150"><template slot-scope="s">{{ s.row.quantity - s.row.scheduledQuantity }}</template></el-table-column><el-table-column v-if="detailKind === 'ticket'" label="正品 / 次品" width="120"><template slot-scope="s">{{ s.row.numberZp }} / {{ s.row.numberCp }}</template></el-table-column><el-table-column v-if="detailKind === 'ticket'" label="批次" prop="batchCode" min-width="170" show-overflow-tooltip /><el-table-column v-if="detailKind === 'ticket'" label="入账结果" min-width="220"><template slot-scope="s">{{ entryStates[s.row.inventoryIsHandle] || '状态异常' }}<div class="error-text">{{ s.row.errorInfo }}</div></template></el-table-column></el-table>
            </el-tab-pane>
            <el-tab-pane v-if="document.receipts" :label="'到货单（' + document.receipts.length + '）'" name="receipts">
              <el-alert title="到货单用于安排分批收货。虚仓采用自动虚拟入库时，审核后自动收货、入账；采用 WMS 回传入库时，审核后下发仓库。仅入账成功才显示已入库并更新采购数量。" type="info" :closable="false" show-icon />
              <el-table :data="document.receipts" empty-text="尚无到货单；审核采购后可安排分批到货。"><el-table-column label="到货单 / 批次" min-width="265"><template slot-scope="s"><el-button type="text" :disabled="!can('purchase', 'query')" @click="show('receipt', s.row)">{{ s.row.noSn }}</el-button><div class="muted">批次：{{ s.row.batchCode || '—' }}</div></template></el-table-column><el-table-column label="到货状态" width="145"><template slot-scope="s"><el-tag size="small" :type="stateColor(s.row.noState)">{{ documentState('receipt', s.row) }}</el-tag></template></el-table-column><el-table-column label="计划 / 已入库" width="140"><template slot-scope="s">{{ s.row.numberExpected }} / {{ s.row.numberActually }}</template></el-table-column><el-table-column label="预计到货日期" width="120"><template slot-scope="s">{{ date(s.row.expectedCallbackTime) }}</template></el-table-column><el-table-column label="创建时间" width="160"><template slot-scope="s">{{ parseTime(s.row.createTime) }}</template></el-table-column><el-table-column label="操作" width="150" fixed="right"><template slot-scope="s"><el-button v-if="can('purchase', 'query')" type="text" @click="show('receipt', s.row)">{{ s.row.noState === 2 && can('purchase', 'approve') ? '查看并审核' : s.row.noState === 3 ? '查看入库进度' : '详情' }}</el-button></template></el-table-column></el-table>
            </el-tab-pane>
            <el-tab-pane v-if="document.tickets" :label="'关联出入库单（' + document.tickets.length + '）'" name="tickets"><el-table :data="document.tickets"><el-table-column label="单号" prop="sn" min-width="250" show-overflow-tooltip /><el-table-column label="执行状态"><template slot-scope="s">{{ states('ticket')[s.row.statusTicket] }}</template></el-table-column><el-table-column label="入账状态"><template slot-scope="s">{{ entryLabel(s.row) }}</template></el-table-column><el-table-column label="操作" width="90"><template slot-scope="s"><el-button v-if="can('ticket', 'query')" type="text" @click="show('ticket', s.row)">详情</el-button></template></el-table-column></el-table></el-tab-pane>
            <el-tab-pane v-if="document.purchases" label="关联采购" name="purchases"><el-table :data="document.purchases"><el-table-column label="采购名称" prop="poName" /><el-table-column label="采购单号" prop="poSn" min-width="260" show-overflow-tooltip /><el-table-column label="状态"><template slot-scope="s">{{ documentState('purchase', s.row) }}</template></el-table-column><el-table-column label="操作"><template slot-scope="s"><el-button v-if="can('purchase', 'query')" type="text" @click="show('purchase', s.row)">详情</el-button></template></el-table-column></el-table></el-tab-pane>
            <el-tab-pane label="操作记录" name="events"><el-timeline v-if="document.events.length"><el-timeline-item v-for="e in document.events" :key="e.id" :timestamp="parseTime(e.createTime)">{{ e.action }} · {{ e.operator }}<div class="muted">{{ e.message }}</div></el-timeline-item></el-timeline><el-empty v-else description="暂无操作记录" /></el-tab-pane>
          </el-tabs>
        </template>
      </div>
      <footer class="detail-footer" aria-label="详情导航">
        <el-button v-if="history.length" size="small" icon="el-icon-arrow-left" :disabled="detailLoading" @click="backDetail">返回上级详情</el-button>
        <el-button size="small" @click="drawer = false">关闭</el-button>
      </footer>
    </el-drawer>

    <el-dialog :title="quantityMode === 'receipt' ? '安排本次到货' : '确认虚拟收货'" :visible.sync="quantityOpen" width="850px" append-to-body :close-on-click-modal="false" :before-close="closeQuantity">
      <el-alert :title="quantityMode === 'receipt' ? '到货单继承采购的供应商、货主、实仓和虚仓；数量为 0 的商品不进入本次到货单。' : '确认后实收数量锁定；下一步库存入账才会增加库存。次品数量计入本次实收总量。'" type="info" show-icon :closable="false" />
      <el-form v-if="quantityMode === 'receipt'" label-position="top" class="receipt-options" size="small"><el-form-item label="本次预计到货日期"><el-date-picker v-model="receiptForm.expectedDate" type="date" value-format="yyyy-MM-dd" placeholder="选择本批次到货日期" /></el-form-item><el-form-item label="本次到货备注"><el-input v-model="receiptForm.remarks" maxlength="255" show-word-limit placeholder="填写送货约定等信息" /></el-form-item></el-form>
      <el-table :key="quantityMode" :data="quantityLines"><el-table-column label="商品 / SKU" min-width="220"><template slot-scope="s">{{ s.row.goodsName }}<div class="muted">{{ s.row.skuSn }}</div></template></el-table-column><el-table-column :label="quantityMode === 'receipt' ? '可安排数量' : '计划数量'" width="120" prop="maximum" /><el-table-column :label="quantityMode === 'receipt' ? '本次到货' : '实收正品'" width="190"><template slot-scope="s"><el-input-number v-if="quantityMode === 'receipt'" v-model="s.row.quantity" :min="0" :max="s.row.maximum" :precision="0" size="small" /><el-input-number v-else v-model="s.row.numberZp" :min="0" :max="s.row.maximum - s.row.numberCp" :precision="0" size="small" /></template></el-table-column><el-table-column v-if="quantityMode === 'receive'" label="实收次品" width="190"><template slot-scope="s"><el-input-number v-model="s.row.numberCp" :min="0" :max="s.row.maximum - s.row.numberZp" :precision="0" size="small" /></template></el-table-column></el-table>
      <div v-if="quantityMode === 'receipt'" class="totals">本次计划 {{ quantityLines.reduce((sum, l) => sum + (Number(l.quantity) || 0), 0) }} 件；确认后生成待审核到货单</div>
      <div slot="footer"><el-button :disabled="acting" @click="quantityOpen = false">取消</el-button><el-button type="primary" :loading="acting" @click="submitQuantities">{{ quantityMode === 'receipt' ? '创建待审核到货单' : '确认实收数量' }}</el-button></div>
    </el-dialog>
  </div>
</template>

<script>
import * as api from '@/api/purchase/workspace'
import WmsInboundPanel from '../warehouse/WmsInboundPanel'
import WmsLogs from '../warehouse/WmsLogs'
import { listInfo } from '@/api/goods/info'
import { checkPermi } from '@/utils/permission'
import { supplierRegionOptions } from '@/utils/supplierRegions'
const routes = { supplier: 'supplier', purchase: 'poInfo', ticket: 'wmsTickets' }
const names = { supplier: '供应商', purchase: '采购单', receipt: '到货单', ticket: '出入库单' }
const defaults = () => ({ pageNum: 1, pageSize: 10, keyword: '', supplierSn: '', companyName: '', state: null, poSn: '', noSn: '', sn: '', storeCode: '', ticketType: null, inventoryStatus: null, skuSn: '', batchCode: '' })
export default {
  components: { WmsInboundPanel, WmsLogs },
  name: 'PurchaseWorkspace', props: { kind: { type: String, required: true } },
  data() { return { query: defaults(), dates: [], more: false, rows: [], total: 0, loading: false, listError: false, sequence: 0, choices: { suppliers: [], warehouses: [] }, optionsError: false, view: 'ticket', editing: false, form: { lines: [] }, saving: false, importing: false, importError: '', drawer: false, document: null, detailKind: '', detailLoading: false, detailError: false, detailSequence: 0, detailTab: 'lines', history: [], acting: false, quantityOpen: false, quantityMode: '', quantityLines: [], receiptForm: { expectedDate: null, remarks: '' }, productsOpen: false, productKeyword: '', productPage: 1, productSize: 10, productTotal: 0, products: [], productsLoading: false, entryStates: { 0: '待入账', 1: '处理中', 2: '入账成功', 3: '入账异常' } } },
  computed: {
    canWmsLogs() { return checkPermi(['warehouse:wms:log']) },
    navigation() { return Object.keys(routes).filter(k => this.can(k, 'list')).map(kind => ({ kind, title: names[kind] })) },
    detailTitle() { return (names[this.detailKind] || '') + '详情' },
    chosenWarehouse() { return this.choices.warehouses.find(w => w.wmsSimulationCode === this.form.wmsSimulationCode) },
    plannedQuantity() { return this.form.lines.reduce((sum, l) => sum + (Number(l.quantity) || 0), 0) },
    plannedAmount() { return this.form.lines.reduce((sum, l) => sum + (Number(l.quantity) || 0) * (Number(l.purchasePrice) || 0), 0) },
    rules() { const required = label => [{ required: true, message: '请填写或选择' + label, trigger: 'change' }]; return { supplierName: required('供应商简称'), companyName: required('公司全称'), poName: required('采购名称'), supplierSn: required('供应商'), wmsSimulationCode: required('收货虚仓'), contactTel: [{ pattern: /^[0-9+()\- #]{3,40}$/, message: '请输入有效联系电话', trigger: 'blur' }] } },
    detailFields() { if (this.detailKind === 'supplier') return [['companyName', '公司全称'], ['contactUser', '联系人'], ['contactTel', '电话'], ['contactProvince', '省'], ['contactCity', '市'], ['contactArea', '区 / 县'], ['contactAddress', '地址'], ['status', '状态'], ['openCount', '未完成采购']]; const f = [['ownerName', '货主'], ['realWarehouseName', '实体仓库'], ['warehouseName', '虚拟仓库'], ['numberExpected', '计划数量'], ['numberActually', this.detailKind === 'ticket' ? '仓库实收数量' : '已入库数量']]; if (this.detailKind === 'purchase') f.push(['supplierName', '供应商'], ['poState', '单据状态'], ['moneyExpected', '计划金额'], ['moneyActually', '实际金额'], ['expectedDate', '预计到货'], ['remarks', '备注'], ['closeReason', '关闭 / 作废原因']); else if (this.detailKind === 'receipt') f.push(['noState', '到货状态'], ['poSn', '采购单号'], ['batchCode', '批次'], ['expectedCallbackTime', '预计到货日期'], ['remarks', '到货备注'], ['reviewerUser', '审核人'], ['reviewerTime', '审核时间']); else f.push(['ticketType', '出入库类型'], ['statusTicket', '执行状态'], ['inventoryStatus', '入账状态'], ['originalSn', '来源单号'], ['relationSn', '关联单号'], ['wmsActuallyTime', '仓库执行时间']); return f },
    availableReceiptQuantity() { return this.detailKind === 'purchase' && this.document ? this.document.lines.reduce((sum, l) => sum + Math.max(0, l.quantity - l.scheduledQuantity), 0) : 0 },
    purchaseNotice() { const d = this.document; if (!d || this.detailKind !== 'purchase') return ''; if ([2, 3].includes(d.poState) && this.availableReceiptQuantity === 0) return '采购商品已全部安排到货，请处理关联到货单及库存入账。全部入账成功后采购才会完成；在途批次处理完毕前不能关闭剩余采购。'; return d.stateNotice },
    receiptReceived() { return this.detailKind === 'receipt' && this.document ? this.document.tickets.reduce((sum, t) => sum + Number(t.numberActually || 0), 0) : 0 },
    receiptStep() { const d = this.document; return d.noState === 4 ? 3 : d.noState === 2 ? 0 : d.tickets.some(t => t.statusTicket === 2) ? 2 : 1 },
    receiptNotice() { const d = this.document; if (!d || this.detailKind !== 'receipt') return ''; return { 1: '尚未提交审核，未产生入库结果。', 2: d.actualWarehouse === 2 ? '虚拟出入库：核对本批商品与数量后审核，系统会按本次计划自动收货并入账，无需再次确认收货。' : '真实出入库：审核后生成入库单，等待 WMS 回传收货结果后入账。', 3: d.tickets.some(t => t.inventoryStatus === 3) ? '库存入账异常，请进入关联入库单查看失败原因并重试。' : '下一步：进入关联入库单处理收货及库存入账；仓库执行完成不等于库存入账成功。', 4: '本批次库存入账已完成，可查看实收数量与关联入库单。', '-1': '到货单已作废，原始记录保留，不再安排本批次入库。' }[d.noState] || '未知状态，请核对单据。' },
    cancellable() { const d = this.document; return d && (this.detailKind === 'purchase' && [1, 2].includes(d.poState) || this.detailKind === 'receipt' && [1, 2].includes(d.noState) || this.detailKind === 'ticket' && d.actualWarehouse === 2 && d.statusTicket <= 1 && d.statusNotify === 0 && d.inventoryStatus === 0) }
  },
  watch: { '$route.query'() { if (this.$route.path === '/oms-supplychain/' + routes[this.kind]) { this.readRoute(); this.load(); this.routeCreate() } } },
  created() { this.readRoute(); this.refresh().then(this.routeCreate) },
  methods: {
    can(kind, action) { return checkPermi([(kind === 'supplier' ? 'warehouse:supplier:' : ['purchase', 'receipt'].includes(kind) ? 'warehouse:poInfo:' : 'warehouse:tickets:') + action]) },
    states(kind) { return kind === 'supplier' ? { 1: '停用', 2: '启用' } : kind === 'purchase' ? { 1: '草稿', 2: '待收货', 3: '收货中', 4: '已完成', 5: '已关闭', '-1': '已作废' } : kind === 'receipt' ? { 1: '草稿', 2: '待审核', 3: '待入库', 4: '已入库', '-1': '已作废' } : { 0: '待确认', 1: '待执行', 2: '执行完成', 3: '执行失败', 4: '待作废', 5: '已作废', 6: '作废失败' } },
    documentState(kind, row) { if (kind === 'purchase' && row.stateLabel) return row.stateLabel; const value = row[{ purchase: 'poState', receipt: 'noState', ticket: 'statusTicket' }[kind]]; return this.states(kind)[value] || '状态异常' },
    async showReceipts(row) { this.history = []; await this.show('purchase', row); if (this.document && this.document.id === row.id) this.detailTab = 'receipts' },
    async showSourcePurchase() { const result = await api.list('purchase', { poSn: this.document.poSn, pageSize: 1 }); if (result.rows.length) await this.show('purchase', result.rows[0]); else this.$modal.msgWarning('未找到来源采购单，请核对单据关联') },
    stateColor(n) { return n < 0 || n === 5 ? 'info' : n === 4 ? 'success' : '' }, codeKey(kind) { return { supplier: 'supplierSn', purchase: 'poSn', receipt: 'noSn', ticket: 'sn' }[kind] },
    entryLabel(row) { return this.entryStates[this.view === 'line' && row.inventoryIsHandle !== undefined ? row.inventoryIsHandle : row.inventoryStatus] || '状态异常' },
    entryType(row) { return (row.inventoryIsHandle === 3 || row.inventoryStatus === 3) ? 'danger' : row.inventoryStatus === 2 ? 'success' : 'warning' },
    amount(v) { return Number(v || 0).toFixed(2) }, date(v) { return v ? typeof v === 'number' ? this.parseTime(v, '{y}-{m}-{d}') : String(v).slice(0, 10) : '—' },
    regionChanged(level) { if (level === 'province') this.$set(this.form, 'contactCity', ''); this.$set(this.form, 'contactArea', '') },
    regionOptions(field) { return supplierRegionOptions(field, this.form) },
    params() { const q = { ...this.query, beginTime: (this.dates || [])[0], endTime: (this.dates || [])[1] }; if (this.kind === 'supplier') { q.status = q.state; delete q.state } return q },
    readRoute() { const q = defaults(); Object.keys(q).forEach(k => { if (this.$route.query[k] !== undefined) q[k] = ['state', 'pageNum', 'pageSize'].includes(k) ? Number(this.$route.query[k]) : this.$route.query[k] }); this.query = q; this.more = !!(q.poSn && this.kind === 'ticket' || q.noSn) },
    async load() { const seq = ++this.sequence; this.loading = true; this.listError = false; try { const r = await api.list(this.kind === 'ticket' ? this.view : this.kind, this.params()); if (seq === this.sequence) { this.rows = r.rows; this.total = r.total } } catch (_) { if (seq === this.sequence) { this.rows = []; this.total = 0; this.listError = true } } finally { if (seq === this.sequence) this.loading = false } },
    async refresh() { await Promise.all([this.load(), this.loadOptions()]) }, async loadOptions() { try { this.choices = (await api.options()).data; this.optionsError = false } catch (_) { this.optionsError = true } },
    search() { this.query.pageNum = 1; this.load() }, reset() { this.query = defaults(); this.dates = []; if (Object.keys(this.$route.query).length) this.$router.replace({ path: this.$route.path, query: {} }); else this.load() }, changeView(view) { this.view = view; this.search() },
    go(kind, query = {}) { this.drawer = false; this.$router.push({ path: '/oms-supplychain/' + routes[kind], query }).catch(() => this.load()) },
    newForSupplier(row) { this.go('purchase', { supplierSn: row.supplierSn, create: '1' }) }, routeCreate() { if (this.kind === 'purchase' && this.$route.query.create === '1' && this.can('purchase', 'add')) this.edit() },
    async edit(row) { const fresh = row ? (await api.detail(this.kind, row.id)).data : null; if (this.kind === 'purchase' && fresh && fresh.editable !== true) { this.$modal.msgWarning(fresh.editBlockReason || '当前采购计划不可编辑'); if (this.can('purchase', 'query')) await this.showReceipts(fresh); this.load(); return } await this.loadOptions(); if (this.optionsError) return; this.importError = ''; this.form = fresh ? { ...fresh } : { status: 2, supplierSn: this.query.supplierSn || '', poName: '', wmsSimulationCode: '', expectedDate: null, remarks: '', lines: [] }; if (!this.form.lines) this.form.lines = []; this.form.expectedDate = this.form.expectedDate ? this.date(this.form.expectedDate) : null; this.editing = true; this.$nextTick(() => this.$refs.form.clearValidate()) },
    closeEdit(done) { if (!this.saving && !this.importing) done() }, closeQuantity(done) { if (!this.acting) done() },
    saveForm() { if (this.saving) return; this.$refs.form.validate(async valid => { if (!valid) return; if (this.kind === 'purchase' && !this.form.lines.length) return this.$modal.msgError('请先添加采购商品'); this.saving = true; try { await api.save(this.kind, this.form); this.editing = false; this.$modal.msgSuccess('保存成功'); await this.refresh() } catch (_) { /* Preserve the complete form for correction. */ } finally { this.saving = false } }) },
    addLine() { if (this.form.lines.length >= 200) return this.$modal.msgError('每单最多 200 行'); this.form.lines.push({ skuSn: '', goodsName: '', quantity: 1, purchasePrice: 0 }) },
    async remove(row) { try { await this.$modal.confirm('删除供应商“' + row.supplierName + '”？已有采购记录的供应商只能停用。'); await api.removeSupplier(row.id); this.$modal.msgSuccess('删除成功'); this.refresh() } catch (_) {} },
    exportRows() { this.download(`supplychain/purchaseWorkspace/${this.kind === 'ticket' ? this.view : this.kind}/export`, this.params(), `${names[this.kind]}_${Date.now()}.xlsx`) },
    template() { this.download('supplychain/purchaseWorkspace/importTemplate', {}, '采购商品模板.xlsx') },
    async importFile(e) { const file = e.target.files[0]; e.target.value = ''; if (!file) return; this.importing = true; this.importError = ''; try { const r = await api.previewImport(file); if (this.form.lines.length) await this.$modal.confirm('导入校验通过，将替换当前草稿中的商品明细。确认替换？'); this.form.lines = r.data; this.$modal.msgSuccess('已校验 ' + r.data.length + ' 行，保存草稿后生效') } catch (error) { if (error !== 'cancel' && error !== 'close') this.importError = error.message || '导入失败，请按提示修正；当前明细未更改' } finally { this.importing = false } },
    openProducts() { this.productsOpen = true; this.productPage = 1; this.loadProducts() },
    async loadProducts() { this.productsLoading = true; try { const r = await listInfo({ goodsName: this.productKeyword, pageNum: this.productPage, pageSize: this.productSize }); this.products = r.rows; this.productTotal = r.total } finally { this.productsLoading = false } },
    selectProduct(row) { if (this.form.lines.length >= 200) return this.$modal.msgError('每单最多 200 行'); this.form.lines.push({ skuSn: row.skuSn, goodsName: row.goodsName, quantity: 1, purchasePrice: 0 }) },
    async openRow(row) { this.history = []; if (this.kind === 'ticket' && this.view === 'line') { const r = await api.list('ticket', { sn: row.sn, pageSize: 1 }); if (r.rows.length) return this.show('ticket', r.rows[0]) } else return this.show(this.kind, row) },
    async show(kind, row, back = false) { if (this.drawer && this.document && !back) this.history.push({ kind: this.detailKind, id: this.document.id }); const seq = ++this.detailSequence; this.drawer = true; this.document = null; this.detailLoading = true; this.detailError = false; this.detailKind = kind; this.detailTab = kind === 'supplier' ? 'purchases' : 'lines'; try { const r = await api.detail(kind, row.id); if (seq === this.detailSequence) this.document = r.data } catch (_) { if (seq === this.detailSequence) this.detailError = true } finally { if (seq === this.detailSequence) this.detailLoading = false } },
    backDetail() { const previous = this.history.pop(); if (previous) this.show(previous.kind, previous, true) },
    display(key) { const value = this.document[key]; if (['poState', 'noState', 'statusTicket'].includes(key)) return this.documentState(this.detailKind, this.document); if (key === 'status') return this.states(this.detailKind)[value] || '状态异常'; if (key === 'inventoryStatus') return this.entryStates[value] || '状态异常'; if (key === 'ticketType') return value === 1 ? '入库' : '出库'; if (key.startsWith('money')) return '¥ ' + this.amount(value); if (/Time$/.test(key)) return this.parseTime(value) || '—'; if (/Date$/.test(key)) return this.date(value); return value === null || value === undefined || value === '' ? '—' : value },
    lineExpected(l) { return this.detailKind === 'purchase' ? l.quantity : this.detailKind === 'receipt' ? l.zpNumberExpected + l.cpNumberExpected : l.numberExpected }, lineReceived(l) { return this.detailKind === 'purchase' ? l.receivedQuantity : this.detailKind === 'receipt' ? l.zpNumberActually + l.cpNumberActually : l.numberActually },
    async copy(text) { try { await navigator.clipboard.writeText(text); this.$modal.msgSuccess('已复制') } catch (_) { this.$modal.msgError('复制失败，请选择单号手动复制') } },
    async perform(operation, message) { if (this.acting) return; this.acting = true; const kind = this.detailKind; const id = this.document.id; try { await this.$modal.confirm(message); const r = await api.action(kind, id, operation); if (['post', 'completeVirtual'].includes(operation) && r.data === false) this.$modal.msgWarning('部分明细入账失败，请查看失败原因'); else if (!(kind === 'receipt' && operation === 'approve')) this.$modal.msgSuccess('处理成功'); await this.show(kind, { id }, true); if (kind === 'receipt' && operation === 'approve' && this.document) { if (this.document.noState === 4) this.$modal.msgSuccess('审核成功，虚拟入库已完成'); else if (this.document.tickets.some(t => t.inventoryStatus === 3)) this.$modal.msgWarning('审核成功，但库存入账失败，请查看关联入库单并重试'); else this.$modal.msgSuccess(this.document.actualWarehouse === 2 ? '审核成功，请查看关联入库单的处理进度' : '审核成功，等待 WMS 回传收货结果') } this.load() } catch (_) {} finally { this.acting = false } },
    async reasonAction(operation) { if (this.acting) return; this.acting = true; const kind = this.detailKind; const id = this.document.id; try { const r = await this.$prompt(operation === 'close' ? '关闭后不再安排剩余到货，已入库记录保留。请填写原因。' : '作废保留单据和操作记录。请填写原因。', operation === 'close' ? '关闭剩余采购' : '作废单据', { inputType: 'textarea', inputValidator: value => !!value && !!value.trim() && value.length <= 500 || '请填写 1 至 500 字原因' }); await api.action(kind, id, operation, { reason: r.value }); this.$modal.msgSuccess('处理成功'); await this.show(kind, { id }, true); this.load() } catch (_) {} finally { this.acting = false } },
    openReceipt() { this.quantityMode = 'receipt'; this.receiptForm = { expectedDate: this.document.expectedDate ? this.date(this.document.expectedDate) : null, remarks: '' }; this.quantityLines = this.document.lines.filter(l => l.quantity > l.scheduledQuantity).map(l => ({ ...l, lineId: l.id, maximum: l.quantity - l.scheduledQuantity, quantity: l.quantity - l.scheduledQuantity })); if (!this.quantityLines.length) return this.$modal.msgWarning('所有商品均已安排到货，请先处理在途到货单'); this.quantityOpen = true },
    openReceive() { this.quantityMode = 'receive'; this.quantityLines = this.document.lines.map(l => ({ ...l, maximum: l.numberExpected, numberZp: l.numberExpected, numberCp: 0 })); this.quantityOpen = true },
    async submitQuantities() { if (this.acting) return; const kind = this.detailKind; const id = this.document.id; const lines = this.quantityMode === 'receipt' ? this.quantityLines.filter(l => l.quantity > 0) : this.quantityLines; if (!lines.length) return this.$modal.msgError('至少选择一件到货商品'); this.acting = true; try { await api.action(kind, id, this.quantityMode, this.quantityMode === 'receipt' ? { ...this.receiptForm, lines } : { lines }); this.quantityOpen = false; this.$modal.msgSuccess(this.quantityMode === 'receipt' ? '到货单已创建，请在到货单中核对并审核' : '收货已确认，请继续库存入账'); await this.show(kind, { id }, true); if (this.quantityMode === 'receipt') this.detailTab = 'receipts'; this.load() } catch (_) {} finally { this.acting = false } },
    inventoryLink() { this.$router.push({ path: '/oms-inventory/wmsInventory', query: { storeCode: this.document.wmsSimulationCode } }); this.drawer = false }
  }
}
</script>
<style scoped>
.receipt-options{display:grid;grid-template-columns:240px 1fr;gap:20px;margin-top:18px}.receipt-steps{margin-bottom:20px}.disabled-action{display:inline-block;margin-left:10px}.status-summary{margin:18px 0;padding:16px 18px;border:1px solid #dce6f2;border-radius:8px;background:#f6f9fd}.status-heading,.status-links{display:flex;align-items:center;gap:12px;flex-wrap:wrap}.status-summary p{margin:12px 0;color:#526175;line-height:1.8}.status-links{font-size:13px}.workspace-links,.toolbar,.line-filters{display:flex;align-items:center;gap:10px;flex-wrap:wrap;margin-bottom:18px}.workspace-links .el-button,.toolbar .el-button{margin-left:0}.refresh{margin-left:auto!important}.filter-panel{margin-top:0;padding:18px 22px 4px}.filter-panel ::v-deep .el-form-item{vertical-align:bottom;margin-right:18px}.filter-panel ::v-deep .el-form-item__label{display:block;text-align:left;float:none;line-height:24px}.filter-panel ::v-deep .el-input,.filter-panel ::v-deep .el-select{width:200px}.filter-panel ::v-deep .el-date-editor{width:300px}.query-actions ::v-deep .el-form-item__content{display:flex;gap:8px}.query-actions ::v-deep .el-button{margin:0;height:36px}.line-filters .el-input{width:220px}.muted{color:#7c8798;font-size:12px;line-height:1.7}.code{white-space:nowrap;overflow:hidden;text-overflow:ellipsis;font-size:12px}.name-link{max-width:100%;overflow:hidden;text-overflow:ellipsis;text-align:left}.danger,.error-text{color:#d84b4b}.error-text{font-size:12px;white-space:normal}.edit-form{display:grid;grid-template-columns:1fr 1fr;gap:0 24px}.edit-form ::v-deep .el-select,.edit-form ::v-deep .el-date-editor{width:100%}.span-all{grid-column:1/-1}.warehouse-summary{padding:12px;margin-bottom:18px;background:#f2f7ff;border-radius:6px;line-height:1.8}.totals{text-align:right;padding:18px;font-weight:600}.document-detail{padding:0 24px 30px;overflow-wrap:anywhere}.detail-heading h2{margin:0 0 8px}.summary{margin:20px 0}.document-detail .el-alert{margin-bottom:15px}.document-detail ::v-deep .el-timeline{padding:20px}.advanced-filters{border-top:1px solid #edf0f5;padding-top:16px}.toolbar .el-alert{width:100%}@media(max-width:900px){.edit-form{grid-template-columns:1fr}.document-detail{padding:0 12px 20px}}
.document-detail{flex:1;min-height:0;overflow-y:auto;overscroll-behavior:contain}
.detail-code{display:flex;align-items:center;gap:12px;flex-wrap:wrap}
.detail-code .code{white-space:normal;overflow-wrap:anywhere}
.detail-code .el-button{flex-shrink:0}
.detail-footer{display:flex;flex-shrink:0;justify-content:flex-end;align-items:center;gap:12px;padding:16px 24px;padding-bottom:calc(16px + env(safe-area-inset-bottom, 0px));border-top:1px solid #e6ebf2;background:#fff;box-shadow:0 -3px 12px rgba(31,45,61,.04)}
.detail-footer .el-button{margin:0;min-width:88px}
@media(max-width:900px){.detail-footer{padding-left:12px;padding-right:12px}}
</style>
<style>
.purchase-detail-drawer .el-drawer__body{display:flex;flex-direction:column;min-height:0;overflow:hidden}
@media(max-width:900px){.purchase-detail-drawer{width:100%!important}}
</style>
