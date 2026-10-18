import request from '@/utils/request'
const root = '/order/exchanges/platform'
export const platformExchangeList = params => request({ url: root, params })
export const platformExchangeDetail = id => request({ url: `${root}/${id}` })
export const platformExchangeSnapshot = (id, snapshot) => request({ url: `${root}/${id}/snapshots/${snapshot}` })
export const platformExchangeConvert = id => request({ url: `${root}/${id}/convert`, method: 'post' })
export const platformExchangeRefresh = id => request({ url: `${root}/${id}/refresh`, method: 'post' })
export const platformExchangeCandidates = id => request({ url: `${root}/${id}/candidates` })
export const platformExchangeLink = (id, data) => request({ url: `${root}/${id}/link`, method: 'post', data })
