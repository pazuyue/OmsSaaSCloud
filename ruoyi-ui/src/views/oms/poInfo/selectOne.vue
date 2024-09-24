<template>
  <el-row>
    <el-dialog :title="title" :visible.sync="localOpen2" width="90%" append-to-body @close="handleClose">
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

      <el-row :gutter="10" class="mb8">
        <el-form :model="queryParams" ref="queryForm" size="small" :inline="true" v-show="showSearch" label-width="100px">
          <el-form-item label="入库单号" prop="noSn">
            <el-input
              v-model="queryParams.noSn"
              placeholder="请输入入库单号"
              clearable
              @keyup.enter.native="handleQuery"
            />
          </el-form-item>
          <el-form-item label="关联入库单号" prop="relationSn">
            <el-input
              v-model="queryParams.relationSn"
              placeholder="请输入关联单号"
              clearable
              @keyup.enter.native="handleQuery"
            />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" icon="el-icon-search" size="mini" @click="handleQuery">搜索</el-button>
            <el-button icon="el-icon-refresh" size="mini" @click="resetQuery">重置</el-button>
          </el-form-item>
        </el-form>
      </el-row>
      <el-row :gutter="10" class="mb8">
        <el-col :span="1.5">
          <el-button
            type="primary"
            plain
            icon="el-icon-plus"
            size="mini"
            @click="handleAdd"
            v-hasPermi="['system:tickets:add']"
          >新增</el-button>
        </el-col>
        <el-col :span="1.5">
          <el-button
            type="success"
            plain
            icon="el-icon-edit"
            size="mini"
            :disabled="single"
            @click="handleUpdate"
            v-hasPermi="['system:tickets:edit']"
          >修改</el-button>
        </el-col>
        <el-col :span="1.5">
          <el-button
            type="danger"
            plain
            icon="el-icon-delete"
            size="mini"
            :disabled="multiple"
            @click="handleDelete"
            v-hasPermi="['system:tickets:remove']"
          >删除</el-button>
        </el-col>
        <el-col :span="1.5">
          <el-button
            type="warning"
            plain
            icon="el-icon-download"
            size="mini"
            @click="handleExport"
            v-hasPermi="['system:tickets:export']"
          >导出</el-button>
        </el-col>
        <right-toolbar :showSearch.sync="showSearch" @queryTable="getList"></right-toolbar>
      </el-row>
      <el-row>
        <el-table v-loading="loading" :data="ticketsList" @selection-change="handleSelectionChange">
          <el-table-column type="selection" width="55" align="center" />
          <el-table-column label="ID" align="center" prop="id" />
          <el-table-column label="采购单号" align="center" prop="poSn" />
          <el-table-column label="入库单号" align="center" prop="noSn" />
          <el-table-column label="关联单号" align="center" prop="relationSn" />
          <el-table-column label="入库单名称" align="center" prop="noName" />
          <el-table-column label="指派虚仓编码" align="center" prop="wmsSimulationCode" />
          <el-table-column label="批次编号" align="center" prop="batchCode" />
          <el-table-column label="计划到货时间" align="center" prop="expectedCallbackTime" width="180">
            <template slot-scope="scope">
              <span>{{ parseTime(scope.row.expectedCallbackTime, '{y}-{m}-{d}') }}</span>
            </template>
          </el-table-column>
          <el-table-column label="实际入库时间" align="center" prop="actuallyCallbackTime" width="180">
            <template slot-scope="scope">
              <span>{{ parseTime(scope.row.actuallyCallbackTime, '{y}-{m}-{d}') }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" align="center" prop="noState">
            <template slot-scope="scope">
              <dict-tag :options="dict.type.no_state" :value="scope.row.noState"/>
            </template>
          </el-table-column>
          <el-table-column label="备注" align="center" prop="remarks" />
          <el-table-column label="计划入库数量" align="center" prop="numberExpected" />
          <el-table-column label="实际入库数量" align="center" prop="numberActually" />
          <el-table-column label="计划入库货值" align="center" prop="priceExpected" />
          <el-table-column label="实际入库货值" align="center" prop="priceActually" />
          <el-table-column label="入库货值差" align="center" prop="priceDifference" />
          <el-table-column label="公司编码" align="center" prop="companyCode" />
          <el-table-column label="修改时间" align="center" prop="modifyTime" width="180">
            <template slot-scope="scope">
              <span>{{ parseTime(scope.row.modifyTime, '{y}-{m}-{d}') }}</span>
            </template>
          </el-table-column>
          <el-table-column label="创建者" align="center" prop="createdUser" />
          <el-table-column label="审核者" align="center" prop="reviewerUser" />
          <el-table-column label="审核时间" align="center" prop="reviewerTime" width="180">
            <template slot-scope="scope">
              <span>{{ parseTime(scope.row.reviewerTime, '{y}-{m}-{d}') }}</span>
            </template>
          </el-table-column>
          <el-table-column label="来源" align="center" prop="comeFrom" />
          <el-table-column label="操作" align="center" class-name="small-padding fixed-width" fixed="right">
            <template slot-scope="scope">
              <el-button
                size="mini"
                type="text"
                icon="el-icon-upload"
                @click="handleImport(scope.row)"
                v-if="scope.row.noState===1"
                v-hasPermi="['warehouse:noTickets:import']"
              >导入</el-button>
              <el-button
                size="mini"
                type="text"
                icon="el-icon-check"
                @click="handleSelect(scope.row)"
                v-hasPermi="['warehouse:noTicketsTmp:list']"
              >查看</el-button>
              <el-button
                size="mini"
                type="text"
                icon="el-icon-edit"
                @click="handleUpdate(scope.row)"
                v-hasPermi="['warehouse:noTickets:edit']"
                v-if="scope.row.noState===1 || scope.row.noState===2"
              >修改</el-button>
              <el-button
                size="mini"
                type="text"
                icon="el-icon-delete"
                @click="handleDelete(scope.row)"
                v-if="scope.row.noState===1"
                v-hasPermi="['warehouse:noTickets:remove']"
              >删除</el-button>
            </template>
          </el-table-column>
        </el-table>

        <pagination
          v-show="total>0"
          :total="total"
          :page.sync="queryParams.pageNum"
          :limit.sync="queryParams.pageSize"
          @pagination="getList"
        />
      </el-row>
    </el-dialog>
    <!-- 添加或修改采购入库通知单对话框 -->
    <el-dialog :title="title" :visible.sync="open" width="70%" append-to-body>
      <el-form ref="form" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="入库单名称" prop="noName">
          <el-input v-model="form.noName" placeholder="请输入入库单名称" />
        </el-form-item>
        <el-form-item label="虚仓编码" prop="wmsSimulationCode">
          <el-select v-model="form.wmsSimulationCode" placeholder="请选择">
            <el-option
              v-for="item in wimulationCodeOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value">
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item label="计划到货时间" prop="expectedCallbackTime">
          <el-date-picker clearable
                          v-model="form.expectedCallbackTime"
                          type="date"
                          value-format="yyyy-MM-dd"
                          placeholder="请选择计划到货时间">
          </el-date-picker>
        </el-form-item>
        <el-form-item label="备注" prop="remarks">
          <el-input type="textarea" v-model="form.remarks" placeholder="请输入备注" />
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" @click="submitForm">确 定</el-button>
        <el-button @click="cancel">取 消</el-button>
      </div>
    </el-dialog>
    <noTicketsUpload :noTicket="form" :title="upload.title" :open="upload.open" @update:open="updateOpen" @update:open2="updateOpen2" v-if="upload.open"  />
    <noTicketDetailed :title="noTicket.title" :noSn="noTicket.noSn" :open="noTicket.open" v-if="noTicket.open" @noTicket:open="ticketOpen"></noTicketDetailed>
  </el-row>
</template>
<script>
import { listTickets, getTickets, delTickets, addTickets, updateTickets } from "@/api/noTickets/noTickets";
import {getPoInfo} from "@/api/poInfo/poInfo";
import {listSimulationStore} from "@/api/simulationStore/simulationStore";
import noTicketsUpload from "./upload";
import noTicketDetailed from "./../noTickets/one";

export default {
  name: "SelectOne",
  components: {noTicketsUpload,noTicketDetailed},
  dicts: ['po_state','actual_warehouse','no_state'],
  props: {
    open2: {
      type: Boolean,
      default: false
    },
    poId: {
      type: Number,
      default: 0
    }
  },
  created() {
    this.getTickets();
    this.getwimulationCodeOptions();
  },
  data() {
    return {
      title: "查看采购单",
      activeName: '0',
      tableData: [],
      // 遮罩层
      loading: true,
      // 选中数组
      ids: [],
      // 非单个禁用
      single: true,
      // 非多个禁用
      multiple: true,
      // 显示搜索条件
      showSearch: true,
      // 总条数
      total: 0,
      // 采购入库通知单表格数据
      ticketsList: [],
      // 是否显示弹出层
      open: false,
      upload: {
        // 是否显示弹出层（用户导入）
        open: false,
        open2:false,
        // 弹出层标题
        title:'',
      },
      noTicket:{
        noSn: null,
        open: false,
        title:'',
      },
      localOpen2: this.open2,
      poInfo:{},
      wimulationCodeOptions:[],
      // 查询参数
      queryParams: {
        pageNum: 1,
        pageSize: 10,
        noSn: null,
        poSn: null,
        relationSn: null,
        noName: null,
        wmsSimulationCode: null,
        batchCode: null,
        expectedCallbackTime: null,
        actuallyCallbackTime: null,
        noState: null,
        remarks: null,
        numberExpected: null,
        numberActually: null,
        priceExpected: null,
        priceActually: null,
        companyCode: null,
        modifyTime: null,
        createdUser: null,
        reviewerUser: null,
        reviewerTime: null,
        comeFrom: null,
        comeFromType: null
      },
      // 表单参数
      form: {},
      // 表单校验
      rules: {
        noSn: [
          { required: true, message: "入库单号不能为空", trigger: "blur" }
        ],
        poSn: [
          { required: true, message: "关联采购单号不能为空", trigger: "blur" }
        ],
        noName: [
          { required: true, message: "入库单名称不能为空", trigger: "blur" }
        ],
        expectedCallbackTime: [
          { required: true, message: "计划到货时间不能为空", trigger: "blur" }
        ]
      }
    }
  },
  watch: {
    open2(newValue) {
      this.localOpen2 = newValue;
      this.getTickets()
    }
  },
  methods: {
    /** 查询采购入库通知单列表 */
    getList() {
      this.loading = true;
      listTickets(this.queryParams).then(response => {
        this.ticketsList = response.rows;
        this.total = response.total;
        this.loading = false;
      });
    },
    getTickets(){
      if (this.poId>0){
        getPoInfo(this.poId).then(response => {
          this.poInfo = response.data;
          this.queryParams.poSn = this.poInfo.poSn;
          this.getList();
        });
      }
    },
    handleImport(row) {
      console.log("商品导入")
      this.upload.title = "商品导入";
      this.reset();
      const id = row.id
      getTickets(id).then(response => {
        this.form = response.data;
      });
      this.upload.open = true;
      this.upload.open2 = true;
    },
    handleSelect(row){
      this.noTicket.open = true;
      this.noTicket.noSn = row.noSn;
      this.noTicket.title = "查看商品信息";
    },
    // 取消按钮
    cancel() {
      this.open = false;
      this.reset();
    },
    // 表单重置
    reset() {
      this.form = {
        id: null,
        noSn: null,
        relationSn: null,
        noName: null,
        wmsSimulationCode: null,
        batchCode: null,
        noState: null,
        remarks: null
      };
      this.resetForm("form");
    },
    /** 搜索按钮操作 */
    handleQuery() {
      this.queryParams.pageNum = 1;
      this.getList();
    },
    /** 重置按钮操作 */
    resetQuery() {
      this.resetForm("queryForm");
      this.handleQuery();
    },
    // 多选框选中数据
    handleSelectionChange(selection) {
      this.ids = selection.map(item => item.id)
      this.single = selection.length!==1
      this.multiple = !selection.length
    },
    /** 新增按钮操作 */
    handleAdd() {
      this.reset();
      this.open = true;
      this.title = "添加采购入库通知单";
    },
    /** 修改按钮操作 */
    handleUpdate(row) {
      this.reset();
      const id = row.id || this.ids
      getTickets(id).then(response => {
        this.form = response.data;
        this.open = true;
        this.title = "修改采购入库通知单";
      });
    },
    /** 提交按钮 */
    submitForm() {
      this.form.poSn = this.poInfo.poSn;
      this.$refs["form"].validate(valid => {
        if (valid) {
          if (this.form.id != null) {
            updateTickets(this.form).then(response => {
              this.$modal.msgSuccess("修改成功");
              this.open = false;
              this.getList();
            });
          } else {
            addTickets(this.form).then(response => {
              this.$modal.msgSuccess("新增成功");
              this.open = false;
              this.getList();
            });
          }
        }
      });
    },
    /** 删除按钮操作 */
    handleDelete(row) {
      const ids = row.id || this.ids;
      this.$modal.confirm('是否确认删除采购入库通知单编号为"' + ids + '"的数据项？').then(function() {
        return delTickets(ids);
      }).then(() => {
        this.getList();
        this.$modal.msgSuccess("删除成功");
      }).catch(() => {});
    },
    /** 导出按钮操作 */
    handleExport() {
      this.download('supplychain/noTickets/export', {
        ...this.queryParams
      }, `tickets_${new Date().getTime()}.xlsx`)
    },
    handleClose(){
      this.$emit('update:open2', false); // 通知父组件关闭
    },
    getwimulationCodeOptions(){
      this.wimulationCodeOptions = [];
      listSimulationStore().then(response => {
        for (let i = 0; i < response.data.length; i++){
          this.wimulationCodeOptions.push({
            label: response.data[i].wmsSimulationName,
            value: response.data[i].wmsSimulationCode
          })
        }
      });
    },
    updateOpen(value){
      this.upload.open = value;
      if (this.upload.open === false)
      {
        this.getList();
      }
    },
    updateOpen2(value){
      this.upload.open2 = value;
    },
    ticketOpen(value){
      this.noTicket.noSn = null;
      this.noTicket.open = value;
      console.log(this.noTicket)
    }
  }
};
</script>

<style scoped>

</style>
