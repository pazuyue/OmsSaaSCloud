<template>
  <el-card class="app-container" style="margin: 5px">
    <!-- 用户导入对话框 -->
    <el-dialog :title="title" :visible.sync="open" width="400px" append-to-body @close="handleClose">
      {{data}}
    </el-dialog>
  </el-card>
</template>

<script>
export default {
  name: "noTicketsTmpList",
  props: {
    title: {
      type: String,
      default: ''
    },
    data: {
      type: Object,
      default: ''
    },
    open: {
      type: Boolean,
      default: false
    },
  },
  methods: {
    handleClose() {
      this.$emit('update:open', false); // 通知父组件关闭
    }
  }
}
</script>

<style scoped>

</style>
