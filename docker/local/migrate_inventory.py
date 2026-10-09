"""Apply the inventory migration once, with length/collision preflight and an external backup."""
from pathlib import Path
from local_db import execute
DB='qm_oms_saas_inventory'
ROOT=Path(__file__).resolve().parent

def main():
    assert list((ROOT/'backups').glob('oms-saas-complete-*.sql')),'Create a backup first'
    if execute("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='rule_stock_info' AND column_name='allocation_type'",DB).strip()=='0':
        execute((ROOT/'migrations/20261008_rule_allocation.sql').read_text(),DB)
        print('Rule allocation type column added')
    execute("INSERT INTO sys_dict_type(dict_name,dict_type,status,create_by,create_time,remark) SELECT '分货类型','oms_warehouse_allocation_type','0','local-deploy',NOW(),'对应当前库存分货代码枚举' WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type WHERE dict_type='oms_warehouse_allocation_type')")
    for value,label in [(1,'普通分货'),(2,'锁库分货')]:
        execute(f"INSERT INTO sys_dict_data(dict_sort,dict_label,dict_value,dict_type,list_class,is_default,status,create_by,create_time) SELECT {value},'{label}','{value}','oms_warehouse_allocation_type','default','N','0','local-deploy',NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='oms_warehouse_allocation_type' AND dict_value='{value}')")
    if execute("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='wms_inventory' AND index_name='uk_inventory_company_sku_store'",DB).strip()!='0':
        print('Inventory migration already applied');return
    for table in ['wms_inventory','wms_inventory_batch']:
        checks="company_code IS NULL OR LENGTH(TRIM(company_code))=0 OR CHAR_LENGTH(company_code)>50 OR CHAR_LENGTH(sku_sn)>128 OR CHAR_LENGTH(store_code)>64"
        if table.endswith('_batch'):checks+=" OR batch_code IS NULL OR CHAR_LENGTH(batch_code)>128"
        assert execute(f'SELECT COUNT(*) FROM {table} WHERE {checks}',DB).strip()=='0',f'{table}: existing data exceeds migration limits'
    backups=list((ROOT/'backups').glob('oms-saas-complete-*.sql'))
    assert backups,'Create a backup first'
    print('Using pre-migration backup:',max(backups).name)
    # Only stock tables use canonical uppercase tenant codes; warehouse references retain their original values.
    execute((ROOT/'migrations/20261008_inventory.sql').read_text(),DB)
    for table in ['wms_inventory','wms_inventory_batch','oms_inventory']:
        execute(f'UPDATE {table} SET company_code=UPPER(TRIM(company_code)) WHERE BINARY company_code<>BINARY UPPER(TRIM(company_code))',DB)
    print('Inventory migration applied; stock quantities and warehouse codes unchanged')

if __name__=='__main__': main()
