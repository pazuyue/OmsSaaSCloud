"""Indexes for scoped batch history and paged order attribution; no new menus or stock changes."""
from local_db import HERE, execute
DB = 'qm_oms_saas_inventory'

def main():
    assert list((HERE/'backups').glob('oms-saas-complete-*.sql')), 'Create a backup first'
    for table, name, columns in [
        ('wms_inventory_change_history', 'idx_history_batch_page', 'company_code,batch_id,log_id'),
        ('rule_stock_order_reservation', 'idx_order_source_page', 'company_code,source_id,id'),
    ]:
        if execute(f"SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='{table}' AND index_name='{name}'", DB).strip() == '0':
            execute(f'ALTER TABLE {table} ADD INDEX {name} ({columns})', DB)
    print('Batch trace query indexes applied; stock quantities unchanged')

if __name__ == '__main__':
    main()
