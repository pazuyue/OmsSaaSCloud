"""Add bounded channel stock query indexes and a read-only menu, preserving quantities."""
from local_db import HERE, execute
from seed_business import ensure_menu

DB = 'qm_oms_saas_inventory'

def main():
    assert list((HERE / 'backups').glob('oms-saas-complete-*.sql')), 'Create a backup first'
    for table, name, columns in [
        ('oms_channel_inventory', 'idx_channel_company_page', 'company_code,id'),
        ('oms_channel_inventory', 'idx_channel_company_channel_page', 'company_code,channel_id,id'),
        ('oms_channel_inventory', 'idx_channel_company_sku_page', 'company_code,sku_sn,id'),
        ('rule_stock_reservation_event', 'idx_event_channel_page', 'company_code,sku_sn,channel_id,id'),
        ('rule_stock_daily_item', 'idx_daily_item_sku_run', 'sku_sn,run_id,id'),
    ]:
        if execute(f"SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='{table}' AND index_name='{name}'", DB).strip() == '0':
            execute(f'ALTER TABLE {table} ADD INDEX {name} ({columns})', DB)
    parent = execute("SELECT menu_id FROM sys_menu WHERE path='oms-inventory' AND parent_id=0").strip()
    assert parent.isdigit(), 'Inventory menu required'
    menu = ensure_menu('渠道库存', int(parent), 'channelInventory', 'oms/channelInventory/index', permission='channelInventory:inventory:list', order=11)
    execute(f"INSERT INTO sys_role_menu(role_id,menu_id) SELECT DISTINCT rm.role_id,{menu} FROM sys_role_menu rm JOIN sys_menu m ON m.menu_id=rm.menu_id WHERE m.parent_id={parent} AND m.path='wmsInventory' AND NOT EXISTS (SELECT 1 FROM sys_role_menu existing WHERE existing.role_id=rm.role_id AND existing.menu_id={menu})")
    print('Channel inventory query indexes and reader menu applied; stock quantities unchanged')

if __name__ == '__main__':
    main()
