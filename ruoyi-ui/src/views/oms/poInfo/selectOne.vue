<template>
  <el-dialog :title="title" :visible.sync="open" width="90%" append-to-body>
    <el-collapse v-model="activeName" accordion>
      <el-collapse-item :title="'采购信息 ' + poInfo.poSn" name="1">
        <el-descriptions>
          <el-descriptions-item label="采购单名称">{{poInfo.poName}}</el-descriptions-item>
          <el-descriptions-item label="供应商编码">{{poInfo.supplierSn}}</el-descriptions-item>
          <el-descriptions-item label="虚仓编码">{{poInfo.wmsSimulationCode}}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <dict-tag :options="dict.type.po_state" :value="poInfo.poState"/>
          </el-descriptions-item>
          <el-descriptions-item label="出入库类型"><dict-tag :options="dict.type.actual_warehouse" :value="poInfo.actualWarehouse"/></el-descriptions-item>
          <el-descriptions-item label="计划入库数量">{{poInfo.numberExpected}}</el-descriptions-item>
          <el-descriptions-item label="实际入库数量">{{poInfo.numberActually}}</el-descriptions-item>
          <el-descriptions-item label="计划入库货值">{{poInfo.moneyExpected}}</el-descriptions-item>
          <el-descriptions-item label="实际入库货值">{{poInfo.moneyActually}}</el-descriptions-item>
          <el-descriptions-item label="操作人">{{poInfo.operator}}</el-descriptions-item>
          <el-descriptions-item label="备注">{{poInfo.remarks}}</el-descriptions-item>
        </el-descriptions>
      </el-collapse-item>
    </el-collapse>
    <el-row>
      <el-table
        :data="tableData"
        style="width: 100%"
        max-height="250">
        <el-table-column
          prop="noName"
          label="入库通知单名称"
          width="120">
        </el-table-column>
        <el-table-column
          prop="noSn"
          label="入库通知单号"
          width="120">
        </el-table-column>
        <el-table-column
          prop="batchCode"
          label="批次号"
          >
        </el-table-column>
        <el-table-column
          prop="poSn"
          label="关联采购单号"
          width="120">
        </el-table-column>
        <el-table-column
          prop="relationSn"
          label="关联下游单据">
        </el-table-column>
        <el-table-column
          prop="noState"
          label="单据状态">
        </el-table-column>
        <el-table-column
          prop="noState"
          label="单据状态">
        </el-table-column>
        <el-table-column
          prop="numberExpected"
          label="计划入库数量">
        </el-table-column>
        <el-table-column
          prop="numberActually"
          label="实际入库数量">
        </el-table-column>
        <el-table-column
          prop="priceExpected"
          label="计划入库数量">
        </el-table-column>
        <el-table-column
          prop="priceActually"
          label="实际入库货值">
        </el-table-column>
        <el-table-column
          fixed="right"
          label="操作"
          width="120">
          <template slot-scope="">
            <el-button
              @click.native.prevent=""
              type="text"
              size="small">
              移除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-row>
  </el-dialog>
</template>

<script>
export default {
  name: "selectOne",
  dicts: ['po_state','actual_warehouse'],
  data() {
    return {
      title: "查看采购单",
      activeName: '1',
      tableData: [],
      rules: {
        poName: [
          { required: true, message: "采购单名称不能为空", trigger: "blur" }
        ],
      }
    }
  },
  props: {
    open: {
      type: Boolean,
      default: ""
    },
    poInfo: {
      type: Object,
      default: null
    }
  }
};
</script>

<style scoped>

</style>
