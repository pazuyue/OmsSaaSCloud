"""Add product inventory read indexes and menu, preserving all stock quantities."""
from local_db import HERE, execute
from seed_business import ensure_menu

DB = 'qm_oms_saas_inventory'

def main():
    assert list((HERE/'backups').glob('oms-saas-complete-*.sql')), 'Create a backup first'
    indexes = [
        ('oms_inventory', 'idx_oms_company_page', 'company_code,id'),
        ('rule_stock_result', 'idx_result_company_sku', 'company_code,sku_sn,id'),
        ('wms_inventory_change_history', 'idx_history_company_sku_page', 'company_code,sku_sn,log_id'),
        ('rule_stock_reservation_event', 'idx_event_journal', 'company_code,sku_sn,journal_request'),
    ]
    for table, name, columns in indexes:
        if execute(f"SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='{table}' AND index_name='{name}'", DB).strip() == '0':
            execute(f'ALTER TABLE {table} ADD INDEX {name} ({columns})', DB)
    parent = execute("SELECT menu_id FROM sys_menu WHERE path='oms-inventory' AND parent_id=0").strip()
    assert parent.isdigit(), 'Existing inventory menu is required'
    menu = ensure_menu('商品库存', int(parent), 'productInventory', 'oms/productInventory/index', permission='wmsInventory:inventory:list', order=10)
    # Reuse the warehouse inventory reader audience, without granting any new write permission.
    execute(f"INSERT INTO sys_role_menu(role_id,menu_id) SELECT DISTINCT rm.role_id,{menu} FROM sys_role_menu rm JOIN sys_menu m ON m.menu_id=rm.menu_id WHERE m.parent_id={parent} AND m.path='wmsInventory' AND NOT EXISTS (SELECT 1 FROM sys_role_menu existing WHERE existing.role_id=rm.role_id AND existing.menu_id={menu})")
    execute(f"UPDATE sys_menu SET menu_name='分货管理' WHERE parent_id={parent} AND path='ruleStock' AND menu_name='分货规则'")
    print('Product inventory query indexes and menu applied; stock quantities unchanged')

if __name__ == '__main__':
    main()
