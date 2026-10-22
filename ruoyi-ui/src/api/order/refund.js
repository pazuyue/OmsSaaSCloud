import request from '@/utils/request'
const root = '/order/refunds'
export const refundList = (business, params) => request({ url: `${root}/${business ? 'business' : 'platform'}`, params })
export const refundDetail = (business, id) => request({ url: `${root}/${business ? 'business' : 'platform'}/${id}` })
export const convertRefund = id => request({ url: `${root}/platform/${id}/convert`, method: 'post' })
export const refundSnapshot = (id, snapshot) => request({ url: `${root}/platform/${id}/snapshots/${snapshot}` })
export const refundReview = (id, data) => request({ url: `${root}/business/${id}/review`, method: 'post', data })
export const refundCatalog = () => request({ url: `${root}/catalog` })
export const refundConfigs = () => request({ url: `${root}/configs` })
export const saveRefundConfig = data => request({ url: `${root}/configs`, method: 'post', data })
export const testRefundConfig = channel => request({ url: `${root}/configs/${channel}/test`, method: 'post' })
export const syncRefund = data => request({ url: `${root}/sync`, method: 'post', data })
export const refundTasks = () => request({ url: `${root}/tasks` })
export const refundTask = id => request({ url: `${root}/tasks/${id}` })
export const retryRefundTask = id => request({ url: `${root}/tasks/${id}/retry`, method: 'post' })
export const orderRefunds = id => request({ url: `${root}/orders/${id}` })

export const refundGoods=id=>request({url:`${root}/business/${id}/goods`})
export const retryRefundAutoCreate=(id,data)=>request({url:`${root}/business/${id}/goods/auto-create/retry`,method:'post',data})
export const refundGoodsOptions=id=>request({url:`${root}/business/${id}/goods/options`})
export const createRefundReturn=(id,data)=>request({url:`${root}/business/${id}/goods/plans`,method:'post',data})
export const refreshRefundGoods=id=>request({url:`${root}/business/${id}/goods/refresh`,method:'post'})
export const refundGoodsAction=(id,plan,document,action,data)=>request({url:`${root}/business/${id}/goods/plans/${plan}/documents/${document}/${action}`,method:'post',data})
export const finishRefundGoods=(id,data)=>request({url:`${root}/business/${id}/goods/finish`,method:'post',data})
export const waiveRefundGoods=(id,data)=>request({url:`${root}/business/${id}/goods/waive`,method:'post',data})

export const cancelRefundUnshipped=(id,data)=>request({url:`${root}/business/${id}/goods/cancel-unshipped`,method:"post",data})

export const refundParcels=id=>request({url:`${root}/business/${id}/parcels`})
export const refundParcelCandidates=(id,params)=>request({url:`${root}/business/${id}/parcels/candidates`,params})
export const attachRefundParcel=(id,plan,data)=>request({url:`${root}/business/${id}/parcels/plans/${plan}`,method:'post',data})
export const refundParcelTransport=(id,parcel,data)=>request({url:`${root}/business/${id}/parcels/${parcel}/transport`,method:'post',data})
export const allocateRefundParcel=(id,parcel,event,data)=>request({url:`${root}/business/${id}/parcels/${parcel}/receipts/${event}`,method:'post',data})

export const closeWithdrawnRefund=(id,data)=>request({url:`${root}/business/${id}/goods/close-withdrawn`,method:'post',data})

export const refundParcelConfig=channel=>request({url:`${root}/parcel-configs/${channel}`})
export const saveRefundParcelConfig=(channel,data)=>request({url:`${root}/parcel-configs/${channel}`,method:'post',data})
export const runRefundParcels=id=>request({url:`${root}/business/${id}/parcels/auto-run`,method:'post'})
export const refundParcelManualLogistics=(id,plan,data)=>request({url:`${root}/business/${id}/parcels/plans/${plan}/logistics`,method:'post',data})

export const refundParcelPolicy=id=>request({url:`${root}/business/${id}/parcels/policy`})

export const refundParcelReviewContext=(id,plan)=>request({url:`${root}/business/${id}/parcels/plans/${plan}/review`})
export const reviewRefundParcel=(id,plan,data)=>request({url:`${root}/business/${id}/parcels/plans/${plan}/review`,method:'post',data})

export const refundParcelDocument = (id,parcel,action,data) => request({url:`/order/refunds/business/${id}/parcels/${parcel}/document/${action}`,method:'post',data})

export const refundReturnRuleShops=()=>request({url:`${root}/parcel-configs/catalog`})

export const refundReturnRules=params=>request({url:`${root}/parcel-configs`,params})

export const evaluateRefundReceipt=(id,parcel,event,data)=>request({url:`${root}/business/${id}/parcels/${parcel}/receipts/${event}/automatic`,method:"post",data})

export const createRefundBundleReturn=(id,data)=>request({url:`${root}/business/${id}/goods/bundle-plans`,method:'post',data})
