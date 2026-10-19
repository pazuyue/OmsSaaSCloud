export const problemNames = { NONE: '无异常', RATE_LIMIT: '调用限流', NETWORK: '连接异常', SERVICE_ERROR: '服务异常', CONFIGURATION: '配置错误', BUSINESS_CONDITION: '业务条件待核对', RETRY_EXHAUSTED: '重试耗尽', DELIVERY: '投递异常', PROGRESS: '断点继续' }
export const taskStates = { PENDING: '待处理', PROCESSING: '处理中', WAITING: '等待继续', SUCCESS: '处理完成', FAILED: '处理失败', UNKNOWN: '无法关联业务', UNAVAILABLE: '结果暂无法核实' }
export function taskQuery(route, types) {
 const q = route || {}
 return { type: types.some(t => t[0] === q.type) ? q.type : '', state: '', publish: '', keyword: q.event || '', problem: '', focus: ['OVERDUE', 'FAILED', 'PUBLISH', 'ACTIVE'].includes(q.focus) ? q.focus : '', pageNum: 1, pageSize: 20 }
}
export function alertTarget(row) {
 const service = ['SALES_DOCUMENT', 'ORDER_PULL', 'ORDER_CONVERT', 'REFUND_PULL', 'REFUND_CONVERT', 'EXCHANGE_PULL', 'EXCHANGE_CONVERT'].includes(row.business_type) ? 'order' : 'channel'
 if (!['PRODUCT_PULL', 'PRODUCT_BIND', 'SALES_DOCUMENT', 'ORDER_PULL', 'ORDER_CONVERT', 'REFUND_PULL', 'REFUND_CONVERT', 'EXCHANGE_PULL', 'EXCHANGE_CONVERT'].includes(row.business_type)) return null
 if (row.rule_code === 'DEAD_LETTER') return { dead: true }
 const focus = { OVERDUE: 'OVERDUE', FAILURE_RATE: 'FAILED', BACKLOG: 'ACTIVE', NO_CONSUMERS: 'ACTIVE' }[row.rule_code]
 return focus ? { path: '/monitor/messages', query: { service, type: row.business_type, focus } } : null
}
