<template>
  <header v-if="$route.path !== '/index'" class="page-heading">
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
  '/oms-goods': ['集中管理商品资料，让每一个 SKU 清晰有序。', 'el-icon-box'],
  '/oms-supplychain': ['连接采购、仓库与合作伙伴，协同处理日常业务。', 'el-icon-connection'],
  '/oms-inventory': ['查看库存明细，管理库存分配与业务规则。', 'el-icon-data-analysis'],
  '/oms-channel': ['统一维护渠道信息与业务配置。', 'el-icon-set-up'],
  '/system': ['管理组织、账号与权限，维护业务基础配置。', 'el-icon-setting'],
  '/monitor': ['查看运行状态与任务记录。', 'el-icon-monitor'],
  '/tool': ['管理开发工具与代码生成配置。', 'el-icon-folder-opened']
}
export default {
  computed: {
    definition() {
      return (
        descriptions['/' + this.$route.path.split('/')[1]] || [
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
