<template>
  <section class="refund-progress" :class="'refund-progress--'+progress.tone" aria-label="当前单据进度">
    <div class="progress-heading">
      <div class="progress-main"><strong>{{ progress.title }}</strong><span v-if="progress.primary" class="owner">{{ ownerText(progress.primary.owner) }}</span><p v-if="progress.primary">{{ progress.primary.message }}</p><p v-else>以下进度仅属于本退款；平台资金、购买商品和关联赠品分别核对。</p></div>
      <el-button v-if="progress.primary" size="small" type="primary" plain @click="$emit('task',progress.primary)">{{ progress.primary.action }}</el-button>
    </div>
    <div class="progress-tracks">
      <button v-for="track in progress.tracks" :key="track.key" type="button" class="progress-track" :class="'progress-track--'+track.tone" :title="track.detail" @click="$emit('track',track)"><span class="track-title">{{ track.title }}</span><strong>{{ track.state }}</strong><span class="track-detail">{{ track.detail }}</span><span class="track-link">查看详情 <i class="el-icon-arrow-right" /></span></button>
    </div>
    <div v-if="progress.times.length" class="progress-times"><span v-for="item in progress.times" :key="item.title">{{ item.title }}：{{ item.value }}</span></div>
    <el-collapse v-if="progress.tasks.length>1" class="other-progress-tasks"><el-collapse-item :title="'其他待办 '+(progress.tasks.length-1)+' 项'" name="tasks"><div v-for="item in progress.tasks.slice(1)" :key="item.key" class="progress-task"><div><b>{{ item.title }}</b><span class="owner">{{ ownerText(item.owner) }}</span><p>{{ item.message }}</p></div><el-button type="text" @click="$emit('task',item)">{{ item.action }}</el-button></div></el-collapse-item></el-collapse>
  </section>
</template>
<script>
import { ownerText } from './refundProgressPresentation'
export default {
  name: 'RefundProgress',
  props: { progress: { type: Object, required: true }},
  methods: { ownerText }
}
</script>
<style scoped>
.refund-progress{padding:14px 16px;border:1px solid #dfe7f1;border-radius:8px;margin:16px 0;background:#fbfcfe}.progress-heading{display:flex;align-items:center;justify-content:space-between;gap:18px}.progress-main{min-width:0}.progress-main>strong{font-size:16px;line-height:1.7;color:#30445e}.progress-main p{font-size:12px;line-height:1.8;margin:5px 0 0;color:#66778d}.progress-heading>.el-button{flex-shrink:0}.owner{display:inline-block;font-size:11px;color:#7d6b4a;background:#fff4df;border-radius:4px;padding:2px 6px;margin-left:10px;vertical-align:middle}.progress-tracks{display:grid;grid-template-columns:repeat(5,minmax(0,1fr));gap:10px;margin-top:14px}.progress-track{display:flex;flex-direction:column;align-items:flex-start;text-align:left;padding:12px;border:1px solid #e2e8f0;border-radius:6px;background:#fff;cursor:pointer;font-family:inherit;min-width:0}.progress-track:hover{border-color:#82b4f7}.progress-track:focus-visible{outline:2px solid #1677ff;outline-offset:2px}.track-title{font-size:12px;color:#74849a}.progress-track strong{display:block;font-size:13px;font-weight:500;line-height:1.8;margin:6px 0;color:#354c68}.track-detail{font-size:11px;color:#7b8a9d;line-height:1.8;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}.track-link{margin-top:8px;font-size:11px;color:#1677ff}.progress-track--warning strong{color:#a46912}.progress-track--success strong{color:#328062}.progress-times{display:flex;flex-wrap:wrap;gap:5px 20px;color:#8491a4;font-size:11px;line-height:1.8;margin-top:12px}.refund-progress--warning{border-color:#ebd5aa}.refund-progress--success{border-color:#cfe4da}.other-progress-tasks{margin-top:10px;border-bottom:0}.progress-task{display:flex;align-items:center;justify-content:space-between;gap:16px;padding:10px 0;border-bottom:1px solid #edf1f6;font-size:12px}.progress-task p{font-size:12px;margin:4px 0;color:#758397;line-height:1.8}.progress-task .el-button{flex-shrink:0}.progress-task b{font-weight:500}@media(max-width:1000px){.progress-tracks{grid-template-columns:repeat(3,minmax(0,1fr))}}@media(max-width:650px){.progress-tracks{grid-template-columns:repeat(2,minmax(0,1fr))}.progress-heading{align-items:flex-start;flex-direction:column;gap:10px}.progress-task{align-items:flex-start;flex-direction:column}}
</style>
