import request from '@/utils/request'
const root = '/supplychain/warehouseControl'
export const options = () => request({ url: root + '/options' })
export const sources = data => request({ url: root + '/sources', method: 'post', data })
export const list = (kind, params) => request({ url: `${root}/${kind}`, params })
export const detail = (kind, id) => request({ url: `${root}/${kind}/${id}` })
export const create = (kind, data) => request({ url: `${root}/${kind}`, method: 'post', data })
export const action = (kind, id, operation, data = {}) => request({ url: `${root}/${kind}/${id}/${operation}`, method: 'post', data })
export const review = (id, data) => request({ url: `${root}/adjustments/${id}/review`, method: 'post', data })
export const retryAdjustment = id => request({ url: `${root}/adjustments/${id}/retry`, method: 'post' })
export const reports = () => request({ url: root + '/inventory-reports' })

export const checkScope = store_code => request({url:root+'/reconciliation-scope',params:{store_code}})
export const checkResults = (id,params) => request({url:`${root}/reconciliations/${id}/results`,params})
export const checkControl = (id,action) => request({url:`${root}/reconciliations/${id}/control/${action}`,method:'post'})
export const checkRecheck = (id,data) => request({url:`${root}/reconciliations/${id}/recheck`,method:'post',data})
export const checkExport = (id,params) => request({url:`${root}/reconciliations/${id}/export`,params,responseType:'blob',timeout:120000})

export const checkGoods = params => request({url:root+'/reconciliation-goods',params})
