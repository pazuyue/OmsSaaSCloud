import request from '@/utils/request'
const root = '/supplychain/purchaseWorkspace'
export const options = () => request({ url: root + '/options' })
export const list = (kind, params) => request({ url: `${root}/${kind}/list`, params })
export const detail = (kind, id) => request({ url: `${root}/${kind}/${id}` })
export const save = (kind, data) => request({ url: `${root}/${kind}`, method: 'post', data, timeout: 120000 })
export const action = (kind, id, operation, data = {}) => request({ url: `${root}/${kind}/${id}/${operation}`, method: 'post', data, timeout: 120000 })
export const removeSupplier = id => request({ url: `${root}/supplier/${id}`, method: 'delete' })
export const previewImport = file => { const data = new FormData(); data.append('file', file); return request({ url: root + '/importPreview', method: 'post', data, timeout: 120000 }) }

export const receiptBatches = (id, params) => request({ url: `${root}/ticket/${id}/batches`, params })
export const cutoffReport = (kind, id) => request({ url: `${root}/${kind}/${id}/cutoff` })
export const requestCutoff = (kind, id, data) => request({ url: `${root}/${kind}/${id}/cutoff`, method: 'post', data })
export const retryCutoff = (kind, id, data) => request({ url: `${root}/${kind}/${id}/cutoff/retry`, method: 'post', data })
export const confirmCutoff = (id, data) => request({ url: `${root}/cutoff/items/${id}/confirm`, method: 'post', data })
export const inboundReview = id => request({ url: `${root}/inbound-reviews/${id}` })
export const decideInboundReview = (id, data) => request({ url: `${root}/inbound-reviews/${id}`, method: 'post', data })
export const operations = params => request({ url: root + '/operations', params })
export const differences = params => request({ url: root + '/differences', params })
export const differenceDetail = id => request({ url: `${root}/differences/${id}` })
export const decideDifference = (id, data) => request({ url: `${root}/differences/${id}`, method: 'post', data })
