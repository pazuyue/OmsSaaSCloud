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
            <el-descriptions-item label="分货仓库">
              <el-tag
                v-for="(item, index) in storeCodeList"
                :key="index"
                type="success"
                class="tag-margin"
              >
                {{ item.storeCode }}
              </el-tag>
            </el-descriptions-item>
          </el-descriptions>
        </el-collapse-item>
      </el-collapse>

      <el-table :data="channelList" style="width: 100%"  ref="table" :row-key="(row)=>row.channelId" class="mt20">
        <el-table-column label="店铺" prop="channelName" align="center" />
        <el-table-column label="分货比例" prop="percentage" align="center" />
        <el-table-column label="小数点处理" prop="decimal_handle_type" align="center">
          <template slot-scope="scope">
            {{ decimalHandleTypeMap[scope.row.decimalHandleType] }}
          </template>
        </el-table-column>
      </el-table>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" @click="submitForm">审核通过</el-button>
        <el-button @click="cancel">取 消</el-button>
      </div>
    </el-dialog>
  </el-row>
</template>

<script>
import {getInfo, getInfoDetails} from "@/api/ruleStock/info";

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
      storeCodeList: [],
      channelList: [],
      activeName: '1',
      decimalHandleTypeMap: {  // 改用对象映射，键为 ID，值为文本
        1: '向下取整',
        2: '向上取整',
        3: '四舍五入'
      }
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
      getInfoDetails(this.ruleId).then(response => {
        console.log(response.data)
        this.storeCodeList = response.data.storeCodeInfoList;
        this.channelList = response.data.channelInfoList;
      })
    },
    submitForm() {
      this.$emit('handleCancel');
    }
  }
}
</script>

<style scoped>
.tag-margin {
  margin-right: 8px;  /* 可根据需求调整数值 */
}
</style>
