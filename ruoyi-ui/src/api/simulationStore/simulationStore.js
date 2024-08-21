import request from '@/utils/request'

// 查询虚仓列表
export function listSimulationStoreInfo(query) {
  return request({
    url: '/supplychain/simulationStore/list',
    method: 'get',
    params: query
  })
}

// 查询虚仓详细
export function getSimulationStoreInfo(id) {
  return request({
    url: '/supplychain/simulationStore/' + id,
    method: 'get'
  })
}

// 新增虚仓
export function addSimulationStoreInfo(data) {
  return request({
    url: '/supplychain/simulationStore',
    method: 'post',
    data: data
  })
}

// 修改虚仓
export function updateSimulationStoreInfo(data) {
  return request({
    url: '/supplychain/simulationStore',
    method: 'put',
    data: data
  })
}

// 删除虚仓
export function delSimulationStoreInfo(id) {
  return request({
    url: '/supplychain/simulationStore/' + id,
    method: 'delete'
  })
}

export function listSimulationStore(query) {
  return request({
    url: '/supplychain/simulationStore/listSimulationStore',
    method: 'get',
    params: query
  })
}
