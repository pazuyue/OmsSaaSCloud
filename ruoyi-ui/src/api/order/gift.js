import request from '@/utils/request'
const root = '/order/gifts'
export const giftGroups = () => request({ url: `${root}/groups` })
export const saveGiftGroup = data => request({ url: `${root}/groups`, method: 'post', data })
export const giftCatalog = () => request({ url: `${root}/catalog` })
export const giftGoodsSearch = data => request({ url: `${root}/goods-search`, method: 'post', data })
export const giftRecords = params => request({ url: `${root}/records`, params })
export const orderGifts = id => request({ url: `${root}/orders/${id}` })
export const giftPreview = id => request({ url: `${root}/orders/${id}/preview`, method: 'post' })
export const cancelGift = (id, data) => request({ url: `${root}/orders/${id}/cancel`, method: 'post', data })
export const waiveGift = (id, data) => request({ url: `${root}/orders/${id}/waive`, method: 'post', data })
export const giftReturnOptions = id => request({ url: `${root}/refunds/${id}/options` })
export const createGiftReturn = (id, data) => request({ url: `${root}/refunds/${id}/return`, method: 'post', data })
export const giftReturnAction = (refund, plan, document, action, data) => request({ url: `${root}/refunds/${refund}/plans/${plan}/documents/${document}/${action}`, method: 'post', data })

export const giftPreviewNumber = number => request({url:`${root}/preview`,method:'post',data:{number}})
