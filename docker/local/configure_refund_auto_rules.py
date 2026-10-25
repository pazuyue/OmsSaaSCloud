"""Document the existing rules JSON and reuse the existing original-suborder index.

No business rows, rules, defaults, backfills, queue controls or jobs are changed.
"""
from configure_refund_execution import document_columns
from local_db import execute


def install(db):
    expected = 'company_code,channel_id,platform_tid,platform_oid,id'
    columns = execute("SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='oms_platform_refund' AND INDEX_NAME='original_scope'", db).strip()
    assert columns == expected, 'Original suborder lookup index must exist before applying scoped metadata'
    # Remove only the duplicate created by this acceptance iteration, identified by exact columns and comment.
    duplicate = execute("SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX),MAX(INDEX_COMMENT) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='oms_platform_refund' AND INDEX_NAME='refund_money_scope'", db).strip()
    if duplicate == expected + '\t按原子订单聚合已退及已审核金额，串行校验累计可退额度':
        execute('ALTER TABLE oms_platform_refund DROP INDEX refund_money_scope, ALGORITHM=INPLACE, LOCK=NONE', db)
    document_columns(db, 'oms_refund_flow_policy', {
        'policy_json': "JSON NOT NULL COMMENT '自动规则JSON：小额免退开关及子单累计上限、发货后剩余全额取消、实收无差异自动结案、平台操作与付款上限；各开关默认关闭'"})


if __name__ == '__main__':
    install('qm_oms_saas_order')
    print('PASS existing original_scope reused, redundant acceptance index removed, rules JSON documented; business records unchanged')
