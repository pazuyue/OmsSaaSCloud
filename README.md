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

本次完整备份为 `docker/local/backups/oms-saas-complete-20261008-110641.sql`，包含 5 个数据库、71 张表，
已通过隔离恢复验证。同目录配套 `.json` 记录表数量与校验结果、`.sql.sha256` 记录文件校验和、`.env` 保存对应部署配置。
请一起保留这些文件；备份包含业务数据和连接凭据，已排除在 Git 提交之外。
导入前的原数据库备份保留在 `docker/local/backups/20261008-105158/before-business-import.sql`。

恢复到当前本地环境时，在 WSL 的项目根目录执行：

```bash
python3 docker/local/restore_database.py \
  docker/local/backups/oms-saas-complete-20261008-110641.sql --replace-local-data
python3 docker/local/verify_business.py
```

恢复脚本先校验文件及数据库密码，停止应用服务，再备份恢复前的数据，然后导入 SQL、清除本地登录缓存并启动服务。
该操作会替换备份内的同名表；恢复后需重新登录。脚本保留备份中的 Nacos 配置。
如果是在全新的数据卷上重建环境，请在首次启动 MySQL **之前**将配套备份 `.env` 复制为 `docker/local/.env`，
完成镜像及项目构建、运行 `start.sh` 后再执行恢复命令；现有数据卷的 MySQL 密码不会因修改 `.env` 自动变更。
SQL 备份不包含上传文件、Docker 镜像或构建产物。

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
python3 docker/local/index_inventory.py
bash docker/local/build.sh -pl oms-modules/oms-inventory,oms-modules/oms-goods-administration,oms-modules/oms-supplychain -am
bash docker/local/build-frontend.sh
docker compose -f docker/local/compose.yaml up -d --force-recreate inventory goods supplychain
python3 docker/local/verify_inventory_workspace.py
python3 docker/local/test_inventory.py
```

迁移补充公司维度唯一键、分页索引与现有库存流水模型对应的表。编码上限为公司 50、SKU 128、仓库 64、批次 128 字符，迁移前检查已有数据长度。
列表固定为计数、当前页查询、当前页批次汇总三个 SQL；名称按页批量查询，页面和明细每页最多 100 条。
写入按公司/SKU 汇总行、排序后的仓库、批次加锁；分货失败向外抛出并回滚，禁止吞掉错误后报成功。
不在持锁事务中调用远程服务，不在事务内部自动重试。调整使用幂等请求编号，预占释放按原单据及批次回溯。
`test_inventory.py` 仅操作独立的 `inventory_workspace_test` 数据库，覆盖并发、真实死锁回滚、幂等、差异核对及大数据分页。

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
