import request from '@/utils/request'
const root = '/supplychain/purchaseWorkspace'
export const options = () => request({ url: root + '/options' })
export const list = (kind, params) => request({ url: `${root}/${kind}/list`, params })
export const detail = (kind, id) => request({ url: `${root}/${kind}/${id}` })
export const save = (kind, data) => request({ url: `${root}/${kind}`, method: 'post', data, timeout: 120000 })
export const action = (kind, id, operation, data = {}) => request({ url: `${root}/${kind}/${id}/${operation}`, method: 'post', data, timeout: 120000 })
export const removeSupplier = id => request({ url: `${root}/supplier/${id}`, method: 'delete' })
export const previewImport = file => { const data = new FormData(); data.append('file', file); return request({ url: root + '/importPreview', method: 'post', data, timeout: 120000 }) }
