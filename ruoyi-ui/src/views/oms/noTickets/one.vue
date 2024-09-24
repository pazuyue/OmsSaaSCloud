<template>
  <el-row>
    <el-card class="app-container" style="margin: 5px">
      <!-- 用户导入对话框 -->
      <el-dialog :title="noTicket.title" :visible.sync="noTicket.open" width="400px" append-to-body @close="handleClose">
        ok
      </el-dialog>
    </el-card>

  </el-row>
</template>

<script>
export default {
  name: "noTicketDetailed",
  props: {
    open: {
      type: Boolean,
      default: false
    },
    title: {
      type: String,
      default: ''
    },
    noSn: {
      type: String,
      default: ''
    },
  },
  data() {
    return {
      noTicket:{
        noSn: this.noSn,
        title: this.title,
        open: this.open
      },
      // 遮罩层
      loading: true,
      // 选中数组
      ids: [],
      // 非单个禁用
      single: true,
      // 非多个禁用
      multiple: true,
      // 总条数
      total: 0,
      // 采购入库通知单表格数据
      ticketsList: [],
      // 弹出层标题
      dialogTitle: "",
      // 是否显示弹出层
    }
  },
  methods: {
    handleClose() {
      this.noTicket.open = false;
      this.$emit('noTicket:open', this.noTicket.open); // 通知父组件关闭
    },
  }

}
</script>

<style scoped>

</style>
