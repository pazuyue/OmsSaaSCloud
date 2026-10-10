<template>
  <div class="goods-nav">
    <el-button v-for="item in items" :key="item.kind" v-hasPermi="[`goods:${item.kind}:list`]" size="small" :type="active === item.kind ? 'primary' : 'default'" :plain="active !== item.kind" @click="go(item.kind)">{{ item.label }}</el-button>
  </div>
</template>
<script>
export default {
  props: { active: String },
  data: () => ({ items: [{ kind: 'info', label: '商品资料' }, { kind: 'category', label: '商品分类' }, { kind: 'color', label: '商品颜色' }, { kind: 'size', label: '商品尺码' }] }),
  methods: { go(kind) { if (kind !== this.active) this.$router.push(`/oms-goods/${kind}`) } }
}
</script>
<style scoped>
.goods-nav { display:flex; flex-wrap:wrap; gap:8px; margin-bottom:18px; }
.goods-nav .el-button { margin-left:0; }
</style>
