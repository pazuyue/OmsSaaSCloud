"""Lucky-bag rules and real-MySQL regressions; disposable schemas only."""
import os
import sys
import shutil
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path
from local_db import ROOT, HERE, ENV, test_jdbc_url, execute


def main():
    from test_goods_workspace import prepare
    from configure_order import install_inventory
    from configure_bundles import install_order
    install_order('order_workspace_test')
    from configure_order_documents import install as documents
    documents('order_workspace_test')
    from configure_platform_log_center import install as logs
    from configure_exchanges import install_channel
    from configure_platform_products import install as products
    logs('channel_platform_test');install_channel('channel_platform_test');products('channel_platform_test')
    prepare()
    for table in ['rule_stock_info','rule_stock_store_code_info','rule_stock_channel_info','rule_stock_goods_info','rule_stock_result','rule_stock_daily_run','rule_stock_daily_item']:
        execute(f'CREATE TABLE IF NOT EXISTS order_inventory_test.{table} LIKE qm_oms_saas_inventory.{table}')
    from configure_order import column
    for name,definition in {'interval_minutes':'INT NOT NULL DEFAULT 5','daily_priority':'INT NOT NULL DEFAULT 100','daily_enabled':'TINYINT NOT NULL DEFAULT 0','next_run_at':'DATETIME NULL','active_run_id':'BIGINT NULL'}.items():
        column('order_inventory_test','rule_stock_info',name,definition)
    install_inventory('order_inventory_test')
    for database in ['inventory_workspace_test','inventory_workspace_test_other']:
        execute('CREATE DATABASE IF NOT EXISTS '+database+' CHARACTER SET utf8mb4')
        for table in ['oms_inventory','wms_inventory','wms_inventory_batch','wms_inventory_change_history','oms_channel_inventory','rule_stock_info','rule_stock_store_code_info','rule_stock_channel_info','rule_stock_goods_info','rule_stock_result','rule_stock_reservation','rule_stock_order_reservation','rule_stock_reservation_event','rule_stock_daily_run','rule_stock_daily_item']:
            execute(f'CREATE TABLE IF NOT EXISTS {database}.{table} LIKE qm_oms_saas_inventory.{table}')
        install_inventory(database)
    cache = Path.home() / '.cache/oms-local' 
    modules = ['oms-common-core', 'oms-order', 'oms-inventory', 'oms-goods-administration', 'oms-channel']
    for module in modules:
        shutil.copytree(ROOT / 'oms-modules' / module / 'src', cache / 'source/oms-modules' / module / 'src', dirs_exist_ok=True)
    suites = ['GiftProductsTest', 'GiftQuotaTest', 'GiftCatalogTest', 'GiftStrategyDesignerTest', 'GoodsGiftProtectionTest', 'OrderDocumentInfoTest', 'OrderDocumentPersistenceTest', 'OrderUpdateServiceTest', 'TmallOrderParserTest', 'LuckyBagRulesTest', 'GoodsLuckyBagTest', 'LuckyBagInventoryTest', 'GoodsBundleTest', 'GoodsWorkspaceTest', 'OrderBundleTest', 'OrderAllocationServiceTest', 'RefundBusinessListTest', 'OrderProcessorTest', 'OrderRepositoryTest', 'OrderProductMatchServiceTest', 'OrderShipmentServiceTest', 'OrderPlatformShippingServiceTest', 'RefundBundleReturnTest', 'RefundGoodsServiceTest', 'GiftServiceTest', 'StockSyncTest', 'OrderStockTest', 'OrderFulfillmentAdjustmentTest', 'LuckyBagOrderTest', 'RefundReturnAutoCreateTest', 'AllocationWorkspaceTest', 'DailyAllocationTest', 'InventoryTransactionsTest', 'ProductInventoryQueryTest', 'DailyCoordinatorTest']
    if '--focused' in sys.argv:
        suites=[s for s in suites if s not in ['InventoryTransactionsTest','DailyCoordinatorTest','DailyAllocationTest']]
    suites += ['OrderShipmentDispatchServiceTest', 'OrderShipmentReceiptServiceTest', 'OrderVirtualOutboundServiceTest', 'OrderCutoffServiceTest', 'OrderVirtualOutboundTest', 'RefundSharedScopeTest']
    if '--inventory-only' in sys.argv:
        suites=['LuckyBagInventoryTest','BundleWarehousePlannerTest','WarehouseAllocationPlannerTest','OrderStockTest','AllocationWorkspaceTest','ProductInventoryQueryTest']
    env = dict(os.environ, GOODS_TEST_URL=test_jdbc_url('goods_workspace_test'),
               ORDER_STOCK_TEST_URL=test_jdbc_url('order_inventory_test'),INVENTORY_TEST_URL=test_jdbc_url('inventory_workspace_test'),
               ORDER_TEST_URL=test_jdbc_url('order_workspace_test'),CHANNEL_TEST_URL=test_jdbc_url('channel_platform_test'),
               INVENTORY_TEST_PASSWORD=ENV['MYSQL_ROOT_PASSWORD'], JAVA_HOME='/usr/lib/jvm/java-17-openjdk-amd64')
    command = [str(HERE / 'tools/apache-maven-3.9.9/bin/mvn'), '-B', '-f', str(cache / 'source/pom.xml'),
               '-Plocal-docker', '-Dmaven.repo.local=' + str(cache / 'm2'),
               '-pl', ','.join('oms-modules/' + m for m in modules if m != 'oms-common-core'), '-am',
               '-Dtest=' + ','.join(suites), '-Dsurefire.failIfNoSpecifiedTests=false', 'clean', 'test']
    with (HERE / 'logs/lucky-bag-tests.log').open('w') as log:
        result = subprocess.run(command, env=env, stdout=log, stderr=subprocess.STDOUT)
    if result.returncode:
        raise SystemExit('Lucky bag tests failed: docker/local/logs/lucky-bag-tests.log')
    total, found = 0, set()
    for module in modules:
        for report in (cache / 'source/oms-modules' / module / 'target/surefire-reports').glob('TEST-*.xml'):
            a = ET.parse(report).getroot().attrib
            name = a['name'].split('.')[-1]
            if name in suites:
                assert int(a['tests']) > 0, a
                assert all(int(a.get(k, 0)) == 0 for k in ['failures', 'errors', 'skipped']), a
                total += int(a['tests'])
                found.add(name)
    assert found == set(suites), set(suites) - found
    print('PASS lucky-bag rules and master-data regressions:', total)


if __name__ == '__main__':
    main()
