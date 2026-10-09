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

## WSL Ubuntu 本地部署

本仓库已增加 `docker/local/` 部署配置，适用于当前 Ubuntu 24.04.3 LTS 的 Docker 环境。
后端为 Spring Boot 2.7.18 / Spring Cloud 2021.0.8，前端为 Vue 2 / Element UI；
本地启动 MySQL、Redis、Nacos、Nginx，以及网关、认证、系统、代码生成、任务、文件、监控、商品、供应链、库存、渠道共 11 个 Java 服务。

在 PowerShell 中进入 WSL，后续命令在 Ubuntu 中执行：

```powershell
wsl -d Ubuntu-24.04
```

```bash
cd /mnt/d/Users/yueguang/OmsSaaSCloud
# 已完成首次构建，日常启动直接运行这一条：
bash docker/local/start.sh

# 查看状态、日志；停止服务并保留数据：
docker compose -f docker/local/compose.yaml ps
docker compose -f docker/local/compose.yaml logs --tail=100 system
docker compose -f docker/local/compose.yaml stop

# 修改代码后的重新构建与启动：
bash docker/local/build.sh
bash docker/local/build-frontend.sh
docker compose -f docker/local/compose.yaml up -d --force-recreate

# 验证真实验证码、登录、用户信息、菜单和基础列表接口：
python3 docker/local/verify.py
```

| 入口 | 地址 | 本地初始账号 |
| --- | --- | --- |
| 管理后台 | http://localhost:8088 | `admin / admin123` |
| Nacos | http://localhost:8848/nacos | 本地关闭鉴权，仅绑定回环地址 |
| 服务监控 | http://localhost:9100 | `ruoyi / 123456` |
| 网关 | http://localhost:8080 | 由前端 `/prod-api/` 代理调用 |
| MySQL | `127.0.0.1:13306`，共 5 个数据库，见下文 | `root`，密码见 `docker/local/.env` |
| Redis | `127.0.0.1:16379` | 本地无密码 |

本地容器以 `oms-local-` 命名，数据库、Redis 和上传文件存放在独立 Docker 数据卷中。
旧 `ruoyi-*` 容器和旧数据库卷保留。SQL 仅在新数据卷第一次启动时初始化，后续启动不清空数据。
`start.sh` 会从仓库 SQL 生成本地配置并发布到本地 Nacos，因此持久修改本地配置请修改 `docker/local/prepare.py`。
其中适配了容器地址、MyBatis-Plus 配置、各 OMS 服务的实体扫描范围、网关路由和本地文件上传。
初始化脚本另补充了当前登录代码需要的 `sys_user.login_company_code` 字段。

后端通过 `local-docker` Maven profile 包含 OMS 模块，编译目录为 WSL 的 `~/.cache/oms-local/source`，
依赖缓存为 `~/.cache/oms-local/m2`，部署 JAR 位于 `docker/local/artifacts/`。
前端产物位于 `docker/local/dist/`，构建与启动日志位于 `docker/local/logs/`。
构建需要 Maven、JDK 17、Node 和 npm；前端脚本已处理旧 Webpack 在新 Node 下的 OpenSSL 兼容参数。

当前部署复用本机已有的 `mysql:5.7`、`redis:latest`、`nginx:latest` 及 `docker_ruoyi-auth:latest` 中的 Java 8 运行时，
并使用 `nacos/nacos-server:v2.2.3`。Java 镜像入口已覆盖，实际运行的是新编译的项目 JAR。
如更换运行时镜像，可在 `.env` 设置 `JAVA_RUNTIME_IMAGE`；镜像需包含兼容的 Java 和用于健康检查的 `curl`。

已导入用户提供的 `oms-saas.zip` 中的 27 张业务表，保留其原始数据。商品、供应链共用
`qm_oms_saas_commodity`，库存使用 `qm_oms_saas_inventory`，渠道使用 `qm_oms_saas_channel`；
基础后台与 Nacos 分别使用 `ry-cloud`、`ry-config`。`.env` 中的 `OMS_BUSINESS_DATABASES=1` 保持此路由。
根据当前代码补齐两张企业插件配置表、库存规则 `type` 字段、18 类字典、14 个业务页面菜单及管理员权限，
管理员的登录企业设置为 `qm`。已有登录会话请退出后重新登录。
缺失插件配置采用代码中的通用默认值；无法从现有资料确定的商品类型保留为 `0 / 未指定`，库存规则 `type` 默认 `0`。
已验证 15 个业务列表查询、字典、菜单和跨服务插件配置查询；完整下单、出入库等写入流程尚未验证。

```bash
# 验证业务数据库与页面读取接口：
python3 docker/local/verify_business.py

# 以后需要补充备份时运行；会在隔离的临时 MySQL 中恢复并核对每张表的记录数：
python3 docker/local/backup_database.py --verify
```

最新完整备份为 `docker/local/backups/oms-saas-complete-20261009-112716.sql`，包含 5 个数据库、78 张表及库存、分货、锁库来源余额、订单回调记录、日常分货轮次/商品记录、Quartz 调度任务、商品库存菜单和查询索引，已在隔离 MySQL 中恢复并核对全部表记录数。
同目录配套 `.json` 记录表数量与校验结果、`.sql.sha256` 记录文件校验和、`.env` 保存对应部署配置。
请一起保留这些文件；备份包含业务数据和连接凭据，已排除在 Git 提交之外。
导入前的原数据库备份保留在 `docker/local/backups/20261008-105158/before-business-import.sql`。
首次导入后的 71 表备份 `oms-saas-complete-20261008-110641.sql` 和本次迁移前备份 `oms-saas-complete-20261008-145432.sql` 同样保留。

恢复到当前本地环境时，在 WSL 的项目根目录执行：

```bash
python3 docker/local/restore_database.py \
  docker/local/backups/oms-saas-complete-20261009-112716.sql --replace-local-data
python3 docker/local/verify_business.py
```

恢复脚本先校验文件及数据库密码，停止应用服务，再备份恢复前的数据，然后导入 SQL、清除本地登录缓存并启动服务。
该操作会替换备份内的同名表；恢复后需重新登录。脚本保留备份中的 Nacos 配置。
如果是在全新的数据卷上重建环境，请在首次启动 MySQL **之前**将配套备份 `.env` 复制为 `docker/local/.env`，
完成镜像及项目构建、运行 `start.sh` 后再执行恢复命令；现有数据卷的 MySQL 密码不会因修改 `.env` 自动变更。
SQL 备份不包含上传文件、Docker 镜像或构建产物。
备份使用单事务快照和单行 INSERT，从实际导出的 SQL 统计恢复校验行数，避免定时任务日志或业务写入期间，独立计数与备份快照不一致而误报。

本地默认关闭 Sentinel 控制台集成，文件上传使用本地数据卷，未部署 FastDFS、MinIO、Seata 或 SkyWalking。

## 本地界面更新

前端采用浅色工作空间布局：顶部切换业务模块，侧栏仅显示当前模块的功能；切换模块时记住本账号、本次会话最后访问的页面。
多页签保留，右上角账户菜单可保存页签显示和动态标题设置。旧布局偏好首次自动切换至新版默认设置。
工作台的模块和快捷入口来自实际菜单权限，最近访问来自本次会话；不展示模拟业务指标。

商品、供应链、库存、渠道及系统列表统一使用可展开的筛选区。商品默认保留常用列，右侧列设置可查看其他字段。
窄屏自动折叠顶部菜单并使用抽屉侧栏，表格支持横向滚动；表单、弹窗、空状态和登录页沿用统一样式。
视觉规范集中在 `ruoyi-ui/src/assets/styles/workspace.scss`，导航逻辑位于 `ruoyi-ui/src/utils/workspaceNavigation.js`。

```bash
# 导航回归：权限过滤、嵌套路由、菜单路径不被修改、模块定位与查询参数
cd ruoyi-ui
npm run test:navigation
```

`build-frontend.sh` 先构建到临时产物目录，成功后发布资源并最后替换入口页，保留旧版本的哈希资源供已打开的页面加载。
日常构建不会删除 Nginx 挂载的静态目录，页面刷新后即可使用新版。

## 库存页面与一致性优化

仓库库存页按公司隔离，支持仓库/商品查询、仅有库存、仅看差异、正次品分组和当前页导出。
右侧详情抽屉按需加载分页批次、预占记录与库存流水；汇总与批次不一致时显示六项数量差额，禁止直接改数。
库存调整须选择批次、正次品、数量及原因，提交版本与请求编号；汇总、批次、OMS 数量和流水同事务更新。
旧批次直接增删改接口已关闭，入库继续走原有入库接口。历史导入数据没有来源流水时明确显示为空，不补造来源。

首次从旧备份升级时，先启动 MySQL，并在停止库存写入的情况下执行：

```bash
python3 docker/local/backup_database.py
python3 docker/local/migrate_inventory.py
python3 docker/local/migrate_allocation.py
python3 docker/local/migrate_product_inventory.py
python3 docker/local/migrate_batch_trace.py
python3 docker/local/migrate_daily_allocation.py
python3 docker/local/index_inventory.py
bash docker/local/build.sh -pl oms-modules/oms-inventory,oms-modules/oms-goods-administration,oms-modules/oms-supplychain,oms-modules/oms-channel,ruoyi-modules/ruoyi-job -am
bash docker/local/build-frontend.sh
docker compose -f docker/local/compose.yaml up -d --force-recreate inventory goods supplychain channel job
python3 docker/local/verify_inventory_workspace.py
python3 docker/local/test_inventory.py
```

迁移补充公司维度唯一键、分页索引与现有库存流水模型对应的表。编码上限为公司 50、SKU 128、仓库 64、批次 128 字符，迁移前检查已有数据长度。
列表固定为计数、当前页查询、当前页批次汇总三个 SQL；名称按页批量查询，页面和明细每页最多 100 条。
写入按公司/SKU 汇总行、排序后的仓库、批次加锁；分货失败向外抛出并回滚，禁止吞掉错误后报成功。
不在持锁事务中调用远程服务，不在事务内部自动重试。调整使用幂等请求编号，预占释放按原单据及批次回溯。
`test_inventory.py` 仅操作独立的 `inventory_workspace_test` 数据库，覆盖并发、真实死锁回滚、幂等、差异核对及大数据分页。

## 商品库存总览

入口为“库存 → 商品库存”，路由 `/oms-inventory/productInventory`。列表以 `oms_inventory` 的公司 + SKU 汇总记录为基础，默认每页 20 个商品；商品名称、SKU、条码使用现有商品查询，次品列默认收起。
总库存显示商品汇总表数值，正次品数量来自仓库明细合计。库存写入链路按虚仓记账，核对范围包含停用仓的现存库存，不再叠加实体仓数据。商品、仓库、批次数值分别显示，出现差异不自动覆盖或补造明细。
详情提供仓库分布、锁库来源、库存流水三个页签，分页按需加载。仓库可进入原有批次详情与调整入口，分货来源可进入原有分货单释放入口；返回商品页保留查询条件、分页和详情页签。
锁库来源显示原锁库、订单占用、已出库、已释放、可释放及渠道余额。订单占用属于锁定库存的其中项；历史来源不完整显示“待核对”。出库流水关联订单行，入库、调整、锁库及释放保留原始单据关联。
新接口 `/inventory/productInventory` 及 `/{id}`、`/{id}/warehouses`、`/{id}/reservations`、`/{id}/history` 均为只读，复用 `wmsInventory:inventory:list` 权限并强制按登录公司查询。跳转后的调整和释放仍检查原有操作权限。
`migrate_product_inventory.py` 增加菜单和四个查询索引，商品页菜单授权沿用原仓库库存菜单读者。数量核对只汇总当前页 SKU，读取使用同一 MVCC 快照，不加库存写锁；默认分页明确使用公司 + ID 索引，避免 MySQL 5.7 对整个公司排序。未提供全库异常扫描、直接编辑商品汇总、冻结或安全库存配置。

## 批次详情追溯

入口沿用“仓库库存 → 查看详情 → 批次明细 → 查看详情”，商品库存的仓库分布也可进入。批次详情嵌入原抽屉，返回保留批次筛选；库存调整继续使用原批次列表的入口。
“本批次流水”支持按入库、调整、锁库、释放、出库筛选，显示本批次前后数量及来源单据、关联订单行。查询使用登录公司和批次 ID，并校验 SKU、仓库，不按可能重复的批次编码推断历史归属。
“锁库去向”以分货单、渠道和当前批次为一行，显示原锁库、剩余锁定、其中订单占用、已出库、已释放、可释放。查看订单时才分页加载该来源的订单行，默认仅看仍占用，可切换查看已出库或取消的历史记录；跨批次订单只显示当前批次分摊量。
来源可跳转原分货单处理释放，返回恢复仓库筛选、批次及页签；来源不完整显示“待核对”，其他锁定余额单独提示。
批次接口 `/{id}`、`/{id}/history`、`/{id}/sources`、`/{id}/sources/{sourceId}/orders` 位于 `/inventory/wmsInventory/wmsInventoryBatch` 下，复用 `wmsInventoryBatch:batch:query` 权限，无新增菜单或写接口。
`migrate_batch_trace.py` 只增加公司 + 批次 + 流水 ID、公司 + 来源 + 订单记录 ID 两个索引。分页上限 100，读取使用 MVCC，不申请库存写锁，不调用远程服务。

## 分货工作台

分货页采用基本信息、仓库与商品、渠道分配、预览与结果四个页签；日常分货另提供执行记录。执行方式准确显示 `1 日常分货 / 2 一次性分货 / 3 锁库时分货`。日常分货仅允许普通配额；类型 3 复用现有一次性锁库、来源追溯和释放流程。历史类型 2 的锁库分货保持兼容。
普通配额重算选中渠道的可售量（扣除订单预占与冻结、最低为零），不预留实物库存；锁库分货增加渠道锁库量并同步预留仓库和原始批次。
按优先级分配明确保存顺序，目标配额为零也会清理旧可售量。渠道独立配额允许普通配额共享实物库存，锁库总量始终不能超过实际可用量。
仓库和渠道按当前登录公司查询，只能选择已启用渠道。导入 SKU 上限 50 字符、5 万行、10 MB，校验并去重后事务替换；失败保留原清单。

新增迁移 `docker/local/migrate_allocation.py` 添加规则版本、渠道顺序、执行记录及查询索引，保留历史库存。
新 API 位于 `/inventory/allocation`，旧无版本写接口和审核 GET 已停用。草稿可以编辑、删除，待审核可以撤回；执行后配置不可修改。
预览最多每页 20 个 SKU，按当前页批量读取库存及渠道数据，不锁库存。开始执行固定商品清单，每个 SKU 一个独立短事务，失败项单独回滚。
每次处理请求最多 10 个 SKU，规则行、OMS SKU、仓库按统一顺序加锁；同规则的并发请求串行处理，成功记录与库存同事务提交。
页面关闭或网络中断后停止发送下一批请求，当前请求可能完成；重新打开详情点击“继续处理”，不会重做成功项。
失败重试按最新库存重新计算，只处理失败项。锁库执行时保存本单、SKU、渠道、批次的来源余额；释放只返还本来源未占用、未出库、未释放的数量，库存与渠道任一校验失败则回滚该 SKU。
列表按当前页批量汇总原锁库、订单占用、已出库、已释放、可释放数量，显示“部分释放 / 释放完成”；商品结果提供相同明细。开始释放后不再接受新订单占用，已占用部分可以继续出库或取消。订单取消只归还本来源余额，用户可再次点击“释放剩余锁库”；出库不会返还可用库存。
历史单据缺少来源余额时显示“待核对”，不根据汇总锁定量推断，必须先核对原始业务记录。已完成的普通配额不会因删除或关闭单据自动恢复旧配额。

订单集成入口为 `POST /inventory/allocation/{ruleId}/reservation/{action}`，动作取 `OCCUPY`（占用）、`CONSUME`（实际出库）、`CANCEL`（取消尚未出库的占用），需登录且有 `ruleStock:info:edit` 权限，公司取自登录身份。
请求 JSON 包含 `skuSn`、`channelId`、`orderLine`（唯一订单行编号，最多 100 字）、`quantity`（正整数）和 `requestId`（16–64 位字母、数字、短横线或下划线）。相同分货单、SKU、请求编号必须重放相同参数；同一订单行对同一单据 SKU 只能首次占用一次，取消后重新占用需新的订单行版本编号。
取消和出库可分次结算，出库数量只能从该订单行的剩余占用扣减；事件记录关联实际批次扣减流水。所有操作沿用规则行、结果行、OMS SKU 锁顺序，不跨 SKU 开长事务，不在事务内调用外部服务。当前仓库未包含订单模块，此入口已实现并验证，后续订单调用方需在确认业务事件后调用，并沿用原请求编号重试。
新增 `20261008_reservation_sources.sql` 由 `migrate_allocation.py` 一并执行，仅增加余额字段和来源、订单占用、回调事件三张表，不回填无法确认的历史消耗量。

`test_inventory.py` 同时运行 20 项分货、10 项库存事务、12 项商品总览与批次追溯、11 项日常分货 MySQL 集成测试，包括真实死锁回滚、并发请求、跨规则锁库、部分出库与释放、取消后再次释放、回调幂等、跨批次渠道来源、租户隔离、差异展示、读取不等待写锁及万级 SKU 分页。批次测试还覆盖重复批次编码隔离、缺失历史关联、跨批次订单分摊及来源订单分页索引。

## 日常分货与现有定时任务

日常规则设置生效时间、结束时间、执行间隔（1–1440 分钟）和规则优先级。保存草稿、提交审核、审核启用后，后台只在 `[生效时间, 结束时间)` 内执行；未来生效的规则等待到时再运行。支持停用、有效期内重新启用和立即执行一轮。审核后的配置不可直接编辑。
每轮以所选仓库正品可用库存重算普通渠道配额，排除正品锁库和次品。可用量已经扣除锁库，不再重复扣减；渠道普通订单预占、冻结沿用原计算方式，渠道锁库余额及来源记录保持不变。普通配额覆盖重算，不累加；已存在且配额无变化的渠道不写库存行。
同一公司、SKU、渠道被多条有效日常规则覆盖时，数字更小的优先级负责该渠道，同值按规则 ID 较小者优先。低优先级规则仍保留计算明细，标记跳过，不覆盖该渠道，也不把跳过的目标重新分摊给其他渠道；高优先级规则失败时不会自动降级覆盖。停用或到期后，其他规则可在下一轮接管。

复用“系统监控 → 定时任务”的 Quartz 模块，不新增独立定时器。迁移注册任务“日常分货规则扫描”，调用目标 `dailyAllocationTask.scan('qm')`，Cron 为 `0/10 * * * * ?`（每 10 秒），禁止并发，错过触发不补跑。任务通过服务发现和内部鉴权调用库存服务，并显式传入公司编码供原数据源路由使用；其他公司需按其数据源编码配置对应任务。公网网关会移除内部鉴权标识，不能直接调用扫描接口。
暂停系统任务会暂停所有对应公司的规则扫描，恢复后从已提交进度继续；它不会停用规则或清理配额。规则自己的停用操作会结束当前轮次，并阻止后续商品处理。到期同样在下一商品事务开始前检查，已开始提交的事务允许完成；最后一次配额保留，不自动清零。

每轮有独立执行记录和商品结果，支持分页、仅看失败、展开渠道变化与优先级原因。商品清单每批最多 2000 个 SKU，准备期间新出现的商品可能在下一轮纳入；处理每次最多 50 个 SKU、约 2 秒工作预算，单个事务超时 15 秒。扫描按规则 ID 轮转，避免大型规则长期占据扫描入口。每个 SKU 使用短事务，锁顺序为规则头、轮次/商品结果、OMS SKU、排序后的仓库和渠道；事务内没有远程调用，也没有自动重试。
库存与商品成功记录同事务提交，多实例或重复触发不会重复处理已成功项。进程中断后由下一次扫描继续未完成轮次；某 SKU 失败会回滚并记录原因，其余商品继续。轮次结束后从结束时间计算下一次间隔，不补跑漏掉的历史周期；失败商品在下一轮按最新库存重新计算。系统任务日志记录扫描调用是否成功，分货执行记录记录每个业务商品的成功与失败。
迁移 `migrate_daily_allocation.py` 新增 5 个规则调度字段、`rule_stock_daily_run` 和 `rule_stock_daily_item` 两张表及查询索引，新增字段和表均带中文 COMMENT。迁移不启用历史分货规则，不更改库存数量。新增 API 为 `/inventory/allocation/{id}/daily/{ENABLE|PAUSE|RUN}`、`/{id}/daily-runs`、`/{id}/daily-runs/{runId}` 及其 `/items`；权限沿用分货编辑和查询权限，业务接口公司来自当前登录用户。

## 平台简介

若依是一套全部开源的快速开发平台，毫无保留给个人及企业免费使用。

* 采用前后端分离的模式，微服务版本前端(基于 [RuoYi-Vue](https://gitee.com/y_project/RuoYi-Vue))。
* 后端采用Spring Boot、Spring Cloud & Alibaba。
* 注册中心、配置中心选型Nacos，权限认证使用Redis。
* 流量控制框架选型Sentinel，分布式事务选型Seata。
* 提供了技术栈（[Vue3](https://v3.cn.vuejs.org) [Element Plus](https://element-plus.org/zh-CN) [Vite](https://cn.vitejs.dev)）版本[RuoYi-Cloud-Vue3](https://github.com/yangzongzhuan/RuoYi-Cloud-Vue3)，保持同步更新。
* 如需不分离应用，请移步 [RuoYi](https://gitee.com/y_project/RuoYi)，如需分离应用，请移步 [RuoYi-Vue](https://gitee.com/y_project/RuoYi-Vue)
* 阿里云折扣场：[点我进入](http://aly.ruoyi.vip)，腾讯云秒杀场：[点我进入](http://txy.ruoyi.vip)&nbsp;&nbsp;
* 阿里云优惠券：[点我领取](https://www.aliyun.com/minisite/goods?userCode=brki8iof&share_source=copy_link)，腾讯云优惠券：[点我领取](https://cloud.tencent.com/redirect.php?redirect=1025&cps_key=198c8df2ed259157187173bc7f4f32fd&from=console)&nbsp;&nbsp;

#### 友情链接 [若依/RuoYi-Cloud](https://gitee.com/zhangmrit/ruoyi-cloud) Ant Design版本。

## 系统模块

~~~
com.ruoyi
├── oms-modules           // mos系统模块
│       └── oms-common-core                           // 公共配置
│       └── oms-goods-administration                 // 商品管理                  
├── ruoyi-ui              // 前端框架 [80]
├── ruoyi-gateway         // 网关模块 [8080]
├── ruoyi-auth            // 认证中心 [9200]
├── ruoyi-api             // 接口模块
│       └── ruoyi-api-system                          // 系统接口
├── ruoyi-common          // 通用模块
│       └── ruoyi-common-core                         // 核心模块
│       └── ruoyi-common-datascope                    // 权限范围
│       └── ruoyi-common-datasource                   // 多数据源
│       └── ruoyi-common-log                          // 日志记录
│       └── ruoyi-common-redis                        // 缓存服务
│       └── ruoyi-common-seata                        // 分布式事务
│       └── ruoyi-common-security                     // 安全模块
│       └── ruoyi-common-swagger                      // 系统接口
├── ruoyi-modules         // 业务模块
│       └── ruoyi-system                              // 系统模块 [9201]
│       └── ruoyi-gen                                 // 代码生成 [9202]
│       └── ruoyi-job                                 // 定时任务 [9203]
│       └── ruoyi-file                                // 文件服务 [9300]
├── ruoyi-visual          // 图形化管理模块
│       └── ruoyi-visual-monitor                      // 监控中心 [9100]
├──pom.xml                // 公共依赖
~~~

## 架构图

<img src="https://oscimg.oschina.net/oscnet/up-82e9722ecb846786405a904bafcf19f73f3.png"/>

## 内置功能

1.  用户管理：用户是系统操作者，该功能主要完成系统用户配置。
2.  部门管理：配置系统组织机构（公司、部门、小组），树结构展现支持数据权限。
3.  岗位管理：配置系统用户所属担任职务。
4.  菜单管理：配置系统菜单，操作权限，按钮权限标识等。
5.  角色管理：角色菜单权限分配、设置角色按机构进行数据范围权限划分。
6.  字典管理：对系统中经常使用的一些较为固定的数据进行维护。
7.  参数管理：对系统动态配置常用参数。
8.  通知公告：系统通知公告信息发布维护。
9.  操作日志：系统正常操作日志记录和查询；系统异常信息日志记录和查询。
10. 登录日志：系统登录日志记录查询包含登录异常。
11. 在线用户：当前系统中活跃用户状态监控。
12. 定时任务：在线（添加、修改、删除)任务调度包含执行结果日志。
13. 代码生成：前后端代码的生成（java、html、xml、sql）支持CRUD下载 。
14. 系统接口：根据业务代码自动生成相关的api接口文档。
15. 服务监控：监视当前系统CPU、内存、磁盘、堆栈等相关信息。
16. 在线构建器：拖动表单元素生成相应的HTML代码。
17. 连接池监视：监视当前系统数据库连接池状态，可进行分析SQL找出系统性能瓶颈。

## 在线体验

- admin/admin123  
- 陆陆续续收到一些打赏，为了更好的体验已用于演示服务器升级。谢谢各位小伙伴。

演示地址：http://ruoyi.vip  
文档地址：http://doc.ruoyi.vip

## 演示图

<table>
    <tr>
        <td><img src="https://oscimg.oschina.net/oscnet/cd1f90be5f2684f4560c9519c0f2a232ee8.jpg"/></td>
        <td><img src="https://oscimg.oschina.net/oscnet/1cbcf0e6f257c7d3a063c0e3f2ff989e4b3.jpg"/></td>
    </tr>
    <tr>
        <td><img src="https://oscimg.oschina.net/oscnet/up-8074972883b5ba0622e13246738ebba237a.png"/></td>
        <td><img src="https://oscimg.oschina.net/oscnet/up-9f88719cdfca9af2e58b352a20e23d43b12.png"/></td>
    </tr>
    <tr>
        <td><img src="https://oscimg.oschina.net/oscnet/up-39bf2584ec3a529b0d5a3b70d15c9b37646.png"/></td>
        <td><img src="https://oscimg.oschina.net/oscnet/up-4148b24f58660a9dc347761e4cf6162f28f.png"/></td>
    </tr>
	<tr>
        <td><img src="https://oscimg.oschina.net/oscnet/up-b2d62ceb95d2dd9b3fbe157bb70d26001e9.png"/></td>
        <td><img src="https://oscimg.oschina.net/oscnet/up-d67451d308b7a79ad6819723396f7c3d77a.png"/></td>
    </tr>	 
    <tr>
        <td><img src="https://oscimg.oschina.net/oscnet/5e8c387724954459291aafd5eb52b456f53.jpg"/></td>
        <td><img src="https://oscimg.oschina.net/oscnet/644e78da53c2e92a95dfda4f76e6d117c4b.jpg"/></td>
    </tr>
	<tr>
        <td><img src="https://oscimg.oschina.net/oscnet/up-8370a0d02977eebf6dbf854c8450293c937.png"/></td>
        <td><img src="https://oscimg.oschina.net/oscnet/up-49003ed83f60f633e7153609a53a2b644f7.png"/></td>
    </tr>
	<tr>
        <td><img src="https://oscimg.oschina.net/oscnet/up-d4fe726319ece268d4746602c39cffc0621.png"/></td>
        <td><img src="https://oscimg.oschina.net/oscnet/up-c195234bbcd30be6927f037a6755e6ab69c.png"/></td>
    </tr>
	<tr>
        <td><img src="https://oscimg.oschina.net/oscnet/up-ece3fd37a3d4bb75a3926e905a3c5629055.png"/></td>
        <td><img src="https://oscimg.oschina.net/oscnet/up-92ffb7f3835855cff100fa0f754a6be0d99.png"/></td>
    </tr>
    <tr>
        <td><img src="https://oscimg.oschina.net/oscnet/up-ff9e3066561574aca73005c5730c6a41f15.png"/></td>
        <td><img src="https://oscimg.oschina.net/oscnet/up-5e4daac0bb59612c5038448acbcef235e3a.png"/></td>
    </tr>
</table>


## 若依微服务交流群

QQ群： [![加入QQ群](https://img.shields.io/badge/已满-42799195-blue.svg)](https://jq.qq.com/?_wv=1027&k=yqInfq0S) [![加入QQ群](https://img.shields.io/badge/已满-170157040-blue.svg)](https://jq.qq.com/?_wv=1027&k=Oy1mb3p8) [![加入QQ群](https://img.shields.io/badge/已满-130643120-blue.svg)](https://jq.qq.com/?_wv=1027&k=rvxkJtXK) [![加入QQ群](https://img.shields.io/badge/已满-225920371-blue.svg)](https://jq.qq.com/?_wv=1027&k=0Ck3PvTe) [![加入QQ群](https://img.shields.io/badge/已满-201705537-blue.svg)](https://jq.qq.com/?_wv=1027&k=FnHHP4TT) [![加入QQ群](https://img.shields.io/badge/已满-236543183-blue.svg)](https://jq.qq.com/?_wv=1027&k=qdT1Ojpz) [![加入QQ群](https://img.shields.io/badge/已满-213618602-blue.svg)](https://jq.qq.com/?_wv=1027&k=nw3OiyXs) [![加入QQ群](https://img.shields.io/badge/已满-148794840-blue.svg)](https://jq.qq.com/?_wv=1027&k=kiU5WDls) [![加入QQ群](https://img.shields.io/badge/已满-118752664-blue.svg)](https://jq.qq.com/?_wv=1027&k=MtBy6YfT) [![加入QQ群](https://img.shields.io/badge/已满-101038945-blue.svg)](https://jq.qq.com/?_wv=1027&k=FqImHgH2) [![加入QQ群](https://img.shields.io/badge/已满-128355254-blue.svg)](http://qm.qq.com/cgi-bin/qm/qr?_wv=1027&k=G4jZ4EtdT50PhnMBudTnEwgonxkXOscJ&authKey=FkGHYfoTKlGE6wHdKdjH9bVoOgQjtLP9WM%2Fj7pqGY1msoqw9uxDiBo39E2mLgzYg&noverify=0&group_code=128355254) [![加入QQ群](https://img.shields.io/badge/已满-179219821-blue.svg)](http://qm.qq.com/cgi-bin/qm/qr?_wv=1027&k=irnwcXhbLOQEv1g-TwGifjNTA_f4wZiA&authKey=4bpzEwhcUY%2FvsPDHvzYn6xfoS%2FtOArvZ%2BGXzfr7O0%2FEqLfkKA%2BuCDXlzHIFg8t93&noverify=0&group_code=179219821) [![加入QQ群](https://img.shields.io/badge/158753145-blue.svg)](http://qm.qq.com/cgi-bin/qm/qr?_wv=1027&k=lx1uEdEDuxeM7rUvF3qmlFdqKqdJ5Z-R&authKey=rgyPW9yhhh4IIURKVFa6NgP3qiqH04WAzrJ0trsgkr3pjzm6sKIOGyA58oOjoj%2FJ&noverify=0&group_code=158753145) 点击按钮入群。
