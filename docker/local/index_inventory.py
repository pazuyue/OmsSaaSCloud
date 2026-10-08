"""Idempotent supporting indexes for bounded inventory queries."""
from local_db import execute
INDEXES=[
 ('qm_oms_saas_inventory','wms_inventory','idx_inventory_company_page','company_code,id'),
 ('qm_oms_saas_inventory','wms_inventory_batch','idx_batch_company_page','company_code,sku_sn,store_code,id'),
 ('qm_oms_saas_commodity','goods_sku_sn_info','idx_inventory_goods_sku','company_code,sku_sn'),
 ('qm_oms_saas_commodity','goods_sku_sn_info','idx_inventory_goods_name','company_code,goods_name(100)'),
 ('qm_oms_saas_commodity','goods_sku_sn_info','idx_inventory_goods_barcode','company_code,barcode_sn'),
 ('qm_oms_saas_commodity','wms_simulation_store_info','idx_inventory_store_code','company_code,wms_simulation_code'),
 ('qm_oms_saas_commodity','wms_simulation_store_info','idx_inventory_store_name','company_code,wms_simulation_name(100)')]
for database,table,name,columns in INDEXES:
    exists=execute(f"SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='{table}' AND index_name='{name}'",database).strip()
    if exists=='0':execute(f'ALTER TABLE {table} ADD INDEX {name} ({columns})',database)
    print('Index ready:',table,name)
