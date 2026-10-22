export function amount(value) {
  if (value === null || value === undefined || value === '') return '未提供'
  const match = String(value).match(/^(-?)(\d+)(?:\.(\d{1,2}))?$/)
  return match ? `${match[1]}${match[2]}.${(match[3] || '').padEnd(2, '0')}` : '待核对'
}
export function money(value) {
  const text = amount(value)
  return ['未提供', '待核对'].includes(text) ? text : `¥${text}`
}
export const documentOf = order => order.document || {}
export const lineAmounts = order => Object.fromEntries((documentOf(order).lines || []).map(line => [String(line.platform_oid), line]))
export const checkLabel = state => ({ MATCHED: '金额核对一致', DIFFERENCE: '金额存在差额', INCOMPLETE: '金额资料未齐', INVALID: '金额格式待核对' }[state] || '金额资料未提供')
export const checkTone = state => state === 'MATCHED' ? 'success' : ['DIFFERENCE', 'INVALID'].includes(state) ? 'warning' : 'info'
