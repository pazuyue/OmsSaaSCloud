"""Structured commercial snapshots for new documents; no historical backfill."""
from configure_order import column


def install(database):
    for table in ['oms_platform_order', 'oms_order']:
        column(database, table, 'document_info', "JSON NULL COMMENT '订单金额、销售子单及交易资料快照'")


if __name__ == '__main__':
    install('qm_oms_saas_order')
    print('Order document snapshot schema installed; no historical documents rewritten')
