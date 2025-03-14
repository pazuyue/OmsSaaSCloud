<template>
  <el-row>
    <el-dialog :title="title" :visible.sync="localExamineOpen" width="90%" append-to-body @close="handleClose">
      <el-collapse v-model="activeName" accordion>
        <el-collapse-item :title="'分货规则: ' + ruleStockInfo.ruleCode" name="1">
          <el-descriptions>
            <el-descriptions-item label="分货单名称">{{ruleStockInfo.ruleName}}</el-descriptions-item>
            <el-descriptions-item label="分货单规则">
              <dict-tag :options="dict.type.inventory_allocation_rule_type" :value="ruleStockInfo.ruleType"/>
            </el-descriptions-item>
            <el-descriptions-item label="分货范围">
              <dict-tag :options="dict.type.goods_range" :value="ruleStockInfo.ruleRange"/>
            </el-descriptions-item>
            <el-descriptions-item label="分货单状态">
              <dict-tag :options="dict.type.inventory_allocation_status" :value="ruleStockInfo.status"/>
            </el-descriptions-item>
          </el-descriptions>
        </el-collapse-item>
      </el-collapse>
    </el-dialog>
  </el-row>
</template>

<script>
import {getInfo} from "@/api/ruleStock/info";

export default {
  name: "ruleDetails",
  dicts: ['oms_yes_no', 'inventory_allocation_rule_type', 'goods_type','inventory_allocation_status','goods_range'],
  props: {
    examineOpen: {
      type: Boolean,
      default: false
    },
    ruleId: {
      type: Number,
      default: 0
    }
  },
  data() {
    return {
      localExamineOpen: this.examineOpen, // 初始化本地数据属性
      title: "规则明细",
      ruleStockInfo: {},
      activeName: '1',
    }
  },
  created() {

  },
  watch: {
    examineOpen(newVal) {
      this.localExamineOpen = newVal; // 监听 prop 变化并更新本地数据属性
      if (this.ruleId){
        this.getTickets();
      }
    }
  },
  methods: {
    cancel() {
      this.localExamineOpen = false
    },
    handleClose() {
      this.localExamineOpen = false; // 更新本地数据属性
      this.$emit('handleCancel');
    },
    getTickets() {
      getInfo(this.ruleId).then(response => {
        this.ruleStockInfo = response.data;
      });
    },
  }
}
</script>

<style scoped>

</style>
