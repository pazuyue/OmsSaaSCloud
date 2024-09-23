<template>
  <div class="app-container">
    <el-form :model="queryParams" ref="queryForm" size="small" :inline="true" v-show="showSearch" label-width="68px">
      <el-form-item label="采购单号" prop="poSn">
        <el-input
          v-model="queryParams.poSn"
          placeholder="请输入采购单号"
          clearable
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="采购单名称" prop="poName">
        <el-input
          v-model="queryParams.poName"
          placeholder="请输入采购单名称"
          clearable
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="状态" prop="poState">
        <el-input
          v-model="queryParams.poState"
          placeholder="请输入状态"
          clearable
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="计划入库数量" prop="numberExpected">
        <el-input
          v-model="queryParams.numberExpected"
          placeholder="请输入计划入库数量"
          clearable
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="实际入库数量" prop="numberActually">
        <el-input
          v-model="queryParams.numberActually"
          placeholder="请输入实际入库数量"
          clearable
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="操作人" prop="perationUser">
        <el-input
          v-model="queryParams.perationUser"
          placeholder="请输入操作人"
          clearable
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="修改时间" prop="modifyTime">
        <el-date-picker clearable
                        v-model="queryParams.modifyTime"
                        type="date"
                        value-format="yyyy-MM-dd"
                        placeholder="请选择修改时间">
        </el-date-picker>
      </el-form-item>
      <el-form-item label="公司编码" prop="companyCode">
        <el-input
          v-model="queryParams.companyCode"
          placeholder="请输入公司编码"
          clearable
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="备注" prop="remarks">
        <el-input
          v-model="queryParams.remarks"
          placeholder="请输入备注"
          clearable
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="计划入库货值" prop="moneyExpected">
        <el-input
          v-model="queryParams.moneyExpected"
          placeholder="请输入计划入库货值"
          clearable
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="实际入库货值" prop="moneyActually">
        <el-input
          v-model="queryParams.moneyActually"
          placeholder="请输入实际入库货值"
          clearable
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="单据状态" prop="comeFrom">
        <el-select v-model="queryParams.poState" placeholder="请选择单据状态">
          <el-option
            v-for="dict in dict.type.po_state"
            :key="dict.value"
            :label="dict.label"
            :value="dict.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="出入库类型" prop="actualWarehouse">
        <el-select v-model="queryParams.actualWarehouse" placeholder="请选择是否真实出库">
          <el-option
            v-for="dict in dict.type.actual_warehouse"
            :key="dict.value"
            :label="dict.label"
            :value="dict.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" size="mini" @click="handleQuery">搜索</el-button>
        <el-button icon="el-icon-refresh" size="mini" @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>

    <el-row :gutter="10" class="mb8">
      <el-col :span="1.5">
        <el-button
          type="primary"
          plain
          icon="el-icon-plus"
          size="mini"
          @click="handleAdd"
          v-hasPermi="['PoInfo:poInfo:add']"
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
          v-hasPermi="['PoInfo:poInfo:edit']"
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
          v-hasPermi="['PoInfo:poInfo:remove']"
        >删除</el-button>
      </el-col>
      <el-col :span="1.5">
        <el-button
          type="warning"
          plain
          icon="el-icon-download"
          size="mini"
          @click="handleExport"
          v-hasPermi="['PoInfo:poInfo:export']"
        >导出</el-button>
      </el-col>
      <right-toolbar :showSearch.sync="showSearch" @queryTable="getList"></right-toolbar>
    </el-row>

    <el-table v-loading="loading" :data="poInfoList" @selection-change="handleSelectionChange">
      <el-table-column type="selection" width="55" align="center" />
      <el-table-column label="ID" align="center" prop="id" />
      <el-table-column label="采购单号" align="center" prop="poSn" />
      <el-table-column label="采购单名称" align="center" prop="poName" />
      <el-table-column label="状态" align="center" prop="poState" >
        <template slot-scope="scope">
          <dict-tag :options="dict.type.po_state" :value="scope.row.poState"/>
        </template>
      </el-table-column>
      <el-table-column label="供应商编码" align="center" prop="supplierSn" />
      <el-table-column label="采购部门" align="center" prop="departmentCode" />
      <el-table-column label="虚仓编码" align="center" prop="wmsSimulationCode" />
      <el-table-column label="计划入库数量" align="center" prop="numberExpected" />
      <el-table-column label="实际入库数量" align="center" prop="numberActually" />
      <el-table-column label="操作人" align="center" prop="perationUser" />
      <el-table-column label="修改时间" align="center" prop="modifyTime" width="180">
        <template slot-scope="scope">
          <span>{{ parseTime(scope.row.modifyTime, '{y}-{m}-{d}') }}</span>
        </template>
      </el-table-column>
      <el-table-column label="公司编码" align="center" prop="companyCode" />
      <el-table-column label="备注" align="center" prop="remarks" />
      <el-table-column label="计划入库货值" align="center" prop="moneyExpected" />
      <el-table-column label="实际入库货值" align="center" prop="moneyActually" />
      <el-table-column label="来源" align="center" prop="comeFrom" />
      <el-table-column label="出入库类型" align="center" prop="actualWarehouse">
        <template slot-scope="scope">
          <dict-tag :options="dict.type.actual_warehouse" :value="scope.row.actualWarehouse"/>
        </template>
      </el-table-column>
      <el-table-column label="操作" align="center" class-name="small-padding fixed-width" fixed="right">
        <template slot-scope="scope">
          <el-button
            size="mini"
            type="text"
            icon="el-icon-edit"
            @click="handleSelect(scope.row)"
            v-hasPermi="['warehouse:poInfo:query']"
          >查看</el-button>
          <el-button
            size="mini"
            type="text"
            icon="el-icon-edit"
            @click="handleUpdate(scope.row)"
            v-if="scope.row.poState===1"
            v-hasPermi="['warehouse:poInfo:edit']"
          >修改</el-button>
          <el-button
            size="mini"
            type="text"
            icon="el-icon-delete"
            @click="handleDelete(scope.row)"
            v-if="scope.row.poState===1"
            v-hasPermi="['warehouse:poInfo:remove']"
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

    <!-- 添加或修改采购单对话框 -->
    <el-dialog :title="title" :visible.sync="open" width="600px" append-to-body>
      <el-form ref="form" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="采购单名称" prop="poName">
          <el-input v-model="form.poName" placeholder="请输入采购单名称" />
        </el-form-item>
        <el-form-item label="供应商" prop="ownerCode">
          <el-select v-model="form.supplierSn" placeholder="请选择">
            <el-option
              v-for="item in supplierSnCodeOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value">
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item label="采购部门" prop="departmentName">
          <el-input v-model="form.departmentName" placeholder="采购部门" readonly/>
        </el-form-item>
        <el-form-item label="虚仓" prop="wmsSimulationCode">
          <el-select v-model="form.wmsSimulationCode" placeholder="请选择">
            <el-option
              v-for="item in wmsSimulationCodeOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value">
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item label="出入库类型" prop="actualWarehouse">
          <el-select v-model="form.actualWarehouse" placeholder="请选择是否真实出库">
            <el-option
              v-for="dict in dict.type.actual_warehouse"
              :key="dict.value"
              :label="dict.label"
              :value="dict.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="备注" prop="remarks">
          <el-input v-model="form.remarks" placeholder="请输入备注" />
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" @click="submitForm">确 定</el-button>
        <el-button @click="cancel">取 消</el-button>
      </div>
    </el-dialog>
    <selectOne ref="selectOne" :poId="poId" :open2="open2" @update:open2="updateOpen2"></selectOne>
  </div>
</template>

<script>
import { listPoInfo, getPoInfo, delPoInfo, addPoInfo, updatePoInfo } from "@/api/poInfo/poInfo";
import {listSupplier} from "@/api/supplier/supplier";
import {listSimulationStore} from "@/api/simulationStore/simulationStore";
import selectOne from "./selectOne"

export default {
  name: "PoInfo",
  components:{selectOne},
  dicts: ['po_state','actual_warehouse'],
  data() {
    return {
      name: "PoInfo",
      // 遮罩层
      loading: true,
      // 选中数组
      ids: [],
      poId: 0,
      // 非单个禁用
      single: true,
      // 非多个禁用
      multiple: true,
      // 显示搜索条件
      showSearch: true,
      // 总条数
      total: 0,
      // 采购单表格数据
      poInfoList: [],
      supplierSnCodeOptions:[],
      wmsSimulationCodeOptions:[],
      // 弹出层标题
      title: "",
      // 是否显示弹出层
      open: false,
      open2: false,
      // 查询参数
      queryParams: {
        pageNum: 1,
        pageSize: 10,
        poSn: null,
        poName: null,
        poState: null,
        supplierSn: null,
        departmentCode: null,
        wmsSimulationCode: null,
        numberExpected: null,
        numberActually: null,
        perationUser: null,
        modifyTime: null,
        companyCode: null,
        remarks: null,
        moneyExpected: null,
        moneyActually: null,
        comeFrom: null,
        actualWarehouse: null
      },
      // 表单参数
      form: {},
      // 表单校验
      rules: {
        poName: [
          { required: true, message: "采购单名称不能为空", trigger: "blur" }
        ],
        supplierSn: [
          { required: true, message: "供应商编码不能为空", trigger: "change" }
        ],
        departmentCode: [
          { required: true, message: "采购部门不能为空", trigger: "change" }
        ],
        wmsSimulationCode: [
          { required: true, message: "虚仓编码不能为空", trigger: "change" }
        ],
      }
    };
  },
  created() {
    this.getSupplierCodeOptions();
    this.getWmsSimulationCodeOptions();
    this.getList();
  },
  methods: {
    /** 查询采购单列表 */
    getList() {
      this.loading = true;
      listPoInfo(this.queryParams).then(response => {
        this.poInfoList = response.rows;
        this.total = response.total;
        this.loading = false;
      });
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
        poName: null,
        supplierSn: null,
        departmentCode: null,
        departmentName: null,
        wmsSimulationCode: null,
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
      var user = this.$store.state.user;
      this.form.departmentCode = user.deptId
      this.form.departmentName = user.dept.deptName
      this.open = true;
      this.title = "添加采购单";
    },
    /** 修改按钮操作 */
    handleUpdate(row) {
      this.reset();
      const id = row.id || this.ids
      getPoInfo(id).then(response => {
        this.form = response.data;
        this.open = true;
        this.title = "修改采购单";
      });
    },
    handleSelect(row){
      this.poId =  row.id;
      this.open2 = true;
      this.title = "查看采购单";
    },
    updateOpen2(value) {
      this.open2 = value;
    },
    /** 提交按钮 */
    submitForm() {
      this.$refs["form"].validate(valid => {
        if (valid) {
          if (this.form.id != null) {
            updatePoInfo(this.form).then(response => {
              this.$modal.msgSuccess("修改成功");
              this.open = false;
              this.getList();
            });
          } else {
            addPoInfo(this.form).then(response => {
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
      this.$modal.confirm('是否确认删除采购单编号为"' + ids + '"的数据项？').then(function() {
        return delPoInfo(ids);
      }).then(() => {
        this.getList();
        this.$modal.msgSuccess("删除成功");
      }).catch(() => {});
    },
    /** 导出按钮操作 */
    handleExport() {
      this.download('supplychain/poInfo/export', {
        ...this.queryParams
      }, `poInfo_${new Date().getTime()}.xlsx`)
    },
    getSupplierCodeOptions(){
      this.supplierSnCodeOptions = [];
      listSupplier().then(response => {
        for (let i = 0; i < response.data.length; i++){
          this.supplierSnCodeOptions.push({
            label: response.data[i].supplierName,
            value: response.data[i].supplierSn
          })
        }
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
  }
};
</script>

