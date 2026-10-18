"""Regression suite against the disposable order/purchase databases initialized by local test runners."""
import os
import sys
import shutil
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path
from local_db import ROOT, ENV, test_jdbc_url


def main():
    from test_purchase_workspace import prepare
    if sys.argv[1:] != ['--prepared']:
        prepare()
    cache = Path.home() / '.cache/oms-local'
    for module in ['oms-common-core', 'oms-inventory', 'oms-order', 'oms-supplychain']:
        shutil.copytree(ROOT / 'oms-modules' / module / 'src', cache / 'source/oms-modules' / module / 'src', dirs_exist_ok=True)
    env = dict(os.environ, ORDER_TEST_URL=test_jdbc_url('order_workspace_test'),
               PURCHASE_TEST_URL=test_jdbc_url('purchase_workspace_test'), ORDER_STOCK_TEST_URL=test_jdbc_url('order_inventory_test'),
               INVENTORY_TEST_PASSWORD=ENV['MYSQL_ROOT_PASSWORD'], JAVA_HOME='/usr/lib/jvm/java-17-openjdk-amd64')
    suites = ['OrderStockTest', 'OrderFulfillmentAdjustmentTest', 'ExchangeServiceTest', 'OrderShipmentServiceTest', 'OrderShipmentDispatchServiceTest', 'OrderSalesDocumentServiceTest',
              'ExchangeReturnTest', 'PurchaseWorkspaceTest', 'WmsOutboundServiceTest', 'WmsOutboundReceiptServiceTest',
              'SalesOutboundDocumentServiceTest', 'WmsLogQueryTest', 'PurchaseCutoffTest']
    command = [str(ROOT / 'docker/local/tools/apache-maven-3.9.9/bin/mvn'), '-B', '-f', str(cache / 'source/pom.xml'),
               '-Plocal-docker', '-Dmaven.repo.local=' + str(cache / 'm2'), '-pl', 'oms-modules/oms-inventory,oms-modules/oms-order,oms-modules/oms-supplychain',
               '-am', '-Dtest=' + ','.join(suites), '-Dsurefire.failIfNoSpecifiedTests=false', 'test']
    with (ROOT / 'docker/local/logs/exchange-control-tests.log').open('w') as log:
        result = subprocess.run(command, env=env, stdout=log, stderr=subprocess.STDOUT)
    if result.returncode:
        raise SystemExit('Exchange control tests failed; see docker/local/logs/exchange-control-tests.log')
    total = 0
    for module in ['oms-inventory', 'oms-order', 'oms-supplychain']:
        for report in (cache / 'source/oms-modules' / module / 'target/surefire-reports').glob('TEST-*.xml'):
            root = ET.parse(report).getroot()
            if root.attrib['name'].split('.')[-1] in suites:
                assert all(int(root.attrib.get(k, 0)) == 0 for k in ['failures', 'errors', 'skipped']), root.attrib['name']
                total += int(root.attrib['tests'])
    print('PASS exchange control, shipment and supplychain regression tests:', total)


if __name__ == '__main__':
    main()
