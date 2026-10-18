# 仓库交互日志中心

入口：供应链 → 仓库交互日志（`/oms-supplychain/wmsLogs`）。采购入库、仓库资料和发货单的原日志入口均跳转至同一页面，单据入口带完整执行单号。

## 查询语义

- 默认最近 24 小时；时间范围最多 31 天。完整执行单号、OMS 订单号、平台单号、仓库单号、请求编号支持精确查询及“本单全部时间”。
- 每页默认 50 条、最多 100 条，使用时间和 ID 的复合游标；新日志不会导致向后翻页重复读取原页记录。
- OMS、平台及仓库单号先通过标量索引定位任务，再查询该公司、业务类型和任务对应的日志。一次最多关联 1000 张任务，超过时引导使用执行单号。
- 新下发快照带 `order_id`、`order_sn`；供应链任务保存这些标量及 `platform_tid`，查询不扫描原始业务 JSON。安装脚本不回填历史任务。
- 同一入库任务与出库任务可能有相同 ID，关联时必须同时限定公司及业务动作类型。无法关联的回传仍在列表显示，不使用未验签内容强制关联。

## 接口结果与业务进度

`wms_interaction_log.result` 是本次交互结果，不被后续记账或人工核对覆盖。页面“当前单据处理”另从本地任务、入库回传明细、出库事件及核对记录读取当前进度，同页按任务批量聚合，不逐行发起请求。

例如回传成功但记账失败：本次接口结果仍为成功，当前单据处理显示回传记账异常。冲突回传拒绝时，显示待人工核对及冲突回传未扣账。仓库取消成功不表示 OMS 的库存释放已经完成，具体截单流程须打开原单核实。

## 权限与接口

- `warehouse:wms:log`：列表、统计、筛选选项、详情摘要和链路。
- `warehouse:wms:payload`：按需查看原有脱敏报文，沿用独立报文接口。列表和详情摘要不返回报文或连接密钥。
- 新只读接口：`GET /wmsIntegration/log-center`、`log-center-summary`、`log-center-options`、`log-center/{id}`。
- 所有查询显式限定可信公司；停用的仓库连接也保留为筛选选项。查询不调用 WMS、不补发请求、不写业务状态。

## 安装与验证

执行 `python3 docker/local/configure_wms_log_center.py` 安装查询字段、索引及管理员菜单。标准 WMS schema/runtime 安装流程也分别接入字段索引和菜单安装。部署订单服务、供应链服务及前端。

后端运行 `docker/local/test_purchase_workspace.py`（包含 `WmsLogQueryTest`）；订单运行 `docker/local/test_orders.py --order-only`；页面只读验收运行 `docker/local/verify_wms_log_center.py`。页面验收产物保存至 `outputs/wms-log-center`。
