import request from '@/utils/request'
const root = '/order/sharedCarriers'
export const carrierPage = (type, params) => request({ url: `${root}/${type}/list`, params, silentError: true })
export const carrierMetadata = () => request({ url: `${root}/metadata`, silentError: true })
export const saveCarrier = (type, data) => request({ url: `${root}/${type}/save`, method: 'post', data, silentError: true })
export const carrierOptions = data => request({ url: `${root}/options`, method: 'post', data, silentError: true })
export const standardSearch = keyword => request({ url: `${root}/standards/search`, params: { keyword }, silentError: true })
export const carrierPreview = data => request({ url: `${root}/preview`, method: 'post', data, silentError: true })
export const directoryPreview = data => request({ url: `${root}/directory/import-preview`, method: 'post', data, silentError: true })
export const directoryConfirm = request_key => request({ url: `${root}/directory/import-confirm`, method: 'post', data: { request_key }, silentError: true })
export const directorySync = data => request({ url: `${root}/directory/sync`, method: 'post', data, silentError: true })
export const carrierTodoAction = (id, action, revision) => request({ url: `${root}/todo/${id}/${action}`, method: 'post', data: { revision }, silentError: true })
export const mappingSuggestions = ids => request({ url: `${root}/mapping/suggestions`, method: 'post', data: ids, silentError: true })
export const mappingBatchPreview = data => request({ url: `${root}/mapping/batch-preview`, method: 'post', data, silentError: true })
export const mappingBatchConfirm = request_key => request({ url: `${root}/mapping/batch-confirm`, method: 'post', data: { request_key }, silentError: true })
export const carrierImpact = (type, id) => request({ url: `${root}/${type}/${id}/impact`, silentError: true })

export const standardDetail = id => request({ url: `${root}/standards/${id}`, silentError: true })

export const mappingOutputOptions = data => request({ url: `${root}/mapping/output-options`, method: 'post', data, silentError: true })
