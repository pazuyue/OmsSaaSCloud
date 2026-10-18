import request from '@/utils/request'
const root = '/order/exchanges'
export const exchangeList = params => request({ url: root, params })
export const exchangeDetail = id => request({ url: `${root}/${id}` })
export const exchangeAction = (id, action, data) => request({ url: `${root}/${id}/${action}`, method: 'post', data })
export const exchangeConfigs = () => request({ url: `${root}/configs` })
export const saveExchangeConfig = data => request({ url: `${root}/configs`, method: 'post', data })
export const exchangeCapability = channel => request({ url: `${root}/capability/${channel}` })
export const exchangeSync = data => request({ url: `${root}/sync`, method: 'post', data })
export const exchangeTasks = () => request({ url: `${root}/tasks` })
export const exchangeCreate = data => request({ url: root, method: 'post', data })
export const exchangeReasons = id => request({ url: `${root}/${id}/reasons` })
export const exchangeAccess = (id, data) => request({ url: `/channel/exchangePlatform/${id}/access`, method: 'post', data })

export const exchangeCatalog = () => request({ url: `${root}/catalog` })

export const exchangeWorkbench = params => request({ url: root + '/workbench', params })
export const exchangeGoodsSearch = data => request({ url: root + '/goods-search', method: 'post', headers: { repeatSubmit: false }, data })

export const exchangeCreateSources = orderId => request({ url: root + '/create-sources', params: { orderId } })
export const exchangeReturnReport = id => request({ url: `${root}/${id}/return` })

export const exchangeControlReport = id => request({ url: `${root}/${id}/controls` })

export const exchangeContinuationPreview = id => request({ url: `${root}/${id}/continuation-preview` })
