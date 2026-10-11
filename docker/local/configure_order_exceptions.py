"""Install current conversion exception metadata; no data rewriting or compatibility rules."""
from configure_order import column
from local_db import execute

def install(database):
    from configure_business_orders import install_order
    install_order(database)
    for name, definition in {
        'manual_sku_sn': "VARCHAR(128) NULL COMMENT '仅本订单明细生效的人工匹配OMS SKU'",
        'manual_match_by': "VARCHAR(64) NULL COMMENT '人工匹配操作人'",
        'manual_match_at': "DATETIME NULL COMMENT '人工匹配时间'",
        'manual_match_reason': "VARCHAR(200) NULL COMMENT '人工匹配原因'",
        'demand_prepared': "TINYINT NOT NULL DEFAULT 0 COMMENT '该明细已开始登记需求，禁止直接换SKU'",
        'platform_spec': "VARCHAR(1000) NOT NULL DEFAULT '' COMMENT '平台原始商品规格'",
    }.items():
        column(database, 'oms_platform_order_line', name, definition)
    for name, definition in {
        'exception_code': "VARCHAR(40) NOT NULL DEFAULT '' COMMENT '稳定转单异常代码'",
        'exception_details': "MEDIUMTEXT NULL COMMENT '结构化完整异常明细JSON'",
        'exception_line_count': "INT NOT NULL DEFAULT 0 COMMENT '当前异常商品行数，列表不读取完整异常JSON'",
        'first_exception_at': "DATETIME NULL COMMENT '首次转单异常时间'",
        'last_exception_at': "DATETIME NULL COMMENT '最近转单异常时间'",
        'retry_count': "INT NOT NULL DEFAULT 0 COMMENT '人工重试提交次数'",
        'system_failures': "INT NOT NULL DEFAULT 0 COMMENT '本轮连续系统失败次数，最多自动重试5次'",
    }.items():
        column(database, 'oms_platform_order', name, definition)
    found=execute("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='oms_platform_order' AND index_name='idx_exception'",database).strip()
    if found=='0':
        execute('ALTER TABLE oms_platform_order ADD INDEX idx_exception(company_code,conversion_status,first_exception_at)',database)

if __name__=='__main__':
    install('qm_oms_saas_order')
    print('Order exception metadata installed')
