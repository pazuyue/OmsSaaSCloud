import request from '@/utils/request'
const root='/inventory/qualityInventory'
export const qualityList=params=>request({url:root,params})
export const qualityDetail=id=>request({url:root+'/'+id})
export const qualityBatches=params=>request({url:root+'/batches',params})
export const createQuality=data=>request({url:root,method:'post',data})
export const reviewQuality=(id,data)=>request({url:root+'/'+id+'/review',method:'post',data})
