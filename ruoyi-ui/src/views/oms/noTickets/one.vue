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


        <el-table v-loading="loading" :data="ticketsGoodsList">
          <el-table-column label="入库通知单号" align="center" prop="noSn" />
          <el-table-column label="产品名称" align="center" prop="goodsName" />
          <el-table-column label="货号" align="center" prop="goodsSn" />
          <el-table-column label="SKU" align="center" prop="skuSn" />
          <el-table-column label="条形码" align="center" prop="barcodeSn" />
          <el-table-column label="采购价格" align="center" prop="purchasePrice" />
          <el-table-column label="正品计划入库数量" align="center" prop="zpNumberExpected" />
          <el-table-column label="次品计划入库数量" align="center" prop="cpNumberExpected" />
          <el-table-column label="正品实际入库数量" align="center" prop="zpNumberActually" />
          <el-table-column label="次品实际入库数量" align="center" prop="cpNumberActually" />
        </el-table>
        <pagination
          v-show="total>0"
          :total="total"
          :page.sync="queryParams.pageNum"
          :limit.sync="queryParams.pageSize"
          @pagination="getList"
        />
        <div slot="footer" class="dialog-footer">
          <el-button type="primary" @click="submitForm">确 定</el-button>
          <el-button @click="handleClose">取 消</el-button>
        </div>
      </el-dialog>
    </el-card>
  </el-row>
</template>

<script>
import {getOne, getTicketsGoods, noTicketsExamine} from "@/api/noTickets/noTickets";

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
      // 总条数
      total: 0,
      // 遮罩层
      loading: true,
      noTicket:{
        noSn: this.noSn,
        title: this.title,
        open: this.open,
      },
      queryParams: {
        pageNum: 1,
        pageSize: 10,
        noSn: null,
      },
      info: {},
      // 采购入库通知单表格数据
      ticketsGoodsList: [],
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
        this.queryParams.noSn = noSn;
        this.getList();
      })
    },
    /** 查询采购入库通知单列表 */
    getList() {
      this.loading = true;
      getTicketsGoods(this.queryParams).then(response => {
        this.ticketsGoodsList = response.rows;
        this.total = response.total;
        this.loading = false;
      });
    },
    submitForm() {
      noTicketsExamine(this.noSn).then(response => {
        this.$message.success(response.msg)
        this.handleClose()
      })
    }
  }

}
</script>

<style scoped>

</style>
