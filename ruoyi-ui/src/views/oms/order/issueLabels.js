export const issueCategories = { SHORTAGE: '库存不足', QUOTA: '额度不足', RESTRICTED: '库存受限', LEDGER: '台账异常', PLATFORM: '平台回传异常' }
export const issueScopes = { COMPANY: '公司', CHANNEL: '渠道', WAREHOUSE: '仓库', BATCH: '批次', ORDER: '订单' }
export const issueStages = { RESERVE: '预占', ALLOCATE: '分仓', OUTBOUND: '出库', DISPATCH: '下发', PLATFORM_SHIP: '平台发货回传' }
export const issueStates = { OPEN: '待处理', ACKNOWLEDGED: '已确认', RESOLVED: '已解除' }
export const issueActions = { OPENED: '问题发生', CHANGED: '依据更新', ACKNOWLEDGED: '确认知悉', RESOLVED: '问题解除', REOPENED: '问题再次发生' }
export const issueTone = issue => Number(issue.blocking) === 1 ? 'danger' : issue.severity === 'WARNING' ? 'warning' : 'info'
