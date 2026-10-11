"""Install the shop-level trade type setting; runtime always reads the stored setting."""
from configure_order import column

def install(database):
    column(database,'oms_order_pull_config','allowed_trade_types',
           "VARCHAR(2048) NOT NULL DEFAULT '[\"fixed\",\"guarantee_trade\",\"tmall_i18n\"]' COMMENT '店铺允许转单的交易类型JSON数组'")

if __name__=='__main__':
    install('qm_oms_saas_order')
    print('Shop-level trade type setting installed')
