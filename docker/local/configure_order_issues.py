"""Add problem metadata only; no reservation, inventory or historical-state rewrites."""
from configure_order import column
from local_db import execute, HERE


def install_inventory(database):
    column(database, 'oms_order_stock_demand', 'decision_snapshot', "MEDIUMTEXT NULL COMMENT '最近预占尝试的数量、余额、额度快照'")


def install_order(database):
    execute((HERE / 'migrations/20261013_order_issues.sql').read_text(encoding='utf8'), database)
    column(database, 'oms_order', 'issue_synced_at', "DATETIME NULL COMMENT '最近完整库存问题核对时间'")
    column(database, 'oms_order', 'locked_reserved_quantity', "BIGINT NOT NULL DEFAULT 0 COMMENT '累计渠道锁库预占数量的只读投影'")
    column(database, 'oms_order', 'public_reserved_quantity', "BIGINT NOT NULL DEFAULT 0 COMMENT '累计公共补占数量的只读投影'")
    column(database, 'oms_order', 'issue_sync_error', "VARCHAR(500) NOT NULL DEFAULT '' COMMENT '问题核对失败时保留原问题，不据失败清空'")


if __name__ == '__main__':
    install_inventory('qm_oms_saas_inventory')
    install_order('qm_oms_saas_order')
    print('Order issue metadata installed; stock quantities unchanged')
