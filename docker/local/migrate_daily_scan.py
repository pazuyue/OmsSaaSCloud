"""Unify Quartz scanning; no company registry, new tables or stock mutations."""
from local_db import HERE, execute

def ensure_unified_job():
    existing=execute("SELECT COUNT(*) FROM sys_job WHERE invoke_target='dailyAllocationTask.scanAll()'",'ry-cloud').strip()
    if existing=='0':
        execute("""INSERT INTO sys_job(job_name,job_group,invoke_target,cron_expression,misfire_policy,concurrent,status,create_by,create_time,remark)
VALUES ('日常分货规则扫描','DEFAULT','dailyAllocationTask.scanAll()','0/10 * * * * ?','3','1','0','admin',NOW(),'自动发现已加载库存数据源中的日常分货公司，分批轮转，失败隔离')""", 'ry-cloud')
        return
    # Re-running initialization preserves the configured task schedule and enabled state.

def main():
    assert list((HERE/'backups').glob('oms-saas-complete-*.sql')), 'Create a backup first'
    if execute("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='rule_stock_info' AND index_name='idx_daily_company_scan'", 'qm_oms_saas_inventory').strip() == '0':
        execute('ALTER TABLE rule_stock_info ADD INDEX idx_daily_company_scan(rule_type,daily_enabled,company_code)', 'qm_oms_saas_inventory')
    ensure_unified_job()
    print('Unified Quartz scan and company discovery index ready; reload job service after deployment')

if __name__ == '__main__':
    main()
