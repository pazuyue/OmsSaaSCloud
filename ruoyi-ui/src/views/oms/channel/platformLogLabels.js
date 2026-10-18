export const results = { SUCCESS: '成功', FAILED: '失败', UNKNOWN: '结果待确认', RUNNING: '处理中' }
export const kinds = { OPERATION: '业务操作', API: '平台接口', SIMULATION: '模拟调用', CONFIG: '配置操作', NOTIFICATION: '平台通知', DATA_SYNC: '数据同步' }
export const categories = { AUTH: '授权与店铺', SERVICE: '服务订购', ORDER: '订单', SHIPPING: '发货', EXCHANGE: '换货', REFUND: '退款', NOTIFICATION: '平台通知', CONFIG: '配置', DATA_SYNC: '数据同步' }
export const environments = { REAL: '真实平台', SIMULATION: '模拟', LOCAL: '本地操作' }
export const numberTypes = { AUTO: '自动识别单号', PLATFORM_TID: '平台订单号', PLATFORM_OID: '平台子订单号', OMS_ORDER: 'OMS 订单号', EXCHANGE: 'OMS 换货单号', REFUND: 'OMS 退款单号', PLATFORM_REFUND: '平台退款单号', PLATFORM_EXCHANGE: '平台换货单号', SHIPMENT: '发货单号', PACKAGE: '包裹编号', WAYBILL: '运单号', TASK: '业务请求编号' }
export const actions = { 'JDP.refund.read': '读取退款推送库', 'oauth.token.exchange': '授权换令牌', 'oauth.token.refresh': '刷新令牌', 'oauth.token.simulate': '模拟授权', 'taobao.shop.seller.get': '核验平台店铺', 'taobao.vas.subscribe.get': '核验服务订购', 'tmall.exchange.receive.get': '查询换货申请列表', 'tmall.exchange.get': '查询换货申请', 'tmall.exchange.refusereason.get': '查询换货拒绝原因', 'tmall.exchange.agree': '同意换货', 'tmall.exchange.refuse': '拒绝换货', 'tmall.exchange.returngoods.agree': '确认退回收货', 'tmall.exchange.consigngoods': '回传换出物流', 'SIMULATION.shipping.send': '模拟提交发货', 'SIMULATION.shipping.query': '查询模拟发货结果', OAUTH_EXCHANGE: '处理店铺授权', AUTHORIZE_START: '发起店铺授权', TOKEN_REFRESH: '处理令牌刷新', SIMULATED_AUTHORIZATION: '处理模拟授权', SHOP_QUERY: '查询店铺', SUBSCRIPTION_QUERY: '查询服务订购', APP_SAVE: '保存平台应用', BIND: '绑定店铺', BINDING_SAVE: '保存店铺绑定', REMINDER_SAVE: '保存提醒设置', LOCAL_DISABLE: '停用本地授权' }
export const triggers = { USER: '人工操作', BUSINESS: '业务任务', SCHEDULER: '定时任务', SYSTEM: '系统' }
export const businessStates = { NEW: '新建', WAIT_REVIEW: '待审核', WAIT_ALLOCATION: '待分仓', WAIT_SHIPMENT: '待发货', WAIT_CARRIER: '待匹配快递', READY: '待下发', WAREHOUSE_PROCESSING: '仓库处理中', PART_SHIPPED: '部分发货', SHIPPED: '已发货', COMPLETED: '已完成', CANCELED: '已取消', CLOSED: '已结束', VOIDED: '已作废', BLOCKED: '待核对', NEEDS_REVIEW: '待人工审核', APPROVED: '已审核', WAIT_STOCK: '等待库存', PROCESSING: '处理中', REJECTED: '已拒绝' }
function primaryRefs(row) { const refs = row.refs || []; for (const type of ['REFUND', 'SHIPMENT', 'EXCHANGE', 'PLATFORM_REFUND', 'OMS_ORDER', 'PLATFORM_EXCHANGE', 'PLATFORM_TID']) { const selected = refs.filter(r => r.type === type); if (selected.length) return selected } return [] }
export function primaryRef(row) { const selected = primaryRefs(row); return selected.length === 1 ? selected[0] : null }
export function multipleBusinesses(row) { return primaryRefs(row).length > 1 }

export function refKey(ref) { return ref ? ref.type + ':' + ref.number : '' }
export function tone(result) { return ({ SUCCESS: 'success', FAILED: 'danger', UNKNOWN: 'warning', RUNNING: '' })[result] || 'info' }

export function errorSummary(row) { if (row.result === 'UNKNOWN') return '尚无可确认结果，请先查询原请求'; if (row.result !== 'FAILED') return ''; if (['27', 'isv.session-expired', 'invalid_session', 'invalid_grant'].includes(row.error_code)) return '平台授权已失效，请核对店铺授权'; if (row.error_code === 'VALIDATION_OR_PERSISTENCE_ERROR') return row.environment === 'SIMULATION' ? '模拟数据校验或本地结果保存未完成' : '本地校验或结果保存未完成'; return '平台拒绝或响应校验失败，请按错误码核对' }
export function businessLabel(state) { if (!state) return ''; const control = { HOLD: '已挂起', CONTINUE_REVIEW: '待继续换出审核', CANCELING: '取消处理中', CANCEL_REVIEW: '待确认结束' }; return control[state.control_state] || businessStates[state.state] || state.state }

export const businessTabs = [{ key: 'ALL', label: '全部' }, { key: 'ORDER', label: '订单同步' }, { key: 'SHIPPING', label: '发货回传' }, { key: 'EXCHANGE', label: '换货处理' }, { key: 'REFUND', label: '退款处理' }, { key: 'AUTH', label: '授权与店铺' }]
export const businessActions = [
 { method: 'JDP.refund.read', label: '读取退款推送库', category: 'REFUND' },
 { method: 'tmall.exchange.receive.get', label: '查询换货申请列表', category: 'EXCHANGE' },
 { method: 'tmall.exchange.get', label: '查询换货申请', category: 'EXCHANGE' },
 { method: 'tmall.exchange.refusereason.get', label: '查询换货拒绝原因', category: 'EXCHANGE' },
 { method: 'tmall.exchange.agree', label: '同意换货', category: 'EXCHANGE' },
 { method: 'tmall.exchange.refuse', label: '拒绝换货', category: 'EXCHANGE' },
 { method: 'tmall.exchange.returngoods.agree', label: '确认退回收货', category: 'EXCHANGE' },
 { method: 'tmall.exchange.consigngoods', label: '回传换出物流', category: 'EXCHANGE' },
 { method: 'SIMULATION.shipping.send', label: '模拟提交发货', category: 'SHIPPING' },
 { method: 'SIMULATION.shipping.query', label: '查询模拟发货结果', category: 'SHIPPING' },
 { method: 'taobao.shop.seller.get', label: '核验平台店铺', category: 'AUTH' },
 { method: 'oauth.token.exchange', label: '授权换令牌', category: 'AUTH' },
 { method: 'oauth.token.refresh', label: '刷新令牌', category: 'AUTH' }
]
