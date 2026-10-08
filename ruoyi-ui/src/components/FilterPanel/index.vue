<template>
  <section class="filter-panel" :class="{ 'is-expanded': expanded }" aria-label="查询条件">
    <div class="filter-panel-heading">
      <span><i class="el-icon-search" /> 筛选条件</span><button
        v-if="advancedKeys.length"
        type="button"
        class="filter-toggle"
        :aria-expanded="expanded"
        @click="expanded = !expanded"
      >
        {{ expanded ? '收起筛选' : '更多筛选'
        }}<span v-if="activeCount" class="filter-count">{{ activeCount }}</span><i :class="expanded ? 'el-icon-arrow-up' : 'el-icon-arrow-down'" />
      </button>
    </div>
    <slot />
  </section>
</template>
<script>
export default {
  name: 'FilterPanel',
  props: { model: { type: Object, required: true }, primaryFields: { type: Array, default: () => [] }},
  data: () => ({ expanded: false }),
  computed: {
    advancedKeys() {
      return Object.keys(this.model).filter(
        (key) => !['pageNum', 'pageSize', 'params', ...this.primaryFields].includes(key)
      )
    },
    activeCount() {
      return this.advancedKeys.filter((key) => {
        const value = this.model[key]
        return (
          value !== null && value !== undefined && value !== '' && (!Array.isArray(value) || value.length)
        )
      }).length
    }
  }
}
</script>
