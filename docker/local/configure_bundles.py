"""Fixed virtual kits. Schema only; no historical document conversion."""
from local_db import execute
from configure_order import column

def index(db,table,name,fields):
    current=execute("SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='"+table+"' AND index_name='"+name+"'",db).strip()
    if current!=fields:
        execute('ALTER TABLE '+table+(' DROP INDEX '+name+',' if current and current!='NULL' else '')+' ADD UNIQUE KEY '+name+'('+fields+')',db)
def install_goods(db):
    from configure_lucky_bags import install_goods as install_lucky_goods
    install_lucky_goods(db)
    execute("""CREATE TABLE IF NOT EXISTS goods_bundle (
      company_code VARCHAR(50) NOT NULL, sku_sn VARCHAR(128) NOT NULL, revision BIGINT NOT NULL DEFAULT 1,
      enabled TINYINT NOT NULL DEFAULT 1, components JSON NOT NULL, updated_by VARCHAR(64) NOT NULL,
      update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
      PRIMARY KEY(company_code,sku_sn)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
    CREATE TABLE IF NOT EXISTS goods_bundle_component (
      company_code VARCHAR(50) NOT NULL, bundle_sku VARCHAR(128) NOT NULL, component_sku VARCHAR(128) NOT NULL,
      PRIMARY KEY(company_code,bundle_sku,component_sku), KEY component_reference(company_code,component_sku)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
    CREATE TABLE IF NOT EXISTS goods_bundle_version (
      company_code VARCHAR(50) NOT NULL, sku_sn VARCHAR(128) NOT NULL, revision BIGINT NOT NULL,
      enabled TINYINT NOT NULL, components JSON NOT NULL, actor VARCHAR(64) NOT NULL,
      create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY(company_code,sku_sn,revision)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""",db)
    column(db,'goods_bundle','inventory_mode',"VARCHAR(16) NOT NULL DEFAULT 'VIRTUAL'")
    column(db,'goods_bundle_version','inventory_mode',"VARCHAR(16) NOT NULL DEFAULT 'VIRTUAL'")

def install_order(db):
    column(db,'oms_platform_order_line','bundle_snapshot','JSON NULL')
    column(db,'oms_order_line','stocked_bundle_snapshot','JSON NULL')
    column(db,'oms_order_line','bundle_id','BIGINT NULL')
    column(db,'oms_order_line','component_quantity','INT NOT NULL DEFAULT 0')
    index(db,'oms_order_line','uk_order_line','order_id,platform_oid,stock_reference,sku_sn')
    execute("""CREATE TABLE IF NOT EXISTS oms_order_bundle (
      id BIGINT AUTO_INCREMENT PRIMARY KEY, company_code VARCHAR(50) NOT NULL, order_id BIGINT NOT NULL,
      platform_oid VARCHAR(64) NOT NULL, sku_sn VARCHAR(128) NOT NULL, goods_name VARCHAR(500) NOT NULL,
      quantity BIGINT NOT NULL, payment DECIMAL(18,2) NOT NULL, revision BIGINT NOT NULL, snapshot JSON NOT NULL,
      create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, UNIQUE KEY sales_line(company_code,order_id,platform_oid)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
    CREATE TABLE IF NOT EXISTS oms_refund_bundle_claim (
      company_code VARCHAR(50) NOT NULL, refund_id BIGINT NOT NULL, bundle_id BIGINT NOT NULL,
      shipment_line_id BIGINT NOT NULL, quantity BIGINT NOT NULL,
      PRIMARY KEY(company_code,refund_id,shipment_line_id), KEY source_claim(company_code,shipment_line_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
    CREATE TABLE IF NOT EXISTS oms_refund_bundle_return (
      id BIGINT AUTO_INCREMENT PRIMARY KEY, company_code VARCHAR(50) NOT NULL, refund_id BIGINT NOT NULL,
      bundle_id BIGINT NOT NULL, quantity BIGINT NOT NULL, request_json JSON NOT NULL, actor VARCHAR(64) NOT NULL,
      create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, UNIQUE KEY refund_bundle(company_code,refund_id,bundle_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""",db)
    column(db,'oms_order_bundle','stock_reference',"VARCHAR(64) NOT NULL DEFAULT ''")
    column(db,'oms_order_bundle','gift_plan_id','BIGINT NULL')
    index(db,'oms_order_bundle','sales_line','company_code,order_id,platform_oid,stock_reference')
    from configure_lucky_bags import install_order as install_lucky_order
    install_lucky_order(db)
def install_inventory(db):
    from configure_lucky_bags import install_inventory as install_lucky_inventory
    install_lucky_inventory(db)
    column(db,'oms_order_stock_demand','component_quantity','INT NOT NULL DEFAULT 0')
    index(db,'oms_order_stock_demand','uk_order_demand','company_code,channel_id,platform_tid,platform_oid,sku_sn')
if __name__=='__main__':
    install_goods('qm_oms_saas_commodity');install_order('qm_oms_saas_order');install_inventory('qm_oms_saas_inventory')
    print('Installed fixed virtual kit schema without business backfill')
