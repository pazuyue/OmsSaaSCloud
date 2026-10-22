<template>
  <section v-if="parents.length" class="gift-contents">
    <h4>本单赠送内容</h4><p class="muted">赠送数量按件、套、袋分别记录；展开查看实际库存商品。以下内容采用本单冻结的组成。</p>
    <article v-for="parent in parents" :key="parent.stock_reference || parent.sku_sn" class="gift-parent">
      <div class="parent-title"><div><el-tag size="mini" type="success">赠品</el-tag> <strong>{{ parent.goods_name || parent.sku_sn }}</strong><div class="muted">{{ parent.sku_sn }} · {{ type(parent) }}</div></div><div class="parent-count"><strong>原赠 {{ parent.quantity }} {{ unit(parent) }}</strong><div v-if="voided" class="muted">已终止赠送</div><div v-else class="muted">当前可保留 {{ parent.eligible_quantity }} {{ unit(parent) }}</div></div></div>
      <el-collapse v-if="recipe(parent)"><el-collapse-item :title="lucky(parent)?'查看每袋实际内容':'查看固定套装组成'" name="contents">
        <template v-if="lucky(parent)"><div v-for="bag in recipe(parent).units || []" :key="bag.unit_id" class="bag"><strong>第 {{ bag.unit }} 袋</strong><span class="muted"> · 原袋 #{{ bag.unit_id }} · {{ bag.store_code }}</span><div v-for="(part,i) in bag.components" :key="i" class="part">{{ part.goods_name || part.sku_sn }} <span class="muted">{{ part.sku_sn }}</span> × {{ part.quantity || 1 }} 件</div></div></template>
        <template v-else><div v-for="part in recipe(parent).components || []" :key="part.sku_sn" class="part">{{ part.goods_name || part.sku_sn }} <span class="muted">{{ part.sku_sn }}</span> · 每套 {{ part.quantity }} 件</div><p class="muted">{{ recipe(parent).inventory_mode==='STOCKED'?'按整套成品库存履约，组成仅供核对。':'按组成商品库存履约；下方待办按实际库存商品处理。' }}</p></template>
      </el-collapse-item></el-collapse>
    </article>
  </section>
</template>
<script>
export default {
  props: { voided: Boolean, parents: { type: Array, default: () => [] } },
  methods: {
    recipe(parent) { return parent.bundle_snapshot || null },
    lucky(parent) { return this.recipe(parent) && this.recipe(parent).business_type === 'LUCKY_BAG' },
    unit(parent) { return this.lucky(parent) ? '袋' : this.recipe(parent) ? '套' : '件' },
    type(parent) { return this.lucky(parent) ? '福袋' : this.recipe(parent) ? '固定套装' : '普通商品' }
  }
}
</script>
<style scoped>
.gift-contents{margin:18px 0}.gift-contents h4{margin:0 0 8px}.muted{font-size:12px;color:#758397;line-height:1.8}.gift-parent{border:1px solid #e1e8f0;border-radius:8px;padding:14px 18px;margin:10px 0}.parent-title{display:flex;align-items:center;justify-content:space-between;gap:12px}.parent-count{white-space:nowrap;text-align:right}.part{padding:6px 0;overflow-wrap:anywhere}.bag{padding:10px 0;border-bottom:1px solid #edf0f5}.gift-parent ::v-deep .el-collapse-item__header{font-size:12px;color:#758397}
</style>
