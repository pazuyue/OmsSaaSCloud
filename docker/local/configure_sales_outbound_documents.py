"""Add document projections only; never execute shipments or change stock."""
from local_db import HERE, execute
from configure_order import column

def install_supplychain(database):
    column(database,'wms_tickets','business_type',"VARCHAR(32) NOT NULL DEFAULT ''")
    column(database,'wms_tickets_goods','source_line_id','BIGINT NULL')
    execute((HERE/'migrations/20261012_sales_outbound_documents.sql').read_text(encoding='utf-8'),database)
    if execute("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='wms_tickets_goods' AND index_name='sales_source_line'",database).strip()=='0':
        execute('ALTER TABLE wms_tickets_goods ADD INDEX sales_source_line(sn,source_line_id,company_code)',database)

def install_order(database):
    execute((HERE/'migrations/20261012_order_sales_document_sync.sql').read_text(encoding='utf-8'),database)

if __name__=='__main__':
    install_supplychain('qm_oms_saas_commodity')
    install_order('qm_oms_saas_order')
    print('Sales document and movement schemas installed; no stock changes.')
