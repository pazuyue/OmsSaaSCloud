"""Run process transactions, protocol and affected kit workflows in disposable databases."""
import os,shutil,subprocess,xml.etree.ElementTree as ET
from pathlib import Path
from local_db import ROOT,HERE,ENV,test_jdbc_url,execute
from configure_process import install_supplychain,install_stock
from configure_bundles import install_order,install_goods

def main():
 install_supplychain('purchase_workspace_test');install_stock('order_inventory_test');install_order('order_workspace_test');install_goods('goods_workspace_test')
 for table in ['goods_sku_sn_info','goods_master_lock']:
  execute('CREATE TABLE IF NOT EXISTS purchase_workspace_test.'+table+' LIKE qm_oms_saas_commodity.'+table)
 cache=Path.home()/'.cache/oms-local'
 for module in ['oms-common-core','oms-order','oms-inventory','oms-goods-administration','oms-supplychain']:shutil.copytree(ROOT/'oms-modules'/module/'src',cache/'source/oms-modules'/module/'src',dirs_exist_ok=True)
 suites=['TenantRoutingTest','StockProcessTest','QimenProcessTest','WmsProcessTest','OrderBundleTest','GoodsBundleTest','OrderStockTest','BundleWarehousePlannerTest','OrderShipmentServiceTest','OrderRepositoryTest','WmsLogQueryTest','PurchaseWorkspaceTest','WmsOutboundServiceTest','OrderProcessorTest','OrderPlatformShippingServiceTest']
 env=dict(os.environ,ORDER_STOCK_TEST_URL=test_jdbc_url('order_inventory_test'),ORDER_TEST_URL=test_jdbc_url('order_workspace_test'),GOODS_TEST_URL=test_jdbc_url('goods_workspace_test'),PURCHASE_TEST_URL=test_jdbc_url('purchase_workspace_test'),INVENTORY_TEST_PASSWORD=ENV['MYSQL_ROOT_PASSWORD'],JAVA_HOME='/usr/lib/jvm/java-17-openjdk-amd64')
 cmd=[str(HERE/'tools/apache-maven-3.9.9/bin/mvn'),'-B','-f',str(cache/'source/pom.xml'),'-Plocal-docker','-Dmaven.repo.local='+str(cache/'m2'),'-pl',','.join('oms-modules/'+m for m in ['oms-order','oms-inventory','oms-goods-administration','oms-supplychain']),'-am','-Dtest='+','.join(suites),'-Dsurefire.failIfNoSpecifiedTests=false','test']
 with (HERE/'logs/process-tests.log').open('w') as log:r=subprocess.run(cmd,env=env,stdout=log,stderr=subprocess.STDOUT)
 if r.returncode:raise SystemExit('Process regressions failed: docker/local/logs/process-tests.log')
 total=0;found=set()
 for module in ['oms-order','oms-inventory','oms-goods-administration','oms-supplychain']:
  for report in (cache/'source/oms-modules'/module/'target/surefire-reports').glob('TEST-*.xml'):
   a=ET.parse(report).getroot().attrib;name=a['name'].split('.')[-1]
   if name in suites:
    assert all(int(a.get(k,0))==0 for k in ['errors','failures','skipped']),a['name'];total+=int(a['tests']);found.add(name)
 assert found==set(suites),(found,set(suites)-found)
 print('PASS warehouse process and affected workflows:',total)
if __name__=='__main__':main()
