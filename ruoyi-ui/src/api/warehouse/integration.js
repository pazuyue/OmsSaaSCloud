import request from '@/utils/request'
const root = '/supplychain/wmsIntegration'
export const connections = () => request({ url: root + '/connections' })
export const saveConnection = data => request({ url: root + '/connections', method: 'post', data })
export const inboundTask = id => request({ url: root + '/tickets/' + id })
export const inboundAction = (id, action, data) => request({ url: `${root}/tickets/${id}/${action}`, method: 'post', data, timeout: 30000 })
export const interactionLogs = params => request({ url: root + '/logs', params })
export const interactionLog = id => request({ url: root + '/logs/' + id })
