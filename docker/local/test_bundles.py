"""Isolated fixed-kit and affected order/inventory/refund/master-data regressions."""
import os,shutil,subprocess,xml.etree.ElementTree as ET
from pathlib import Path
from local_db import ROOT,HERE,ENV,execute,test_jdbc_url
from configure_bundles import install_goods,install_order,install_inventory

def main():
    install_order('order_workspace_test');install_inventory('order_inventory_test')
    execute('CREATE DATABASE IF NOT EXISTS goods_workspace_test CHARACTER SET utf8mb4')
    for table in ['goods_sku_sn_info','goods_category','goods_color','goods_size','goods_master_lock','goods_import_batch','goods_import_row']:execute('CREATE TABLE IF NOT EXISTS goods_workspace_test.'+table+' LIKE qm_oms_saas_commodity.'+table)
    install_goods('goods_workspace_test')
    from configure_gifts import install_goods as install_gift_goods
    install_gift_goods('goods_workspace_test')
    cache=Path.home()/'.cache/oms-local'
    for module in ['oms-common-core','oms-order','oms-inventory','oms-goods-administration']:shutil.copytree(ROOT/'oms-modules'/module/'src',cache/'source/oms-modules'/module/'src',dirs_exist_ok=True)
    suites=['OrderBundleTest','ExchangeServiceTest','BundleWarehousePlannerTest','GoodsBundleTest','OrderProcessorTest','OrderRepositoryTest','OrderProductMatchServiceTest','OrderStockTest','WarehouseAllocationPlannerTest','OrderAllocationServiceTest','OrderShipmentServiceTest','OrderPlatformShippingServiceTest','OrderVirtualOutboundTest','OrderVirtualOutboundServiceTest','OrderShipmentReceiptServiceTest','OrderIssueServiceTest','OrderFulfillmentAdjustmentTest','OrderCutoffServiceTest','RefundGoodsServiceTest','RefundBundleReturnTest','RefundServiceTest','GoodsWorkspaceTest','OrderAllocationPolicyTest']
    env=dict(os.environ,ORDER_STOCK_TEST_URL=test_jdbc_url('order_inventory_test'),ORDER_TEST_URL=test_jdbc_url('order_workspace_test'),GOODS_TEST_URL=test_jdbc_url('goods_workspace_test'),INVENTORY_TEST_PASSWORD=ENV['MYSQL_ROOT_PASSWORD'],JAVA_HOME='/usr/lib/jvm/java-17-openjdk-amd64')
    command=[str(HERE/'tools/apache-maven-3.9.9/bin/mvn'),'-B','-f',str(cache/'source/pom.xml'),'-Plocal-docker','-Dmaven.repo.local='+str(cache/'m2'),'-pl','oms-modules/oms-order,oms-modules/oms-inventory,oms-modules/oms-goods-administration','-am','-Dtest='+','.join(suites),'-Dsurefire.failIfNoSpecifiedTests=false','test']
    with (HERE/'logs/bundle-tests.log').open('w') as log:result=subprocess.run(command,env=env,stdout=log,stderr=subprocess.STDOUT)
    if result.returncode:raise SystemExit('Kit tests failed; see docker/local/logs/bundle-tests.log')
    total=0
    for module in ['oms-order','oms-inventory','oms-goods-administration']:
        for report in (cache/'source/oms-modules'/module/'target/surefire-reports').glob('TEST-*.xml'):
            a=ET.parse(report).getroot().attrib
            if a['name'].split('.')[-1] in suites:
                assert all(int(a.get(k,0))==0 for k in ['errors','failures','skipped']),a['name'];total+=int(a['tests'])
    print('PASS fixed-kit and affected workflow regressions:',total)
if __name__=='__main__':main()
