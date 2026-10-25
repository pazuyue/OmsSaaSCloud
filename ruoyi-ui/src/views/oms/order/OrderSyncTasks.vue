<template>
  <div v-loading="loading" class="app-container order-sync-tasks">
    <div class="toolbar"><div><h2>同步任务</h2><p class="hint">补拉订单、退款与换货，查询同步进度和失败原因。自动同步规则在业务设置维护。</p></div><div><el-button v-if="configurable" size="small" icon="el-icon-setting" @click="settings">业务设置</el-button><el-button v-if="syncTypes.length" type="primary" size="small" @click="backfill">补拉数据</el-button><el-button size="small" @click="refresh">刷新任务</el-button></div></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <intake-tasks v-if="ready&&taskTypes.length" ref="tasks" :channels="channels" :types="taskTypes" :sync-types="syncTypes" :initial-query="$route.query" />
    <el-empty v-else-if="ready" description="当前账号没有同步任务查看权限" />
    <intake-backfill ref="backfill" :channels="channels" :types="syncTypes" @submitted="submitted" />
  </div>
</template>
<script>
import IntakeTasks from './IntakeTasks'
import IntakeBackfill from './IntakeBackfill'
import { intakeOverview } from '@/api/order/intake'
import { settingsLocation } from './orderBusinessPresentation'
export default {
  name: 'OrderSyncTasks', components: { IntakeTasks, IntakeBackfill },
  data: () => ({ loading: false, ready: false, error: '', channels: [], taskTypes: [], syncTypes: [], configurable: false }),
  watch: { '$route.fullPath'() { this.applyRoute() } }, created() { this.load() },
  methods: {
    async load() { this.loading = true; this.error = ''; try { const data = (await intakeOverview()).data; this.channels = data.rows; this.taskTypes = data.task_types; this.syncTypes = data.sync_types; this.configurable = data.config_types.length > 0; this.ready = true; this.$nextTick(() => { if (this.$route.query.action === 'backfill') this.backfill() }) } catch (_) { this.error = '同步任务资料加载失败，请重试' } finally { this.loading = false } },
    applyRoute() { if (!this.ready) return; if (this.$route.query.action === 'backfill') this.backfill(); else if (this.$refs.tasks) this.$refs.tasks.filter(this.$route.query.kind || '', this.$route.query.taskId || '', this.$route.query.channelId || '') },
    refresh() { if (this.$refs.tasks) this.$refs.tasks.load(); else this.load() },
    backfill() { this.$refs.backfill.open(this.$route.query.kind, this.$route.query.channelId ? Number(this.$route.query.channelId) : null) },
    submitted(result) { const data = result.data || {}; const taskId = data.id || (data.task_ids || [])[0] || ''; this.$router.replace({ path: '/oms-order/syncTasks', query: { kind: result.kind, taskId: taskId, ...(this.$route.query.channelId ? { channelId: this.$route.query.channelId } : {}) }}); if (this.$refs.tasks) this.$refs.tasks.filter(result.kind, taskId, this.$route.query.channelId || '') },
    settings() { this.$router.push(settingsLocation('overview', this.$route.query.channelId)) }
  }
}
</script>
<style scoped>.toolbar{display:flex;justify-content:space-between;align-items:center;gap:16px;margin-bottom:20px}.toolbar h2{font-size:20px;font-weight:500;margin:0 0 8px}.hint{font-size:12px;color:#758397;line-height:1.8}.toolbar>div:last-child{white-space:nowrap}</style>
