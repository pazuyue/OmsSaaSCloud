export const associationLabels = { UNLINKED: '未关联', LINKED: '已关联', DISABLED: '关联已停用' }
export const todoLabels = { OPEN: '待修复配置', READY: '配置检查通过，待继续业务', COMPLETED: '已完成' }
export const businessLabels = { SHIPMENT: '销售发货', REFUND: '退款退货', EXCHANGE: '换货', PURCHASE_RETURN: '采购退仓', RECLAIM: '供应商寄回' }
export const actionLabels = { PLATFORM_SHIPPING: '向平台回传发货', OUTBOUND_DISPATCH: '下发仓库出库', INBOUND_DISPATCH: '下发仓库入库', RESOLVE_ACTUAL: '识别实际物流', EXCHANGE_CONSIGN: '回传换货物流' }
export const objectLabels = { STANDARD: '标准快递', DIRECTORY: '快递清单', MAPPING: '快递关联', CAPABILITY: '仓库服务能力', TODO: '关联待办', IMPORT: '清单导入', SYNC: '清单同步' }
export const operationLabels = { CREATE: '新增', UPDATE: '修改', PREVIEW: '检查清单', CONFIRM: '确认导入', BATCH_PREVIEW: '检查批量关联', BATCH_CONFIRM: '确认批量关联', REVALIDATE: '检查修复结果', RETRY_COMPLETED: '继续业务完成', SYNC: '查询清单', SYNC_QUEUED: '提交清单查询', SYNC_SUCCESS: '清单查询成功', SYNC_FAILED: '清单查询失败', REQUEST: '提交清单查询', RETRY_ENQUEUED: '继续业务已排队', RETRY_REQUIRES_ORIGINAL_ACTION: '等待回原单继续' }
export const fieldLabels = { standard_code: 'OMS 标准编码', standard_name: '标准快递名称', external_code: '外部快递编码', external_name: '外部快递名称', standard_id: '标准快递身份', directory_id: '清单条目', enabled: '配置状态', disabled_reason: '停用原因', purpose: '业务用途', direction: '处理方向', connection_id: '连接', shop_id: '店铺', owner_code: '货主', real_store_code: '实体仓', product_code: '运输产品编码', is_default: '下发或提交首选项', evidence: '配置来源与确认说明', flow: '仓库收发用途', provinces_json: '配送省份', excluded_provinces_json: '不配送省份', aliases_json: '搜索别名', category: '快递分类', state: '处理结果', message: '结果说明', reason: '处理说明' }
export function parseObject(value) {
  if (value && typeof value === 'object') return value
  try { return JSON.parse(value || '{}') } catch (_) { return {} }
}
export function scopeName(row, metadata = {}) {
  const connection = (metadata.connections || []).find(c => String(c.id) === String(row.connection_id))
  const shop = (metadata.shops || []).find(s => String(s.channel_id) === String(row.shop_id))
  if (row.system_type === 'CHANNEL') {
    const name = { TMALL: '天猫', JD: '京东', DOUYIN: '抖音' }[row.provider] || row.provider
    return `${name} · ${shop ? shop.channel_name : row.shop_id ? '店铺 ' + row.shop_id : row.connection_id ? '接入应用 ' + row.connection_id : '平台通用'}`
  }
  const store = (metadata.stores || []).find(s => String(s.connection_id) === String(row.connection_id) && s.owner_code === row.owner_code && (!row.real_store_code || s.real_store_code === row.real_store_code))
  return [connection ? connection.name : row.connection_id ? '连接 ' + row.connection_id : '未选择仓库连接', row.owner_code ? '货主 ' + ((store || {}).owner_name || row.owner_code) : '', row.real_store_code ? '实仓 ' + ((store || {}).real_store_name || row.real_store_code) : ''].filter(Boolean).join(' / ')
}
export function auditDetails(row, purposes = {}) {
  const before = parseObject(row.before_json), after = parseObject(row.after_json)
  const format = (key, value) => value === undefined || value === null || value === '' ? '未设置' : key === 'enabled' ? Number(value) === 1 ? '已启用' : '已停用' : key === 'is_default' ? Number(value) === 1 ? '是' : '否' : key === 'purpose' ? purposes[value] || value : key === 'direction' ? ({IN:'识别外部快递编码',OUT:'提交或下发快递编码',BOTH:'双向关联'})[value] || value : key === 'state' ? ({OPEN:'待修复配置',READY:'配置检查通过，待继续业务',COMPLETED:'已完成',RESOLVED:'检查通过',MISSING:'缺少关联',DISABLED:'配置已停用',CAPABILITY_UNSUPPORTED:'仓库能力不满足',SUCCESS:'处理成功',FAILED:'处理失败'})[value] || value : key === 'flow' ? value === 'INBOUND' ? '收货' : '发货' : typeof value === 'object' ? JSON.stringify(value) : String(value)
  const changes = Object.keys(fieldLabels).filter(k => JSON.stringify(before[k]) !== JSON.stringify(after[k]) && (before[k] !== undefined || after[k] !== undefined)).map(k => ({ field: fieldLabels[k], before: format(k, before[k]), after: format(k, after[k]) })).filter(change => change.before !== change.after)
  const target = after.standard_name || after.external_name || before.standard_name || before.external_name || after.standard_code || after.external_code || before.standard_code || before.external_code || after.business_sn || before.business_sn || (row.display_standard_name || row.display_external_name ? '当前对象：'+[row.display_standard_name,row.display_external_name,row.display_external_code].filter(Boolean).join(' / ') : '')
  const count = Array.isArray(after.rows) ? `，共 ${after.rows.length} 条，错误 ${after.errors || 0} 条` : ''
  return { summary: `${operationLabels[row.action] || row.action} · ${objectLabels[row.object_type] || row.object_type}${target ? ' · ' + target : ' · 记录 ' + row.object_id}${count}`, changes, evidence: after.evidence || before.evidence || ((after.rows || [])[0] || {}).data?.evidence || '', before, after }
}
