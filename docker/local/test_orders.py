"""Isolated MySQL verification for order management and logical inventory reservation."""
import os, shutil, subprocess, sys
from pathlib import Path
from local_db import execute, ENV, ROOT, HERE, test_jdbc_url
from configure_order import install_inventory

def main():
    if sys.argv[1:] not in ([],['--order-only'],['--inventory-only']):raise SystemExit('Usage: test_orders.py [--order-only|--inventory-only]')
    order_only=sys.argv[1:]==['--order-only']
    inventory_only=sys.argv[1:]==['--inventory-only']
    database='order_inventory_test'
    execute('CREATE DATABASE IF NOT EXISTS '+database+' CHARACTER SET utf8mb4')
    tables=['oms_inventory','oms_channel_inventory','wms_inventory','wms_inventory_batch','wms_inventory_change_history','rule_stock_info','rule_stock_result','rule_stock_reservation','rule_stock_order_reservation','rule_stock_reservation_event']
    for table in tables:execute(f'CREATE TABLE IF NOT EXISTS {database}.{table} LIKE qm_oms_saas_inventory.{table}')
    install_inventory(database)
    execute('CREATE DATABASE IF NOT EXISTS order_workspace_test CHARACTER SET utf8mb4')
    execute((HERE/'migrations/20261010_order.sql').read_text(encoding='utf-8'),'order_workspace_test')
    execute((HERE/'migrations/20261011_order_management.sql').read_text(encoding='utf-8'),'order_workspace_test')
    from configure_order_trade_types import install as install_trade_types
    install_trade_types('order_workspace_test')
    from configure_order_exceptions import install as install_exceptions
    install_exceptions('order_workspace_test')
    from configure_business_orders import install_order as install_business_order
    install_business_order('order_workspace_test')
    cache=Path.home()/'.cache/oms-local'
    for module in ['oms-order','oms-inventory']:
        source=ROOT/'oms-modules'/module/'src'
        if source.exists():shutil.copytree(source,cache/'source/oms-modules'/module/'src',dirs_exist_ok=True)
    env=dict(os.environ,ORDER_STOCK_TEST_URL=test_jdbc_url(database),ORDER_TEST_URL=test_jdbc_url('order_workspace_test'),INVENTORY_TEST_PASSWORD=ENV['MYSQL_ROOT_PASSWORD'],JAVA_HOME='/usr/lib/jvm/java-17-openjdk-amd64')
    for key in ['OMS_ORDER_PUSH_113_URL','OMS_ORDER_PUSH_113_USERNAME','OMS_ORDER_PUSH_113_PASSWORD','OMS_ORDER_PUSH_100_URL','OMS_ORDER_PUSH_100_USERNAME','OMS_ORDER_PUSH_100_PASSWORD']:
        if key in ENV: env[key]=ENV[key].strip("'")
    command=[str(ROOT/'docker/local/tools/apache-maven-3.9.9/bin/mvn'),'-B','-f',str(cache/'source/pom.xml'),'-Plocal-docker','-Dmaven.repo.local='+str(cache/'m2'),'-pl','oms-modules/oms-order,oms-modules/oms-inventory','-Dtest=TmallOrderParserTest,OrderRepositoryTest,OrderProcessorTest,OrderProductMatchServiceTest,TmallPushReaderTest,OrderStockTest,OrderVirtualOutboundTest,PushSourceServiceTest,ConfiguredPushReaderTest,OrderWorkspaceServiceTest,OrderAllocationPolicyTest,OrderAllocationServiceTest,OrderShipmentServiceTest,OrderShipmentDispatchServiceTest,OrderVirtualOutboundServiceTest,OrderCarrierServiceTest,OrderCarrierPolicyTest,OrderReviewPolicyTest,OrderInventoryStatusTest,WarehouseAllocationPlannerTest','-Dsurefire.failIfNoSpecifiedTests=false','test']
    if order_only:
        command[command.index('-pl')+1]='oms-modules/oms-order'
        command=[arg.replace(',OrderStockTest,OrderVirtualOutboundTest,',',') if arg.startswith('-Dtest=') else arg for arg in command]
    if inventory_only:command[command.index('-pl')+1]='oms-modules/oms-inventory'
    with (HERE/('logs/order-inventory-tests.log' if inventory_only else 'logs/order-tests.log')).open('w') as log:result=subprocess.run(command,env=env,stdout=log,stderr=subprocess.STDOUT)
    print('Order tests exit:',result.returncode)
    sys.exit(result.returncode)
if __name__=='__main__':main()
