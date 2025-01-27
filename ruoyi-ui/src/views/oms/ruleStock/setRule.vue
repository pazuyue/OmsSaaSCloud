<template>
  <el-row>
    <el-col :span="24">
      <el-dialog :title="title" :visible.sync="localRuleOpen" width="90%" append-to-body @close="handleClose">
        <el-collapse v-model="activeName" accordion>
          <el-collapse-item :title="'分货规则: ' + ruleStockInfo.ruleCode" name="1">
            <el-descriptions>
              <el-descriptions-item label="分货单名称">{{ruleStockInfo.ruleName}}</el-descriptions-item>
              <el-descriptions-item label="分货单规则">
                <dict-tag :options="dict.type.inventory_allocation_rule_type" :value="ruleStockInfo.ruleType"/>
              </el-descriptions-item>
              <el-descriptions-item label="分货单状态">
                <dict-tag :options="dict.type.inventory_allocation_status" :value="ruleStockInfo.status"/>
              </el-descriptions-item>
            </el-descriptions>
          </el-collapse-item>
        </el-collapse>

        <el-form ref="form" :model="form" label-width="150px" class="mt20">

          <el-form-item label="虚仓" prop="wmsSimulationCode">
            <el-select v-model="form.wmsSimulationCodes" multiple placeholder="请选择">
              <el-option
                v-for="item in wmsSimulationCodeOptions"
                :key="item.value"
                :label="item.label"
                :value="item.value">
              </el-option>
            </el-select>
          </el-form-item>

          <el-form-item label="分货渠道" prop="ruleType">
            <el-select v-model="form.channelIds" multiple placeholder="请选分货渠道" @change="handleChannelChange">
              <el-option
                v-for="item in channelList"
                :key="item.channelId"
                :label="item.channelName"
                :value="item.channelId">
              </el-option>
            </el-select>

            <el-table :data="infoList" style="width: 100%"  ref="table" :row-key="(row)=>row.channelId" class="mt20">
              <el-table-column width="55" align="center">
                <i class="el-icon-s-unfold draggable-handle"></i>
              </el-table-column>
              <el-table-column label="店铺" prop="storeName" align="center" />
              <el-table-column label="优先分货/普通分货" prop="priority" align="center">
                <template slot-scope="scope">
                  <el-select v-model="scope.row.priority" placeholder="请选择">
                    <el-option label="优先分货" value="优先分货"></el-option>
                    <el-option label="普通分货" value="普通分货"></el-option>
                  </el-select>
                </template>
              </el-table-column>
              <el-table-column label="库存基数" prop="stockBase" align="center">
                <template slot-scope="scope">
                  <span>{{ scope.row.stockBase }}</span>
                </template>
              </el-table-column>
              <el-table-column label="百分比（单位：%）" prop="percentage" align="center">
                <template slot-scope="scope">
                  <el-input-number v-model="scope.row.percentage" :min="1" :max="100" label="百分比"></el-input-number>
                </template>
              </el-table-column>
              <el-table-column label="小数点处理" prop="decimalHandling" align="center">
                <template slot-scope="scope">
                  <el-select v-model="scope.row.decimalHandling" placeholder="请选择">
                    <el-option label="向下取整" value="向下取整"></el-option>
                    <el-option label="向上取整" value="向上取整"></el-option>
                    <el-option label="四舍五入" value="四舍五入"></el-option>
                  </el-select>
                </template>
              </el-table-column>
            </el-table>

          </el-form-item>
        </el-form>
      </el-dialog>
    </el-col>
  </el-row>


</template>
<script>
import {getInfo} from "@/api/ruleStock/info";
import {getChannelList} from "@/api/channel/channel";
import {listSimulationStore} from "@/api/simulationStore/simulationStore";
import Sortable from 'sortablejs';
export default {
  name: "setRule",
  dicts: ['oms_yes_no', 'inventory_allocation_rule_type', 'goods_type','inventory_allocation_status','goods_range'],
  props: {
    ruleOpen: {
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
      localRuleOpen: this.ruleOpen, // 初始化本地数据属性
      title: "设置规则",
      ruleStockInfo: {},
      activeName: '1',
      channelList:[],
      wmsSimulationCodeOptions:[],
      form: {
        channelIds: [],
        wmsSimulationCodes:[]
      },
      infoList: []
    }
  },
  created() {
    this.getChannelList();
    this.getWmsSimulationCodeOptions();
  },
  watch: {
    ruleOpen(newVal) {
      this.localRuleOpen = newVal; // 监听 prop 变化并更新本地数据属性
      if (this.ruleId){
        this.reset();
        this.getTickets();
        this.$nextTick(() => {
          this.initSortable();
        });
      }
    }
  },
  methods: {
    reset() {
      this.form = {
        channelIds: [],
        wmsSimulationCodes:[]
      };
      this.infoList = [];
    },
    initSortable() {
      // 确保之前的Sortable实例被销毁
      if (this.sortable) {
        this.sortable.destroy();
      }
      const childComponent = this.$refs.table;
      const el = childComponent.$el.querySelectorAll('.el-table__body-wrapper > table > tbody')[0];

      Sortable.create(el, {
        handle: '.draggable-handle', // 指定只有带有 draggable-handle 类名的元素可以触发拖拽
        onEnd: (evt) => {
          const oldIndex = evt.oldIndex;
          const newIndex = evt.newIndex;
          console.log(
            "结束拖动",
            `拖动前索引${evt.oldIndex}---拖动后索引${evt.newIndex}`
          );
          console.log(this.infoList);
          const movedRow = this.infoList.splice(oldIndex, 1)[0];
          console.log(movedRow.storeName);
          this.infoList.splice(newIndex, 0, movedRow);
          console.log(this.infoList);
        }
      });
    },
    handleClose() {
      this.localRuleOpen = false; // 更新本地数据属性
      this.$emit('cancelRule');
    },
    getTickets() {
      getInfo(this.ruleId).then(response => {
        this.ruleStockInfo = response.data;
      });
    },
    getChannelList() {
      getChannelList().then(response => {
          for (let i = 0; i < response.rows.length; i++){
            this.channelList.push({
              channelId: response.rows[i].channelId,
              channelName: response.rows[i].channelName
            })
          }

        //this.channelList = response.rows;
      });
    },
    getWmsSimulationCodeOptions(){
      this.wmsSimulationCodeOptions = [];
      listSimulationStore().then(response => {
        for (let i = 0; i < response.data.length; i++){
          this.wmsSimulationCodeOptions.push({
            label: response.data[i].wmsSimulationName,
            value: response.data[i].wmsSimulationCode
          })
        }
      });
    },
    handleChannelChange(selectedChannels) {
      // 遍历选择的渠道，检查是否已经在 infoList 中
      selectedChannels.forEach(channelId => {
        const existingItem = this.infoList.find(item => item.channelId === channelId);
        if (!existingItem) {
          // 如果渠道不在 infoList 中，则添加一个新的对象
          const channel = this.channelList.find(item => item.channelId === channelId);
          if (channel) {
            this.infoList.push({
              storeName: channel.channelName, // 或者根据需要设置其他默认值
              priority: '普通分货',
              stockBase: 100,
              percentage: 0.1,
              decimalHandling: '四舍五入',
              channelId: channelId
            });
          }
        }
      });
      // 使用 $nextTick 确保 DOM 更新后再初始化 SortableJS
      this.$nextTick(() => {
        this.initSortable();
      });
    },
  },
}
</script>

<style scoped>

</style>
