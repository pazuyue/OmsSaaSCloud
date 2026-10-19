"""Refund returns plus shared exchange, warehouse, document and log regressions."""
import os
import shutil
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path
from local_db import ROOT,HERE,ENV,test_jdbc_url,execute


def main():
    from configure_refunds import install_order
    from configure_refund_goods import install_supplychain
    install_order('order_workspace_test')
    if not execute("SELECT 1 FROM information_schema.tables WHERE table_schema='purchase_workspace_test' AND table_name='wms_exchange_return'").strip():
        from test_purchase_workspace import prepare
        prepare()
    install_supplychain('purchase_workspace_test')
    cache=Path.home()/'.cache/oms-local'
    for module in ['oms-common-core','oms-order','oms-supplychain']:
        shutil.copytree(ROOT/'oms-modules'/module/'src',cache/'source/oms-modules'/module/'src',dirs_exist_ok=True)
    suites=['TenantRoutingTest','RefundGoodsServiceTest','RefundServiceTest','ExchangeServiceTest','ExchangeReturnTest','RefundReturnTest','RefundParcelsTest','RefundParcelPolicyTest','RefundAllocationContextTest','RefundParcelAutomationTest','RefundParcelReviewTest','RefundSharedScopeTest','PurchaseWorkspaceTest','WmsLogQueryTest']
    env=dict(os.environ,ORDER_TEST_URL=test_jdbc_url('order_workspace_test'),PURCHASE_TEST_URL=test_jdbc_url('purchase_workspace_test'),INVENTORY_TEST_PASSWORD=ENV['MYSQL_ROOT_PASSWORD'],JAVA_HOME='/usr/lib/jvm/java-17-openjdk-amd64')
    command=[str(HERE/'tools/apache-maven-3.9.9/bin/mvn'),'-B','-f',str(cache/'source/pom.xml'),'-Plocal-docker','-Dmaven.repo.local='+str(cache/'m2'),'-pl','oms-modules/oms-order,oms-modules/oms-supplychain','-am','-Dtest='+','.join(suites),'-Dsurefire.failIfNoSpecifiedTests=false','test']
    with (HERE/'logs/refund-goods-tests.log').open('w') as log:result=subprocess.run(command,env=env,stdout=log,stderr=subprocess.STDOUT)
    if result.returncode:raise SystemExit('Refund goods tests failed; see docker/local/logs/refund-goods-tests.log')
    total=0
    for module in ['oms-order','oms-supplychain']:
        for report in (cache/'source/oms-modules'/module/'target/surefire-reports').glob('TEST-*.xml'):
            data=ET.parse(report).getroot().attrib
            if data['name'].split('.')[-1] in suites:
                assert all(int(data.get(k,0))==0 for k in ['errors','failures','skipped']),data['name']
                total+=int(data['tests'])
    print('PASS refund goods and shared return regression tests:',total)


if __name__=='__main__':main()
