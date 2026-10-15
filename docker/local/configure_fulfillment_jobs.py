"""Install central scan jobs paused; activation is explicit after service rollout.
Existing job status, cron and operator edits are preserved on rerun.
"""
from local_db import execute, quote

JOBS = [
 ('订单自动分仓补偿扫描','allocation','0/10 * * * * ?','处理到期自动分仓待办及补货唤醒；不自动下发仓库'),
 ('发货单仓库下发扫描','dispatch','0/5 * * * * ?','扫描到期下发任务；受理成功不代表实际出库'),
 ('仓库出库回传记账补偿扫描','receipts','1/5 * * * * ?','正常回传即时记账；本任务补偿未完成事件，待人工核对不自动审批'),
 ('发货单虚拟出库扫描','virtualOutbound','2/5 * * * * ?','按原出库请求处理到期任务，结果不明先核对原回执'),
 ('销售出库单同步补偿扫描','salesDocuments','3/5 * * * * ?','业务变化后即时同步；本任务只补漏及重试未完成同步，不额外扣库存'),
 ('平台发货回传扫描','platformShipping','0/15 * * * * ?','按店铺配置处理包裹回传；当前仅支持模拟回传'),
 ('订单库存问题核对扫描','orderIssues','0/30 * * * * ?','核对当前库存问题与标签，不自动确认仓库回传'),
 ('采购入库仓库交互扫描','purchaseInbound','4/5 * * * * ?','处理采购入库下发和已接收的收货记账任务')
]

def install(database='ry-cloud'):
    exists=execute("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='sys_job_log' AND column_name='execution_data'",database).strip()
    if exists=='0':execute("ALTER TABLE sys_job_log ADD COLUMN execution_data MEDIUMTEXT NULL COMMENT '业务扫描统计及脱敏单据引用'",database)
    for title,method,cron,remark in JOBS:
        target='fulfillmentScanTask.'+method+'()'
        execute("INSERT INTO sys_job(job_name,job_group,invoke_target,cron_expression,misfire_policy,concurrent,status,create_by,create_time,remark) SELECT "+','.join(map(quote,[title,'DEFAULT',target,cron,'3','1','1','admin']))+",NOW(),"+quote(remark)+" WHERE NOT EXISTS(SELECT 1 FROM sys_job WHERE invoke_target="+quote(target)+")",database)
    print('Central jobs registered paused when new; existing switches and schedules preserved')

if __name__ == '__main__':install()
