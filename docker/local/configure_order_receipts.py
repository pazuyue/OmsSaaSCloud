"""Shipment outbound receipts and packages; no automatic data repair or dispatch."""
from local_db import HERE, execute


def install_order(database):
    from configure_order_backfill import install as install_backfill
    install_backfill(database)
    from configure_sales_outbound_documents import install_order as sales_documents
    sales_documents(database)
    execute((HERE/'migrations/20261012_order_outbound_receipts.sql').read_text(encoding='utf-8'),database)
    from configure_receipt_review import install_order as review_order
    review_order(database)
    execute("INSERT INTO oms_order_shipment_outbound(company_code,shipment_id,shipment_sn,request_id,source_mode,quantity,payload_json,payload_hash,state,created_by,outbound_time,completed_at,create_time) SELECT company_code,shipment_id,shipment_sn,request_id,'VIRTUAL',quantity,payload_json,payload_hash,'COMPLETED',created_by,completed_at,completed_at,create_time FROM oms_order_shipment_virtual_outbound WHERE state='COMPLETED' ON DUPLICATE KEY UPDATE id=oms_order_shipment_outbound.id",database)


def install_supplychain(database):
    from configure_sales_outbound_documents import install_supplychain as sales_documents
    sales_documents(database)
    execute((HERE/'migrations/20261012_wms_outbound_receipts.sql').read_text(encoding='utf-8'),database)
    from configure_receipt_review import install_supplychain as review_supplychain
    review_supplychain(database)


def install_inventory(database):
    from configure_order import column
    column(database,'oms_order_virtual_outbound','source_mode',"VARCHAR(12) NOT NULL DEFAULT 'VIRTUAL'")
    column(database,'oms_order_warehouse_allocation','outbound_method',"VARCHAR(12) NOT NULL DEFAULT 'VIRTUAL'")


def install_permissions():
    from seed_business import ensure_menu
    parent=execute("SELECT menu_id FROM sys_menu WHERE path='shipments' AND component='oms/order/shipments' ORDER BY menu_id LIMIT 1").strip()
    if not parent.isdigit():raise RuntimeError('Shipment menu missing')
    for index,(title,permission) in enumerate([('登记发货单运单','order:shipment:package'),('核对发货单出库回传','order:shipment:receipt')],5):
        menu=ensure_menu(title,int(parent),'',kind='F',permission=permission,order=index)
        execute(f'INSERT INTO sys_role_menu(role_id,menu_id) SELECT 1,{menu} WHERE NOT EXISTS(SELECT 1 FROM sys_role_menu WHERE role_id=1 AND menu_id={menu})')


if __name__=='__main__':
    install_order('qm_oms_saas_order')
    install_supplychain('qm_oms_saas_commodity')
    install_inventory('qm_oms_saas_inventory')
    install_permissions()
    print('Outbound receipt and package schemas installed; no shipment submission or stock posting.')
