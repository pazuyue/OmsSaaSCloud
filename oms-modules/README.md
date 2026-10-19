<p align="center">
	<img alt="logo" src="https://oscimg.oschina.net/oscnet/up-b99b286755aef70355a7084753f89cdb7c9.png">
</p>
<h1 align="center" style="margin: 30px 0 30px; font-weight: bold;">RuoYi v3.6.4</h1>
<h4 align="center">基于 Vue/Element UI 和 Spring Boot/Spring Cloud & Alibaba 前后端分离的分布式微服务架构</h4>
<p align="center">
	<a href="https://gitee.com/y_project/RuoYi-Cloud/stargazers"><img src="https://gitee.com/y_project/RuoYi-Cloud/badge/star.svg?theme=dark"></a>
	<a href="https://gitee.com/y_project/RuoYi-Cloud"><img src="https://img.shields.io/badge/RuoYi-v3.6.4-brightgreen.svg"></a>
	<a href="https://gitee.com/y_project/RuoYi-Cloud/blob/master/LICENSE"><img src="https://img.shields.io/github/license/mashape/apistatus.svg"></a>
</p>

## 平台简介
OMS SAAS 平台是一个基于SpringBoot/SpringCloud & Alibaba的分布式微服务架构
* PluginFactory 基于company_model_association_info插件关联表进行 插件化开发

## 系统模块

~~~
oms-modules   
├── oms-goods-administration   商品管理服务
├── oms-supplychain   供应链管理服务
├── oms-inventory   库存管理模块
├── oms-order   订单管理服务
├── oms-common-core   公共模块
~~~

## 采购入库与仓库对接

实仓维护物理信息，货主实仓关联维护归属及启用状态。虚仓独立维护 `inbound_mode`、`outbound_mode`（1：WMS 回传；2：自动虚拟），因此同一货主、实仓下的多个虚仓可以使用不同执行方式。本期实现采购入库，出库方式仅保存配置。

真实入库虚仓需绑定可复用的仓库连接，以及平台仓库、货主编码。连接支持奇门 XML 和京东虎符 JSON；其他协议需实现 `WmsProtocol` 并登记后才能使用。审核到货单时保存执行方式和映射快照，异步下发；仓库受理后由签名回传驱动增量收货、正次品分批入账，再更新采购进度。

- `wms_connection`：平台账号、地址、协议版本及加密密钥。
- `wms_inbound_task`：下发任务、配置快照、执行状态和租约。
- `wms_receipt_event`：按平台消息 ID 去重，并检测相同 ID 内容冲突。
- `wms_receipt_line`：实际批次收货增量，每条具有稳定库存幂等键。
- `wms_interaction_log`：请求、回传、查询、取消的脱敏报文及结果。

本地初始化运行 `docker/local/migrate_wms_integration.py` 和 `docker/local/configure_wms_runtime.py`，重建 supplychain、gateway 并重新发布前端。初始化不继承实仓旧执行方式；虚仓须明确配置。部署必须提供 `OMS_WMS_ENCRYPTION_KEY`（32 字节随机值的 Base64 编码），长期妥善保存；已有密文不能通过重新生成该密钥解密。

配置入口位于供应链仓库页面“仓库对接配置”。回调入口是网关 `POST /supplychain/wmsCallback/{公司编码}/{配置回调标识}`，实际外网地址按反向代理前缀拼接。`OMS_WMS_TENANT_ROUTES` 明确指定公司到数据源的路由，例如本地 `QM=master`；多公司用逗号分隔。回调和后台任务均使用此映射，未登记的公司或数据源直接拒绝，不回退默认库。仅此入口免用户登录，仍必须通过平台签名、时间戳和仓库货主校验。报文查看权限与配置维护权限独立。

网络超时的下发进入“结果待确认”，先查询仓库，不能直接重复创建；明确业务拒绝后可以重试。已受理单据取消须等仓库确认；已有收货不能取消。回传成功表示事件已保存，入账失败可在入库单重试，不重复处理成功明细。

当前按完整明细消息接收，支持同一明细多个实际批次及多次增量收货。`totalOrderLines` 与本次明细不匹配的分片报文会明确拒收并留日志，不会提前完成。正式联调需确认平台开通版本、必填扩展字段、回传消息 ID 和分片约定；本地模拟验收不代替平台验收。

回归入口：`docker/local/test_purchase_workspace.py`、`docker/local/test_warehouse_workspace.py`。先在 WSL 运行 `python3 docker/local/wms_http_fixture.py`，再用安装了 Playwright 的 Windows Python 运行 `docker/local/verify_wms_inbound_ui.py`，验证网关、调度、库存和浏览器。验收使用独立前缀的测试数据并在结束时清理；验收结束后停止模拟接口。


## RabbitMQ 可靠业务通知

`oms-messaging` 首期接入渠道的商品拉取、自动绑定，以及订单的销售出库单据同步。业务任务和 `oms_message_outbox` 在同库事务内提交；消息仅含公司、业务 ID、版本及追踪号。消费者按公司路由重新读取原任务，使用租约、版本和原业务幂等规则执行。销售单据同步只更新台账投影，不重新下发仓库或扣减库存。

本地运行 `python3 docker/local/configure_messaging.py` 安装两个业务库中的消息表、审计表和菜单。将 `messaging.env.example` 中的变量补入本地 `.env`，启动 compose 的 rabbitmq，再构建并重建 channel、order、job。远端配置示例见 `docker/local/messaging.example.yml`，需要预先安装相同表结构。队列使用 quorum，生产建议三个节点并配置 TLS、独立服务账号和 vhost 权限；本地单节点不提供节点级高可用。管理台仅绑定本机 15672，账号不写入业务消息。

服务内发布器每两秒读取有界待投递批次，要求持久化消息、mandatory 返回和关联确认。消费者提交处理结果后才手动 ACK。路由异常、非法信封进入服务自己的死信队列，修复需核对来源，不允许在业务 UI 编辑原信封。传输失败仍保留原任务；重发使用原事件 ID。Quartz 的“渠道消息任务补偿扫描”“订单消息任务补偿扫描”默认每 15 秒扫描，通过相同处理入口补偿；等待阈值与每业务类型、每公司的处理批量由队列配置控制，初始为60秒、2条。现有商品拉取和销售单据扫描负责业务计划与缺失通知的补发。

“系统监控 → 消息任务”按服务查看投递状态、处理结果、追踪号和最近 100 次处理记录。处理失败后需填写依据，按原业务校验申请重试，生成新通知并保留旧失败记录；处理成功的任务不能从此处重放。商品未匹配等业务结果仍在平台商品列表中处理。消息信封不承载平台密钥、登录信息、数据库连接名或仓库指令。

回归入口：`python3 docker/local/test_stock_sync.py`。真实本地代理停机、服务重启、队列持久化和重复消息验收：Windows Python 运行 `docker/local/verify_messaging.py`。验收只使用保留的模拟商品与销售单据投影，结果保存在 `outputs/messaging/case.json`。


### 消息队列管理

新增“系统监控 → 消息队列”。平台管理员管理共享队列；消息任务仍按登录公司隔离。三个注册业务类型分别对应 `oms.channel.product-pull.v1`、`oms.channel.product-bind.v1`、`oms.order.sales-document.v1`，各有独立 quorum 死信队列。系统不搬迁旧队列消息或补建历史单据。

平台库 `sys_mq_queue_policy` 是运行配置的唯一来源，配置变更按 revision 乐观锁保护，并在 `sys_mq_operation` 记录依据和变更前后内容。消费实例每五秒主动拉取配置、回报心跳与实际生效版本；不依赖负载均衡请求碰巧命中每个实例。每实例每队列并发最多8，同一服务合计最多12，MQ和数据库补偿共用执行额度。发布器使用两个工作线程和12个等待槽，代理确认慢时不会无限创建线程。

消费者默认不启动，取得有效配置后才开始工作。控制配置超过20秒无法更新时停止领取新任务。暂停先关闭本实例处理入口，等待已领取任务完成，再停止消费容器；数据库补偿使用相同入口。已有消息仍以 Outbox 为准，暂停或额度不足时保留为待投递，恢复后继续原任务。配置修改可能等待在途任务排空；页面以各实例的实际状态确认“已暂停”。重启重新读取暂停状态，不使用默认运行状态覆盖。实例正常退出回报 STOPPED；心跳30秒过期显示异常，超过240秒的失联会话退出生效统计，避免已结束的旧进程长期阻塞新实例确认。

商品拉取每个消息步骤最多调用一个平台接口；应用与店铺额度在平台库共享，通过带锁的短事务协调所有实例。网络或额度不足时延后原任务，不阻塞等待线程。控制策略的重试上限与退避控制临时执行失败；业务明确失败仍保留原业务校验和处理流程。

后端每15秒使用只读监控账号采集已登记队列；业务指标每30秒按公司路由读取有索引的活动任务和最近5分钟结果。UI只读取缓存。采集失败保留上次数据并标明过期；主动暂停抑制消费者缺失、积压和任务等待告警。告警包含持续时间判定及恢复记录。Prometheus通过受内部认证保护的HTTP发现端点读取Nacos已注册的渠道、订单和系统实例，每15秒采集这些实例及RabbitMQ，新实例自动纳入，退出实例自动移除，Grafana提供积压、消费者、死信、业务待办和处理P95等图表。指标不使用公司、订单、SKU作为标签。

本地运行 `configure_messaging.py`、`configure_queue_monitor.py` 和 `configure_queue_metrics.py`，构建并重建system、channel、order；RabbitMQ需挂载 `monitoring/enabled_plugins`。监控配置位于 `docker/local/monitoring`。Grafana本机13000、Prometheus本机19090；监控账号和密码在本地 `.env`，不进入浏览器配置响应或消息信封。实际生产部署需保护指标端点、使用TLS及独立服务权限，并为Prometheus告警配置组织内的通知接收端；本地已经启用告警计算、UI记录和恢复，不预设外部通知地址。

多实例验收入口 `docker/local/verify_queue_management.py`，使用临时的第二个渠道容器，验证统一调参、暂停约束两个处理入口、重启保留暂停、恢复去重、监控故障和恢复。脚本finally恢复原策略、恢复代理并停止移除自己创建的临时容器。验收结果保留于 `outputs/queue-management/case.json`。

监控增量验收可运行 `verify_queue_management.py --monitor-only`：创建临时第二实例，检查动态发现、业务耗时指标和九张Grafana图表，随后清理实例并验证目标移除。


消息异常闭环（2026-10-19）：
- 系统监控 → 消息队列：独立“运行详情 / 配置 / 核查死信”入口，告警按当前公司跳转相关任务。
- 消息任务支持异常分类、处理建议及最近 50 条同业务通知；人工重试保留 parent_event_id。限流等待不消耗或清空失败重试预算。
- SDK 每 20 秒每队列最多收集 10 条死信，先将脱敏元数据写入平台 sys_mq_dead_letter，再 ACK 代理副本；写入失败重新入队，下轮继续，不执行业务。
- 仅经原队列 x-death、信封及本地公司业务通知共同校验的消息可关联公司/业务。无法验证的只保留指纹和原因，不存报文，不允许重放。
- 每轮最多核对 20 条未完成死信的原任务/最新通知；已补偿或后续重试完成显示“业务已完成，无需重试”。无法访问原业务时显示“结果暂无法核实”。
- 人工核查仅留痕，不把业务标记为成功；sys_mq_dead_review 追加保存每次依据。未完成的已关联死信仍保持告警；无法关联的死信登记核查后解除待处理告警。
- Prometheus 同时监控代理死信数量和台账待处理数量，防止副本移出代理后告警提前恢复。监控账户仍只读，收集由各业务服务自己的 RabbitMQ 连接执行。
- 配置缺失导致业务执行前消息失败时，修复配置后可按原待处理商品任务生成新通知；业务已完成、存在有效租约或任务版本变化时拒绝重复操作。
- 本期只扩展表结构，不兼容或回填历史单据。真实模拟验收：python docker/local/verify_message_exceptions.py；定向回归（WSL）：python3 docker/local/test_stock_sync.py --messaging-only。


订单接入队列（2026-10-19）：
- 新增 ORDER_PULL（订单拉取）与 ORDER_CONVERT（平台订单转单），各默认每实例并发 2、预取 1；与销售单据队列共用订单服务总并发上限 12。
- 定时扫描只为拉取、转单创建持久化通知；作废推进和已转单的库存补试继续沿用原业务入口。拉取与转单共用店铺租约，同店铺不会同时提交源页或转单结果。
- 自动拉单、时间补拉、指定 TID 补拉均创建消息。每次消费只读取一页，平台数据、转单通知、逐单结果及分页游标在同一事务提交；重复消息从已提交断点继续。
- work_version 区分原拉单任务的人工恢复；intake_version 区分平台订单源变化及人工转单重试。版本不符、业务订单已存在的通知不会再次转单。新订单接入在扫描、发布和实际执行时遵守公司启用状态；存量履约继续使用延续路由。
- 拉取失败耗尽重试后同步标记业务任务失败；人工恢复保留原分页游标。转单仍使用原商品匹配、需求登记、库存预占、审核、分仓与售后阻断规则。
- 同步任务和平台订单展示消息进度，按页批量查询最新消息，支持业务页与消息链路互相跳转。入口：订单与售后接入 → 同步任务；平台单据 → 订单；系统监控 → 消息队列/消息任务。
- 不回填历史单据。结构安装：configure_messaging.order + management；回归：test_stock_sync.py --order-messaging；本地独立卖家/SKU 验收：verify_order_messaging.py。
