"""Install time/TID backfill tracking without submitting any pull tasks."""
from local_db import HERE, execute
from configure_order import column

def install(database):
    for name,definition in [
        ('pull_mode',"VARCHAR(12) NOT NULL DEFAULT 'TIME'"),
        ('requested_tids','TEXT NULL'),
        ('request_key','VARCHAR(64) NULL'),
        ('request_hash',"VARCHAR(64) NOT NULL DEFAULT ''"),
        ('pull_source_key',"VARCHAR(64) NOT NULL DEFAULT ''"),
        ('pull_seller_nick',"VARCHAR(200) NOT NULL DEFAULT ''")]:
        column(database,'oms_order_pull_task',name,definition)
    if execute("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='oms_order_pull_task' AND index_name='backfill_request'",database).strip()=='0':
        execute('ALTER TABLE oms_order_pull_task ADD UNIQUE KEY backfill_request(company_code,channel_id,request_key)',database)
    execute((HERE/'migrations/20261013_order_backfill.sql').read_text(encoding='utf8'),database)

if __name__=='__main__':
    install('qm_oms_saas_order')
    print('Backfill tracking installed; no pull requests or inventory operations executed.')
