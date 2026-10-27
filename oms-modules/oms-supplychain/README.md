# 库存对账与跨实仓调拨

入口：供应链 → 库存对账、跨仓调拨、统一待办；商品库存直接区分仓库实物、调拨在途及公司拥有数量。

- 对账按公司、货主、实仓、SKU、批次和正次品比较；同货主实仓的虚仓先合计。WMS实物=未冻结+冻结，OMS业务占用单独核查。缺失、失败、分页未完整或待入账不能当作零库存，也不能用查询覆盖库存。
- 真实盘盈盘亏建立独立TZ申请，明确虚仓、批次、质量、成本及盘点凭证；审核后后台入账，整个范围的库存版本变化会阻止旧快照调整。奇门盘点通知仅保存验签证据。
- 调拨仅支持同公司、同货主、不同实仓，双方使用真实仓库出入库方式；目的实仓需维护联系人、电话、省市区和地址。审核占用原批次后自动下发DBCK；原仓最终确认后，DBRK计划按实际出库量下发。部分出库保留剩余占用。
- 公司拥有=仓库实物+调拨在途；在途不参与可售。发10收8留下2在途。停止只释放未出库部分；已出库退回原仓须独立DBH收回单，原仓实收后入账。原批次、质量、成本和单位不变；次品继续不可售。
- 源仓占用及目的收货发出库存信号，用于唤醒待分仓订单；店铺库存由现有同步任务在下一轮读取最新快照。渠道和店铺允许仓共同约束可售范围，不能因调入其他仓直接扩大原渠道可售。
- 调拨与对账使用独立有界线程池，每池2线程、128等待槽。扫描只提交到期任务；数据库租约、不可变事件、库存幂等和退避支持补偿，无sleep等待。库存RPC不持供应链业务行锁。列表只读本地当前页汇总。
- 多商品查询最多100个SKU；批次分页每任务一个SKU、最多5000行；普通列表默认近90天、最多93天跨度、每页50条。完整单号可跨日期查询。原始报文需仓库载荷权限。

奇门依据[官方白皮书](https://developer.alibaba.com/docs/doc.htm?articleId=118403&docType=1&treeId=285)：5.7—5.8 DBRK、5.13—5.15 DBCK、5.32 order.cancel、5.34—5.35库存查询、5.38 inventory.report。盘点quantity是有符号变化量，区别于查询绝对量。

```bash
# WSL，沿用本地环境，不改任务或队列策略、不覆盖已有库存
python3 docker/local/configure_warehouse_control.py --local
python3 docker/local/test_warehouse_control.py
python3 docker/local/publish_warehouse_control.py
bash docker/local/build-frontend.sh
# Windows Python：普通业务API、TEST奇门签名回传、保留验收单据
python docker/local/verify_warehouse_control.py
python docker/local/verify_warehouse_control_ui.py
python docker/local/verify_warehouse_control_queries.py
python docker/local/report_warehouse_control.py
```

数据库脚本为`20261026_warehouse_control.sql`与`20261026_stock_movement.sql`，11张新增表的所有字段均有中文备注。安装器仅新增结构、菜单和范围索引元数据，不回填业务状态或库存。
验收数据、逐步库存证据和报告位于`outputs/warehouse-control`；在线报告：`http://127.0.0.1:8088/acceptance/warehouse-control/验收报告.html`。
本轮346项后端回归、28个保留业务场景、18项页面检查和11项只读查询及库存检查通过。
真实外部仓库需联调；本地验收未向真实店铺下发库存。暂未包含跨公司跨货主调拨、自动报损、补货建议或供应商对账结算。

# 采购退仓与质量库存

本轮提供采购退仓业务单和质量处理单，沿用公司隔离、原批次成本、持久化任务及库存幂等能力。
采购退仓从原采购详情的“采购退仓”页签或供应链菜单进入；质量库存在库存菜单独立查询。

## 采购退仓完整生命周期迭代

两批同时落地：新增表单分区、采购来源搜索、原来源与批次可退上限、固定操作栏、全国省市区联动及货主实仓快递目录；列表默认近90天，完整单号可跨日期定位。
详情按商品来源、仓库物流、库存、供应商收货收回、款项和处理记录展示。部分完成与全部取消分别表达，实际出库与库存入账独立。

- 剩余继续申请先确认原仓库停止结果并释放本来源剩余占用，再生成唯一关联草稿；新草稿须独立审核。原单与后续单可双向追溯。
- 多笔退款及抵扣使用`purchase_return_fund`保存不可变明细；冲销追加负数记录，原凭证保留。有效累计金额不能超过实际退仓减去拒收收回后的净货值。
- 供应商签收、拒收、短收按原实际出库行登记凭证；短收不消耗尚未确认余额，登记本身不改变库存。
- 拒收收回使用独立`CGH`单及奇门`entryorder.create/query/confirm`，类型`QTRK`，停止使用`order.cancel`。供应商退回快递与运单来自本次明确登记。
- 仓库实际收回允许正次品质检变化；库存仅按有效实收增加，正品进入可用、次品进入不可售。原退仓出库流水及采购累计实收保留，采购净入库单独加回实际收回量。
- 新收回形成`S<实收来源ID>`与`PURCHASE_RECLAIM_<ID>`真实库存来源，可按原采购成本再次退仓。库存服务限制累计收回不得超过原实际出库，事件重复不重复加库存。
- 审核与来源额度查询批量汇总，库存RPC不持有供应链业务行锁；仓库等待期间不反复执行，失败按下次执行时间退避。

新增表及字段由原安装脚本一并安装，全部字段有中文备注，菜单动作权限分别控制继续、收货登记、收回创建、审核及重试。不回填原业务状态，不修改队列或任务策略。

```bash
# WSL：安装后端数据结构、构建回归及发布
python3 docker/local/configure_purchase_returns.py --local
python3 docker/local/test_purchase_returns.py
python3 docker/local/publish_purchase_returns.py
bash docker/local/build-frontend.sh
# Windows Python：保留TEST资料，按真实接口执行和核对
python docker/local/verify_purchase_return_iteration.py
python docker/local/verify_purchase_return_iteration_ui.py
python docker/local/verify_purchase_return_iteration_queries.py
python docker/local/report_purchase_return_iteration.py
```

本轮310项后端回归、19个完整业务场景、8个基础退仓场景、20项页面检查及14项查询/协议检查通过。逐步库存证据保存在`outputs/purchase-return-iteration`，基础场景另保存在其`base`目录。
报告：`http://127.0.0.1:8088/acceptance/purchase-return-iteration/验收报告.html`。

## 奇门协议约定

按[奇门官方白皮书接口说明](https://developer.alibaba.com/docs/doc.htm?articleId=118403&docType=1&treeId=285)的5.13、5.14、5.15、5.32节接入：

| 业务 | 奇门接口 | 本项目处理 |
|---|---|---|
| 采购退仓下发 | `taobao.qimen.stockout.create` | `CGTH`，绑定供应商、仓库、货主、原行、实际批次及原采购价 |
| 查询原仓单 | `taobao.qimen.stockout.query` | 使用原ERP和仓库单号；查询不替代实际出库回传 |
| 实际出库确认 | `taobao.qimen.stockout.confirm` | 验签后核对原行、SKU、批次及质量，支持中间/最终确认 |
| 停止剩余退仓 | `taobao.qimen.order.cancel` | 明确确认后释放原来源剩余占用；受理结果不明则先查询 |

本轮使用奇门2.0 XML，实际库存质量编码为`ZP`正品、`CC`次品（OMS内部对应`CP`）。
其他仓库质量编码不会静默转换。回传须携带原退仓行号；未完整的明细不扣账，需补齐后回传。
物流展示保留仓库头部和包裹节点的实际物流公司及运单；不会虚构买家运单。
接口成功、仓库受理、实际出库和库存扣账各自展示。重试沿用原单号；结果不明不重建仓单。

## 库存与来源约束

- 采购退仓绑定原成功入账来源，真实采购使用原`WMS_RECEIPT_<明细ID>`，虚拟采购使用原CG单号。
- 一张单使用同一采购、原虚仓；商品逐行保留原供应商、入库单、批次、质量、库存单位和采购成本。
- 审核在原采购锁下校验累计可退额度，提交后后台按批次锁定库存，避免跨服务RPC持有采购行锁。
- 实际出库回传按事件在库存服务内整笔扣账，成功后更新退仓投影。库存已成功而投影失败时按原事件恢复。
- 原采购累计实收保留，退仓和净入库单独核对。停止退仓仅释放尚未实际出库的来源余额。
- 冲突回传保留仓库日志并标记待核对；取得仓库有效更正前不释放占用、不按异常数量扣账。
- 库存流水身份包含原业务单、动作、正次品和原业务行；同SKU同批次混合退仓不会互相冲突。

质量处理支持冻结、按原冻结来源解冻、次品转正，均通过申请及版本审核执行。
转正须填写仓库实际质检凭证并确认，登记OMS质量变化；本轮没有向仓库发送质量变更指令。
转正保持实物总库存不变，分开记录次品减少和正品增加两条流水。不能转正在其他业务已锁定的次品。
采购入库通过显式`purchaseQuality`标记把本轮采购次品计入不可售库存；其他入库继续沿用原质量约定。
不回填原单据或修改原库存。商品汇总核对包含不可售和质量冻结，并与实际仓库、批次数量分开核对。

供应商退款或抵扣使用独立凭证登记，上限为本单实际出库并扣账的原采购货值，不调用银行或平台付款接口。

## 执行与性能

审批提交后使用提交后唤醒及有界线程池执行。数据库业务单和事件保留补偿条件，原入库扫描按公司顺带补偿，
不增加全局自动开关，不使用sleep等待仓库。执行租约防止多个实例重复处理同单。
库存按排序后的SKU互斥，仓库、批次及来源余额在一个本地事务内变更，订单预占和福袋保障仍受保护。
列表不调用库存或仓库；最近交互元数据按当前页批量读取。原入库候选最多200条，按完整采购和可选CG单号定位，
库存批次一次RPC批量查询；明细及日志按需加载，原报文需要独立权限。

## 安装、回归与本地验收

在已有本地项目基础上执行：

```bash
# WSL；所有新增字段和索引有中文备注，不修改任务和队列配置：
python3 docker/local/configure_purchase_returns.py --local
python3 docker/local/test_purchase_returns.py
python3 docker/local/publish_purchase_returns.py
bash docker/local/build-frontend.sh
# Windows Python + Playwright Chromium；保留新建TEST资料：
python docker/local/verify_purchase_returns.py
python docker/local/verify_purchase_returns_ui.py
python docker/local/verify_purchase_returns_queries.py
python docker/local/report_purchase_returns.py
```

权限：`warehouse:purchaseReturn:query/create/approve/cancel/retry/queryWarehouse/funds`，
`inventory:quality:query/create/review`。仅管理员默认授予，普通角色按职责授权。
新增表和字段通过`docker/local/migrations/20261025_purchase_returns.sql`、`20261025_stock_care.sql`及增量安装脚本维护。
输出保存到`outputs/purchase-returns`，仓库使用本地TEST奇门模拟器。
首轮297项相关回归、21个保留场景、15项页面检查和13项查询检查通过。
报告入口：`http://127.0.0.1:8088/acceptance/purchase-returns/验收报告.html`。

## 同一采购退仓单的分次执行与多包裹

新建采购退仓采用一个CGT业务单对应多次仓库执行，奇门`stockout.create`的`deliveryOrderCode`为`CGT…-01/-02`，每次执行使用独立原行编号、外部单号和最终确认。根CGT保存原采购来源、批次、质量、单位及成本；库存占用始终归根CGT，不以新增业务单承接余量。

继续余量先完成已确认出库扣账。原执行尚未最终结束时，先查询不明下发、确认`order.cancel`结果；明确结束后在原CGT追加执行，沿用有效占用。停止仅释放未出库数量。已释放余量先申请恢复，再使用`restoreApprove`权限独立审核，重新校验原来源额度和可用库存后下发。恢复占用使用`rehold`幂等事件，保留累计占用、消耗和释放，`planned_quantity`保持原计划上限；不得通过清空释放数量恢复库存。

连接配置在每次新增执行时冻结回传口径。默认`INCREMENTAL`表示本消息增量，不同`outBizCode`可分次出库。`CUMULATIVE`仅用于已与仓库约定完整累计快照及`deliveryOrder/extendProps/omsSnapshotVersion`顺序版本的对接，计算差量，旧版本只留证。此扩展版本是双方对接约定，不能假定所有奇门仓库默认支持。`SPLIT`按同执行、同`outBizCode`及本消息`totalOrderLines`组装，齐全前不确认出库；与多次增量消息分别处理。组装中的明细阻止停止及释放。执行消息上限200个不同分片、4MB；单条原商品行上限200、单消息包裹上限100。

包裹由仓库`packageCode`独立识别，相同运单不合并包裹，只有头部运单时包裹数量未知。包裹商品到原执行行仅在来源唯一且不超实际出库时自动关联；同SKU多个批次/质量/原来源不能猜测。人工补物流和商品归属使用独立幂等命令，不修改库存。已有包裹商品事实不能覆盖；内容冲突须取得更正凭证并明确确认原事实，保留冲突原报文及已登记商品。存在内容冲突时不能直接按该包裹新增商品验收，可先登记到明确的执行商品行。

供应商实际接受、拒收与短收分别留证，物流签收不自动确认商品。接受与拒收合计受原执行已扣账量限制；短收只记核对证据。收货事实可先归执行行，再补关联包裹；同一事实可以分次关联同一包裹，受事实和包裹双重额度限制。每个补录/关联命令保存请求摘要、完整请求及独立操作记录，重放不重复累计。拒收收回CGH逐条关联`supplier_source_id`，同一原退仓行可按多条拒收来源分别安排，审核时同时检查原行总额及具体来源额度；只有实际QTRK实收才按实际质量和原成本恢复库存。

读取列表不触发WMS请求，执行及包裹按当前页批量汇总，缺少包裹明细的执行单独计数。供应链待办分别归集退仓包裹待核对、恢复余量待审核与供应商商品待验收，点击直达原CGT。详情使用库存报告及本地事实，仓库查询由明确操作触发。后台使用现有有界工作池、持久租约与失败退避；扫描只唤醒到期工作，不在扫描线程同步调用仓库。库存先按SKU排序加锁，再重读占用余额，防止恢复、出库、释放和收回并发使用旧余额。

新增结构见`docker/local/migrations/20261026_purchase_return_fulfillment.sql`，安装脚本只增加结构与权限，不改写保留业务记录、历史库存或全局自动策略。新增表、字段及业务索引有中文备注。独立测试入口：`docker/local/test_purchase_return_fulfillment.py`；保留场景、页面及查询验收脚本分别为`verify_purchase_return_fulfillment.py`、`verify_purchase_return_fulfillment_ui.py`、`verify_purchase_return_fulfillment_queries.py`。验收证据保留在`outputs/purchase-return-fulfillment`，仅使用本地TEST奇门模拟器与签名回传，不代表真实仓库已完成联调。

恢复审核入口先读取`GET /purchaseReturns/{id}/restore/{request}/check`。窗口分别展示原来源实收、本单已退、其他退仓已用/计划占用、来源余额、本次恢复和缺口，并列出占用的具体CGT及其已扣账/未出计划；可在新标签页查看处理后返回刷新。一次核对最多200条原行、200条占用明细，仅一次批量库存余额读取，不调用WMS、不占用库存。多个原入库来源共用同一物理批次时按SKU/虚仓/批次/质量合计核对库存，避免重复使用一份可用量；未查到的库存显示待确认并禁用审核。提交时仍在原采购与业务单锁内重新校验来源额度，额度变化返回结构化最新来源证据。只有该恢复核对/审核请求使用页面内错误处理，防止接口层与页面重复弹出同一错误；其他请求默认提示行为保留。
