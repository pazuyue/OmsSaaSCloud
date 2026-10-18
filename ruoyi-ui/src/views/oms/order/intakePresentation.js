export const intakeKinds = { ORDER: '订单', REFUND: '退款', EXCHANGE: '换货' }
export const taskStates = { PENDING: '待处理', RUNNING: '处理中', COMPLETED: '已完成', RETRY: '等待重试', FAILED: '失败', PARTIAL: '部分失败' }
export const itemStates = { NEW: '新增', UPDATED: '更新', DUPLICATE: '内容相同', SKIPPED: '已跳过', OLDER: '旧版本已保留', CHANGE_PENDING: '已更新，待业务核对', NOT_FOUND: '推送库未找到', FAILED: '失败', INVALID: '资料异常', CONFLICT: '版本冲突', PENDING: '待处理' }
export function intakeStatus(config, allowed, kind) {
  if (!allowed) return { label: '无配置查看权限', tone: 'info' }
  if (!config) return { label: '未配置', tone: 'info' }
  if (!Number(config.enabled)) return { label: '已暂停自动同步', tone: 'info' }
  if (config.last_error || kind !== 'EXCHANGE' && !Number(config.source_enabled)) return { label: '有异常', tone: 'warning' }
  return { label: '自动同步已开启', tone: 'success' }
}
export function retryAction(task) {
  if (task.kind === 'ORDER' && task.state === 'PARTIAL') return 'items'
  return ['FAILED', 'RETRY'].includes(task.state) ? 'task' : ''
}
