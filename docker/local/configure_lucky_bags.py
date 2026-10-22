"""Lucky bag phase 1/2 schema only. No document backfill or automatic rule activation."""
from local_db import execute
from configure_order import column


def install_goods(db):
    execute("""CREATE TABLE IF NOT EXISTS goods_lucky_bag (
      company_code VARCHAR(50) NOT NULL COMMENT '公司', sku_sn VARCHAR(50) NOT NULL COMMENT '福袋SKU',
      revision BIGINT NOT NULL DEFAULT 0 COMMENT '已发布版本', draft_revision BIGINT NOT NULL DEFAULT 0 COMMENT '草稿并发版本',
      draft_json JSON NOT NULL COMMENT '草稿规则', published_json JSON NULL COMMENT '已发布规则',
      updated_by VARCHAR(64) NOT NULL COMMENT '操作者', update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
      PRIMARY KEY(company_code,sku_sn)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='福袋规则及草稿';
    CREATE TABLE IF NOT EXISTS goods_lucky_bag_version (
      company_code VARCHAR(50) NOT NULL COMMENT '公司', sku_sn VARCHAR(50) NOT NULL COMMENT '福袋SKU', revision BIGINT NOT NULL COMMENT '发布版本',
      definition_json JSON NOT NULL COMMENT '不可变规则快照', actor VARCHAR(64) NOT NULL COMMENT '发布人',
      create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发布时间', PRIMARY KEY(company_code,sku_sn,revision)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='福袋发布版本';
    CREATE TABLE IF NOT EXISTS goods_lucky_bag_component (
      company_code VARCHAR(50) NOT NULL COMMENT '公司', bag_sku VARCHAR(50) NOT NULL COMMENT '福袋SKU', component_sku VARCHAR(50) NOT NULL COMMENT '候选SKU',
      PRIMARY KEY(company_code,bag_sku,component_sku), KEY component_reference(company_code,component_sku)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='福袋候选商品引用';""", db)


def install_inventory(db):
    execute("""CREATE TABLE IF NOT EXISTS oms_lucky_exposure (
      company_code VARCHAR(50) NOT NULL COMMENT '公司', channel_id BIGINT NOT NULL COMMENT '渠道', bag_sku VARCHAR(50) NOT NULL COMMENT '福袋SKU',
      group_id BIGINT NOT NULL COMMENT '平台同步组', request_key VARCHAR(64) NOT NULL COMMENT '当前同步栅栏', quantity BIGINT NOT NULL DEFAULT 0 COMMENT '仍可能在平台售出的袋数',
      uncertain TINYINT NOT NULL DEFAULT 1 COMMENT '未完成平台与订单核对时禁止回收', update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '核对时间',
      PRIMARY KEY(company_code,channel_id,bag_sku,group_id), KEY pool_guard(company_code,bag_sku,channel_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='福袋平台曝光持久保护';""", db)
    execute("""CREATE TABLE IF NOT EXISTS oms_lucky_definition (
      company_code VARCHAR(50) NOT NULL COMMENT '公司', sku_sn VARCHAR(50) NOT NULL COMMENT '福袋SKU',
      revision BIGINT NOT NULL COMMENT '规则版本', definition_json JSON NOT NULL COMMENT '已核实规则快照',
      update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '核对时间', PRIMARY KEY(company_code,sku_sn)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='福袋库存规则投影';
    CREATE TABLE IF NOT EXISTS oms_lucky_pool (
      id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '保障池编号', company_code VARCHAR(50) NOT NULL COMMENT '公司', bag_sku VARCHAR(50) NOT NULL COMMENT '福袋SKU',
      revision BIGINT NOT NULL COMMENT '规则版本', rule_id BIGINT NOT NULL COMMENT '原分货单', channel_id BIGINT NOT NULL COMMENT '渠道', store_code VARCHAR(50) NOT NULL COMMENT '固定发货虚仓',
      request_key VARCHAR(100) NOT NULL COMMENT '幂等保障请求', definition_json JSON NOT NULL COMMENT '规则快照',
      quantity BIGINT NOT NULL COMMENT '原始保障袋数', available_quantity BIGINT NOT NULL COMMENT '未占用袋数',
      occupied_quantity BIGINT NOT NULL DEFAULT 0 COMMENT '订单占用袋数', consumed_quantity BIGINT NOT NULL DEFAULT 0 COMMENT '已发袋数', released_quantity BIGINT NOT NULL DEFAULT 0 COMMENT '安全释放袋数',
      state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT '运行状态', create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '建立时间',
      UNIQUE KEY request_scope(company_code,request_key), KEY channel_available(company_code,bag_sku,channel_id,state,id), KEY rule_source(company_code,rule_id,bag_sku)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='福袋组件保障与渠道袋数';
    CREATE TABLE IF NOT EXISTS oms_lucky_source (
      id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '组件来源编号', company_code VARCHAR(50) NOT NULL COMMENT '公司', pool_id BIGINT NOT NULL COMMENT '保障池',
      group_key VARCHAR(32) NOT NULL COMMENT '分组', sku_sn VARCHAR(50) NOT NULL COMMENT '实际SKU', batch_id BIGINT NOT NULL COMMENT '原库存批次',
      quantity BIGINT NOT NULL COMMENT '原始保障件数', occupied_quantity BIGINT NOT NULL DEFAULT 0 COMMENT '订单占用件数', consumed_quantity BIGINT NOT NULL DEFAULT 0 COMMENT '出库件数', released_quantity BIGINT NOT NULL DEFAULT 0 COMMENT '释放件数',
      unit_cost DECIMAL(18,4) NULL COMMENT '原批次单位成本', UNIQUE KEY pool_batch(company_code,pool_id,batch_id), KEY batch_scope(company_code,batch_id), KEY pool_group(company_code,pool_id,group_key)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='福袋原始组件批次保障';
    CREATE TABLE IF NOT EXISTS oms_lucky_unit (
      id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '袋实例编号', company_code VARCHAR(50) NOT NULL COMMENT '公司', pool_id BIGINT NOT NULL COMMENT '保障池',
      sequence_no INT NOT NULL COMMENT '池内序号', components_json JSON NOT NULL COMMENT '实际组件及来源', cost DECIMAL(18,4) NOT NULL COMMENT '每袋批次成本',
      state VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE' COMMENT '可用或订单占用或已发或释放', channel_id BIGINT NOT NULL COMMENT '渠道',
      platform_tid VARCHAR(100) NULL COMMENT '履约库存请求身份', platform_oid VARCHAR(64) NULL COMMENT '平台子订单', order_unit INT NULL COMMENT '本销售行袋序号',
      KEY pool_available(company_code,pool_id,state,id), KEY order_scope(company_code,channel_id,platform_tid,platform_oid), UNIQUE KEY pool_unit(company_code,pool_id,sequence_no)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='预制或订单确定的逐袋组成';
    CREATE TABLE IF NOT EXISTS oms_lucky_claim (
      id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '销售袋数占用编号', company_code VARCHAR(50) NOT NULL COMMENT '公司', channel_id BIGINT NOT NULL COMMENT '渠道',
      platform_tid VARCHAR(100) NOT NULL COMMENT '履约库存请求身份', platform_oid VARCHAR(64) NOT NULL COMMENT '平台子订单', bag_sku VARCHAR(50) NOT NULL COMMENT '销售福袋SKU',
      quantity INT NOT NULL COMMENT '购买袋数', state VARCHAR(20) NOT NULL DEFAULT 'RESERVED' COMMENT '占用状态', snapshot_json JSON NOT NULL COMMENT '原始逐袋组成快照',
      create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '登记时间', UNIQUE KEY sales_identity(company_code,channel_id,platform_tid,platform_oid)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='福袋订单幂等占用';""", db)
    column(db, 'oms_order_stock_demand', 'lucky_sku', "VARCHAR(50) NULL COMMENT '福袋来源SKU，组件独立占用'")
    column(db, 'oms_lucky_pool', 'release_requested_at', "DATETIME NULL COMMENT '本次渠道回收请求时间，平台核对须晚于此时间'")


def install_order(db):
    column(db, 'oms_order_bundle', 'business_type', "VARCHAR(16) NOT NULL DEFAULT 'BUNDLE' COMMENT '固定套装或LUCKY_BAG'")
    execute("""CREATE TABLE IF NOT EXISTS oms_refund_lucky_unit_claim (
      company_code VARCHAR(50) NOT NULL COMMENT '公司', bundle_id BIGINT NOT NULL COMMENT '原销售福袋行',
      unit_id BIGINT NOT NULL COMMENT '原袋实例', refund_id BIGINT NOT NULL COMMENT '占用退款单',
      create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '登记时间',
      PRIMARY KEY(company_code,bundle_id,unit_id), KEY refund_scope(company_code,refund_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='福袋整袋退回占用，防止同袋重复申请';""", db)


if __name__ == '__main__':
    install_goods('qm_oms_saas_commodity')
    install_inventory('qm_oms_saas_inventory')
    install_order('qm_oms_saas_order')
    print('Installed lucky-bag schemas without historical backfill or rule activation')
