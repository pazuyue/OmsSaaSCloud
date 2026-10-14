"""Install review metadata and explicit shared-waybill slots; no data backfill or receipt execution."""
from local_db import execute, HERE
from configure_order import column


def slot(database, table, index, fields):
    column(database, table, 'waybill_slot', "VARCHAR(64) COLLATE utf8mb4_bin NOT NULL DEFAULT ''")
    columns = execute(f"SELECT column_name FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='{table}' AND index_name='{index}' ORDER BY seq_in_index", database).splitlines()
    desired = fields + ['waybill_slot']
    if columns != desired:
        if columns not in ([], fields):
            raise RuntimeError('Unexpected waybill index: ' + index)
        drop = f'DROP INDEX {index}, ' if columns else ''
        execute(f'ALTER TABLE {table} {drop}ADD UNIQUE KEY {index}(' + ','.join(desired) + ')', database)


def install_order(database):
    column(database, 'oms_order_shipment_package', 'shared_review_id', 'BIGINT NULL')
    slot(database, 'oms_order_shipment_package', 'package_waybill', ['company_code', 'carrier_code', 'express_code'])
    execute("CREATE TABLE IF NOT EXISTS oms_order_waybill_lock (company_code VARCHAR(50) COLLATE utf8mb4_bin NOT NULL,carrier_code VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,express_code VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,PRIMARY KEY(company_code,carrier_code,express_code)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4", database)


def install_supplychain(database):
    execute((HERE/'migrations/20261014_receipt_review.sql').read_text(encoding='utf8'), database)
    slot(database, 'wms_outbound_receipt_package', 'receipt_waybill', ['company_code', 'logistics_code', 'express_code'])


def permissions():
    from seed_business import ensure_menu
    parent = execute("SELECT menu_id FROM sys_menu WHERE component='oms/order/shipments' ORDER BY menu_id LIMIT 1").strip()
    assert parent.isdigit()
    menu = ensure_menu('人工核对仓库出库回传', int(parent), '', kind='F', permission='order:shipment:receiptReview', order=47)
    execute(f'INSERT INTO sys_role_menu(role_id,menu_id) SELECT 1,{menu} WHERE NOT EXISTS(SELECT 1 FROM sys_role_menu WHERE role_id=1 AND menu_id={menu})')


if __name__ == '__main__':
    install_order('qm_oms_saas_order')
    install_supplychain('qm_oms_saas_commodity')
    permissions()
    print('Receipt review installed; no existing receipts confirmed and no stock posted')
