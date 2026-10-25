<template>
  <header v-if="$route.path !== '/index' && !['/oms-order/businessSettings','/oms-order/syncTasks'].includes($route.path)" class="page-heading">
    <div>
      <div class="page-eyebrow">{{ section }}</div>
      <h1>{{ $route.meta.title || '工作空间' }}</h1>
      <p>{{ description }}</p>
    </div>
    <span class="page-heading-icon" aria-hidden="true"><i :class="icon" /></span>
  </header>
</template>
<script>
const descriptions = {
  '/oms-order/gift-editor/index': ['配置赠送条件、模拟购物清单并关联适用店铺组。', 'el-icon-present'],
  '/oms-order/gifts': ['按店铺组编排赠品策略，查看执行路径并处理赠品售后。', 'el-icon-present'],
  '/oms-order/afterSaleRules/returns': ['独立设置退回安排、仓库下发、合包与实收处理。', 'el-icon-setting'],
  '/oms-order/platformDocuments': ['集中查看平台订单、退款和换货，按原交易追溯来源及 OMS 业务单据。', 'el-icon-files'],
  '/oms-order/platformExchanges': ['保存平台换货申请与来源版本，跟进原单关联、转单及业务处理。', 'el-icon-document'],
  '/oms-order/platformRefunds': ['保存平台退款原文与版本，跟进同步、转单与来源问题。', 'el-icon-document'],
  '/oms-order/businessRefunds': ['按原订单处理仅退款，分别跟踪资金结果与货物处理。', 'el-icon-tickets'],
  '/oms-order/refundSettings': ['维护店铺退款接入、自动转单与独立同步游标。', 'el-icon-setting'],
  '/oms-order/shipments': ['按实体仓查看订单发货单，跟进分批履约与实际出库数量。', 'el-icon-truck'],
  '/oms-order/syncTasks': ['补拉订单、退款和换货，查询同步进度与失败原因。', 'el-icon-download'],
  '/oms-order/businessSettings': ['按店铺维护接入、订单处理、发货与售后规则。', 'el-icon-setting'],
  '/oms-order/business': ['查看转单后的业务订单，跟进库存预占进度与处理结果。', 'el-icon-document'],
  '/oms-goods': ['集中管理商品资料，让每一个 SKU 清晰有序。', 'el-icon-box'],
  '/oms-supplychain': ['连接采购、仓库与合作伙伴，协同处理日常业务。', 'el-icon-connection'],
  '/oms-inventory': ['查看库存明细，管理库存分配与业务规则。', 'el-icon-data-analysis'],
  '/oms-channel/platformLogs': ['集中查询平台交互，区分接口结果、业务进度与模拟记录。', 'el-icon-connection'],
  '/oms-channel': ['统一维护渠道信息与业务配置。', 'el-icon-set-up'],
  '/oms-order': ['拉取天猫平台订单，处理转单异常与库存逻辑预占。', 'el-icon-document'],
  '/system': ['管理组织、账号与权限，维护业务基础配置。', 'el-icon-setting'],
  '/monitor': ['查看运行状态与任务记录。', 'el-icon-monitor'],
  '/tool': ['管理开发工具与代码生成配置。', 'el-icon-folder-opened']
}
export default {
  computed: {
    definition() {
      return (
        descriptions[this.$route.path] || descriptions['/' + this.$route.path.split('/')[1]] || [
          '查看并管理当前页面的信息。',
          'el-icon-document'
        ]
      )
    },
    description() {
      return this.definition[0]
    },
    icon() {
      return this.definition[1]
    },
    section() {
      const parent = this.$route.matched.find((item) => item.meta && item.meta.title)
      return parent ? parent.meta.title : '工作空间'
    }
  }
}
</script>
