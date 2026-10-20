"""OMS gift rules and after-sales ledger. Schema/navigation only; no document backfill."""
from local_db import execute
from configure_order import column


def install_order(db):
    execute((__import__('pathlib').Path(__file__).parent/'migrations/20261019_gifts.sql').read_text(encoding='utf-8'), db)
    for name, definition in [('gift_plan_id', 'BIGINT NULL'), ('gift_block_reason', "VARCHAR(500) NOT NULL DEFAULT ''")]:
        column(db, 'oms_order', name, definition)
    column(db, 'oms_gift_plan', 'dirty', 'TINYINT NOT NULL DEFAULT 1')
    if not execute("SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='oms_gift_plan' AND index_name='dirty_due' LIMIT 1",db).strip():
        execute('ALTER TABLE oms_gift_plan ADD KEY dirty_due(company_code,dirty,next_check,id)',db)
    column(db, 'oms_order_line', 'gift_plan_id', 'BIGINT NULL')
    column(db, 'oms_refund_return_plan', 'gift_plan_id', 'BIGINT NULL')


def install_inventory(db):
    column(db, 'oms_order_stock_demand', 'line_kind', "VARCHAR(12) NOT NULL DEFAULT 'SALE'")


def menus():
    from seed_business import ensure_menu
    parent = int(execute("SELECT menu_id FROM sys_menu WHERE parent_id=0 AND path='oms-order' LIMIT 1").strip())
    page = ensure_menu('赠品管理', parent, 'gifts', 'oms/order/GiftManagement', permission='order:gift:list', order=17)
    for title, perm in [('查询赠品', 'query'), ('配置赠品规则', 'config'), ('处理赠品履约', 'execute'), ('豁免赠品追退', 'waive')]:
        node = ensure_menu(title, page, '', kind='F', permission='order:gift:'+perm, order=20)
        execute(f'INSERT IGNORE INTO sys_role_menu(role_id,menu_id) VALUES (1,{node})')
    execute(f"UPDATE sys_menu SET icon='gift',visible='0',status='0' WHERE menu_id={page}")
    execute(f'INSERT IGNORE INTO sys_role_menu(role_id,menu_id) VALUES (1,{page})')
    execute("UPDATE sys_job SET job_name='天猫退款同步、转单、合包、货物与赠品售后扫描',remark='保留退款与仓库事实；核对赠品资格，失效未发赠品先截单后取消，已发赠品待追退；不自动扣款' WHERE invoke_target='refundTask.scanAll()'")


if __name__ == '__main__':
    install_order('qm_oms_saas_order')
    install_inventory('qm_oms_saas_inventory')
    menus()
    print('Gift rules, entitlement ledger and after-sales schema installed; no historical documents changed')
