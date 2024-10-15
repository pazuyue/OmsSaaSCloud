<template>
  <div class="app-container">
    <el-form :model="queryParams" ref="queryForm" size="small" :inline="true" v-show="showSearch" label-width="68px">
      <el-form-item label="单据编号" prop="sn">
        <el-input
          v-model="queryParams.sn"
          placeholder="请输入单据编号"
          clearable
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="关联单据号" prop="relationSn">
        <el-input
          v-model="queryParams.relationSn"
          placeholder="请输入关联单据号"
          clearable
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="源单号" prop="originalSn">
        <el-input
          v-model="queryParams.originalSn"
          placeholder="请输入源单号"
          clearable
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="通知成功时间" prop="timeNotify">
        <el-date-picker clearable
                        v-model="queryParams.timeNotify"
                        type="date"
                        value-format="yyyy-MM-dd"
                        placeholder="请选择通知成功时间">
        </el-date-picker>
      </el-form-item>
      <el-form-item label="查询时间" prop="timeQuery">
        <el-date-picker clearable
                        v-model="queryParams.timeQuery"
                        type="date"
                        value-format="yyyy-MM-dd"
                        placeholder="请选择收到反馈完成出入库时间">
        </el-date-picker>
      </el-form-item>
      <el-form-item label="作废成功时间" prop="timeCancel">
        <el-date-picker clearable
                        v-model="queryParams.timeCancel"
                        type="date"
                        value-format="yyyy-MM-dd"
                        placeholder="请选择作废成功时间">
        </el-date-picker>
      </el-form-item>
      <el-form-item label="修改时间" prop="modifyTime">
        <el-date-picker clearable
                        v-model="queryParams.modifyTime"
                        type="date"
                        value-format="yyyy-MM-dd"
                        placeholder="请选择修改时间">
        </el-date-picker>
      </el-form-item>
      <el-form-item label="创建时间" prop="createTime">
        <el-date-picker clearable
                        v-model="queryParams.createTime"
                        type="date"
                        value-format="yyyy-MM-dd"
                        placeholder="请选择创建时间">
        </el-date-picker>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" size="mini" @click="handleQuery">搜索</el-button>
        <el-button icon="el-icon-refresh" size="mini" @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>

    <el-row :gutter="10" class="mb8">
      <el-col :span="1.5">
        <el-button
          type="danger"
          plain
          icon="el-icon-delete"
          size="mini"
          :disabled="multiple"
          @click="handleDelete"
          v-hasPermi="['warehouse:tickets:remove']"
        >作废</el-button>
      </el-col>
      <el-col :span="1.5">
        <el-button
          type="warning"
          plain
          icon="el-icon-download"
          size="mini"
          @click="handleExport"
          v-hasPermi="['warehouse:tickets:export']"
        >导出</el-button>
      </el-col>
      <right-toolbar :showSearch.sync="showSearch" @queryTable="getList"></right-toolbar>
    </el-row>

    <el-table v-loading="loading" :data="ticketsList" @selection-change="handleSelectionChange">
      <el-table-column type="selection" width="55" align="center" />
      <el-table-column label="" align="center" prop="id" />
      <el-table-column label="单据编号" align="center" prop="sn" />
      <el-table-column label="出入库类型" align="center" prop="ticketType" />
      <el-table-column label="关联单据号" align="center" prop="relationSn" />
      <el-table-column label="源单号" align="center" prop="originalSn" />
      <el-table-column label="指派的虚仓编码" align="center" prop="wmsSimulationCode" />
      <el-table-column label="指派的虚仓名称" align="center" prop="wmsSimulationName" />
      <el-table-column label="仓库类型" align="center" prop="storeType">
        <template slot-scope="scope">
          <dict-tag :options="dict.type.wms_type" :value="scope.row.storeType"/>
        </template>
      </el-table-column>
      <el-table-column label="单据状态" align="center" prop="statusTicket">
        <template slot-scope="scope">
          <dict-tag :options="dict.type.wms_status_ticket" :value="scope.row.statusTicket"/>
        </template>
      </el-table-column>
      <el-table-column label="通知状态" align="center" prop="statusNotify">
        <template slot-scope="scope">
          <dict-tag :options="dict.type.wms_status_notify" :value="scope.row.statusNotify"/>
        </template>
      </el-table-column>
      <el-table-column label="查询状态" align="center" prop="statusQuery">
        <template slot-scope="scope">
          <dict-tag :options="dict.type.wms_status_query" :value="scope.row.statusQuery"/>
        </template>
      </el-table-column>
      <el-table-column label="通知成功时间" align="center" prop="timeNotify" width="180">
        <template slot-scope="scope">
          <span>{{ parseTime(scope.row.timeNotify, '{y}-{m}-{d}') }}</span>
        </template>
      </el-table-column>
      <el-table-column label="查询时间" align="center" prop="timeQuery" width="180">
        <template slot-scope="scope">
          <span>{{ parseTime(scope.row.timeQuery, '{y}-{m}-{d}') }}</span>
        </template>
      </el-table-column>
      <el-table-column label="作废成功时间" align="center" prop="timeCancel" width="180">
        <template slot-scope="scope">
          <span>{{ parseTime(scope.row.timeCancel, '{y}-{m}-{d}') }}</span>
        </template>
      </el-table-column>
      <el-table-column label="快递名称" align="center" prop="shippingName" />
      <el-table-column label="快递编码" align="center" prop="shippingCode" />
      <el-table-column label="备注" align="center" prop="remark" />
      <el-table-column label="最终发货仓库编码(实仓)" align="center" prop="realStoreCode" />
      <el-table-column label="修改时间" align="center" prop="modifyTime" width="180">
        <template slot-scope="scope">
          <span>{{ parseTime(scope.row.modifyTime, '{y}-{m}-{d}') }}</span>
        </template>
      </el-table-column>
      <el-table-column label="wms的出入库时间" align="center" prop="wmsActuallyTime" width="180">
        <template slot-scope="scope">
          <span>{{ parseTime(scope.row.wmsActuallyTime, '{y}-{m}-{d}') }}</span>
        </template>
      </el-table-column>
      <el-table-column label="创建人的用户名称" align="center" prop="userName" />
      <el-table-column label="客户编码" align="center" prop="customerNo" />
      <el-table-column label="WMS仓库接单时间" align="center" prop="acceptTime" width="180">
        <template slot-scope="scope">
          <span>{{ parseTime(scope.row.acceptTime, '{y}-{m}-{d}') }}</span>
        </template>
      </el-table-column>
      <el-table-column label="WMS仓库接单回调到OMS时间" align="center" prop="acceptCallbackTime" width="180">
        <template slot-scope="scope">
          <span>{{ parseTime(scope.row.acceptCallbackTime, '{y}-{m}-{d}') }}</span>
        </template>
      </el-table-column>
      <el-table-column label="1 真实出库 2 虚拟出库 " align="center" prop="actualWarehouse">
        <template slot-scope="scope">
          <dict-tag :options="dict.type.actual_warehouse" :value="scope.row.actualWarehouse"/>
        </template>
      </el-table-column>
      <el-table-column label="公司编码" align="center" prop="companyCode" />
      <el-table-column label="操作" align="center" class-name="small-padding fixed-width">
        <template slot-scope="scope">
          <el-button
            size="mini"
            type="text"
            icon="el-icon-delete"
            @click="handleSelect(scope.row)"
            v-hasPermi="['warehouse:tickets:add']"
          >查询</el-button>
          <el-button
            size="mini"
            type="text"
            icon="el-icon-delete"
            @click="handleDelete(scope.row)"
            v-hasPermi="['warehouse:tickets:remove']"
          >作废</el-button>
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

    <!-- 查询 -->
    <el-dialog :title="title" :visible.sync="open" width="500px" append-to-body>
      <el-form ref="form" :model="form" :rules="rules" label-width="80px">
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" @click="submitForm">确 定</el-button>
        <el-button @click="cancel">取 消</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { listTickets, getTickets, delTickets, addTickets, updateTickets } from "@/api/wmsTickets/wmsTickets";

export default {
  name: "Tickets",
  dicts: ['wms_status_ticket','wms_type','wms_status_notify','wms_status_query','actual_warehouse'],
  data() {
    return {
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
      // 出入库单表格数据
      ticketsList: [],
      // 弹出层标题
      title: "",
      // 是否显示弹出层
      open: false,
      // 查询参数
      queryParams: {
        pageNum: 1,
        pageSize: 10,
        sn: null,
        ticketType: null,
        relationSn: null,
        originalSn: null,
        timeNotify: null,
        timeQuery: null,
        timeCancel: null,
        modifyTime: null,
        createTime: null,
      },
      // 表单参数
      form: {},
      // 表单校验
      rules: {
        sn: [
          { required: true, message: "单据编号不能为空", trigger: "blur" }
        ],
        ticketType: [
          { required: true, message: "出入库类型 1：入库 2：出库不能为空", trigger: "change" }
        ],
        relationSn: [
          { required: true, message: "关联单据号不能为空", trigger: "blur" }
        ],
        originalSn: [
          { required: true, message: "源单号不能为空", trigger: "blur" }
        ],
        wmsSimulationCode: [
          { required: true, message: "指派的虚仓编码不能为空", trigger: "blur" }
        ],
        wmsSimulationName: [
          { required: true, message: "指派的虚仓名称不能为空", trigger: "blur" }
        ],
        storeType: [
          { required: true, message: "1电商仓，2门店不能为空", trigger: "change" }
        ],
        statusTicket: [
          { required: true, message: "单据状态，0：待确认，1：已确认待处理，2：已处理完成，3：处理失败，4：待废弃，5：已废弃完成，6：废弃失败 不能为空", trigger: "blur" }
        ],
        statusNotify: [
          { required: true, message: "通知状态， 0：待通知，1：通知成功，2：通知失败 3.已通知待确认不能为空", trigger: "blur" }
        ],
        statusQuery: [
          { required: true, message: "查询状态，0：全部待查询，1：全部查询成功，2：部分查询成功，3：全部查询失败不能为空", trigger: "blur" }
        ],
        shippingName: [
          { required: true, message: "快递名称不能为空", trigger: "blur" }
        ],
        shippingCode: [
          { required: true, message: "快递编码不能为空", trigger: "blur" }
        ],
        realStoreCode: [
          { required: true, message: "最终发货仓库编码(实仓)不能为空", trigger: "blur" }
        ],
        modifyTime: [
          { required: true, message: "修改时间不能为空", trigger: "blur" }
        ],
        createTime: [
          { required: true, message: "创建时间不能为空", trigger: "blur" }
        ],
        userName: [
          { required: true, message: "创建人的用户名称不能为空", trigger: "blur" }
        ],
        customerNo: [
          { required: true, message: "客户编码不能为空", trigger: "blur" }
        ],
        actualWarehouse: [
          { required: true, message: "1 真实出库 2 虚拟出库 不能为空", trigger: "blur" }
        ],
        companyCode: [
          { required: true, message: "公司编码不能为空", trigger: "blur" }
        ]
      }
    };
  },
  created() {
    this.getList();
  },
  methods: {
    /** 查询出入库单列表 */
    getList() {
      this.loading = true;
      listTickets(this.queryParams).then(response => {
        this.ticketsList = response.rows;
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
        sn: null,
        ticketType: null,
        relationSn: null,
        originalSn: null,
        wmsSimulationCode: null,
        wmsSimulationName: null,
        storeType: null,
        statusTicket: null,
        statusNotify: null,
        statusQuery: null,
        timeNotify: null,
        timeQuery: null,
        timeCancel: null,
        shippingName: null,
        shippingCode: null,
        remark: null,
        realStoreCode: null,
        modifyTime: null,
        createTime: null,
        wmsActuallyTime: null,
        userName: null,
        customerNo: null,
        acceptTime: null,
        acceptCallbackTime: null,
        actualWarehouse: null,
        companyCode: null
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
    handleSelect() {
      this.reset();
      this.open = true;
      this.title = "查询出入库单";
    },
    /** 修改按钮操作 */
    handleUpdate(row) {
      this.reset();
      const id = row.id || this.ids
      getTickets(id).then(response => {
        this.form = response.data;
        this.open = true;
        this.title = "修改出入库单";
      });
    },
    /** 提交按钮 */
    submitForm() {
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
      this.$modal.confirm('是否确认删除出入库单编号为"' + ids + '"的数据项？').then(function() {
        return delTickets(ids);
      }).then(() => {
        this.getList();
        this.$modal.msgSuccess("删除成功");
      }).catch(() => {});
    },
    /** 导出按钮操作 */
    handleExport() {
      this.download('supplychain/tickets/export', {
        ...this.queryParams
      }, `tickets_${new Date().getTime()}.xlsx`)
    }
  }
};
</script>
