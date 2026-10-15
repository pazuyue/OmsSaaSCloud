"""Install independent review rules; never re-review or backfill business documents."""
from local_db import HERE,execute
from configure_order import column

def install(database):
    execute((HERE/'migrations/20261015_order_review.sql').read_text(encoding='utf-8'),database)
    for name,definition in {
        'review_decision': "VARCHAR(24) NOT NULL DEFAULT 'UNASSESSED' COMMENT 'AUTO_APPROVED/HUMAN_APPROVED/MANUAL_REQUIRED/WAIT_STOCK/BLOCKED/UNASSESSED'",
        'review_snapshot': "JSON NULL COMMENT '最近自动审核规则与脱敏依据快照，不包含收货个人信息'",
        'review_evaluated_at': "DATETIME NULL COMMENT '最近自动判定时间'",
    }.items():column(database,'oms_order',name,definition)
    if execute("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='oms_order' AND index_name='idx_review_wait'",database).strip()=='0':
        execute('ALTER TABLE oms_order ADD INDEX idx_review_wait(company_code,review_status,review_decision,id)',database)

if __name__=='__main__':
    install('qm_oms_saas_order')
    print('Independent review schema installed; no document backfill or automatic re-review')
