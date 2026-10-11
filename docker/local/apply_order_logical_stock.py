"""Local phase-one rollout. Requires an empty order demand table; no data conversion."""
from local_db import execute, HERE
database='qm_oms_saas_inventory'
assert int(execute('SELECT COUNT(*) FROM oms_order_stock_demand',database).strip())==0, 'Existing demand must be reviewed before rollout'
assert list((HERE/'backups').glob('oms-saas-complete-*.sql')), 'Backup required'
execute((HERE/'migrations/20261011_order_logical_stock.sql').read_text(encoding='utf-8'),database)
print('Applied warehouse-independent demand schema; existing business data unchanged')
