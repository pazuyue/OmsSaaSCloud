<template>
  <el-row>
    <el-card class="app-container" style="margin: 5px">
      <!-- 用户导入对话框 -->
      <el-dialog :title="noTicket.title" :visible.sync="noTicket.open" width="90%" append-to-body @close="handleClose">
        <el-collapse v-model="activeName" accordion>
          <el-collapse-item :title="'采购信息 ' + noTicket.noSn" name="1">
            <el-descriptions>
              <el-descriptions-item label="状态">
                <dict-tag :options="dict.type.no_state" :value="info.noState"/>
              </el-descriptions-item>
              <el-descriptions-item label="入库通知单名称">{{info.noName}}</el-descriptions-item>
              <el-descriptions-item label="关联采购单号">{{info.poSn}}</el-descriptions-item>
              <el-descriptions-item label="关联单号">{{info.relationSn}}</el-descriptions-item>
              <el-descriptions-item label="指派虚仓编码">{{info.wmsSimulationCode}}</el-descriptions-item>
              <el-descriptions-item label="批次编号">{{info.batchCode}}</el-descriptions-item>
              <el-descriptions-item label="计划到货时间">{{info.expectedCallbackTime}}</el-descriptions-item>
              <el-descriptions-item label="实际入库时间">{{info.actuallyCallbackTime}}</el-descriptions-item>
              <el-descriptions-item label="计划入库数量">{{info.numberExpected}}</el-descriptions-item>
              <el-descriptions-item label="实际入库数量">{{info.numberActually}}</el-descriptions-item>
              <el-descriptions-item label="计划入库货值">{{info.priceExpected}}</el-descriptions-item>
              <el-descriptions-item label="实际入库货值">{{info.priceActually}}</el-descriptions-item>
              <el-descriptions-item label="创建者">{{info.createdUser}}</el-descriptions-item>
              <el-descriptions-item label="创建时间">{{info.createTime}}</el-descriptions-item>
              <el-descriptions-item label="修改时间">{{info.modifyTime}}</el-descriptions-item>
              <el-descriptions-item label="审核者">{{info.reviewerUser}}</el-descriptions-item>
              <el-descriptions-item label="审核时间">{{info.reviewerTime}}</el-descriptions-item>
              <el-descriptions-item label="备注">{{info.remarks}}</el-descriptions-item>
            </el-descriptions>
          </el-collapse-item>
        </el-collapse>
      </el-dialog>
    </el-card>

  </el-row>
</template>

<script>
import {getOne} from "@/api/noTickets/noTickets";

export default {
  name: "noTicketDetailed",
  dicts: ['no_state'],
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
  created() {
    this.getnoTicketsInfo(this.noSn);
  },
  data() {
    return {
      noTicket:{
        noSn: this.noSn,
        title: this.title,
        open: this.open,
      },
      info: {},
      activeName: '1',
    }
  },
  methods: {
    handleClose() {
      this.noTicket.open = false;
      this.$emit('noTicket:open', false); // 通知父组件关闭
    },
    getnoTicketsInfo(noSn) {
      getOne(noSn).then(response => {
        console.log(response)
        this.info = response.data;
      })
    }
  }

}
</script>

<style scoped>

</style>
