"""Refund intake/conversion and fulfillment isolation against a disposable MySQL schema."""
import os
import shutil
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path
from local_db import ROOT, HERE, ENV, test_jdbc_url


def main():
    from configure_refunds import install_order
    install_order('order_workspace_test')
    cache = Path.home() / '.cache/oms-local'
    for module in ['oms-common-core', 'oms-order']:
        shutil.copytree(ROOT / 'oms-modules' / module / 'src', cache / 'source/oms-modules' / module / 'src', dirs_exist_ok=True)
    shutil.copytree(ROOT/'ruoyi-common/ruoyi-common-core/src',cache/'source/ruoyi-common/ruoyi-common-core/src',dirs_exist_ok=True)
    shutil.copytree(ROOT/'oms-modules/oms-messaging/src',cache/'source/oms-modules/oms-messaging/src',dirs_exist_ok=True)
    suites = ['RefundSupplementContinuationTest','RefundAllocationContextTest','RefundGoodsServiceTest','RefundParcelAutomationTest','RefundReturnDispatchAutomationTest','RefundGiftScopeTest', 'RefundAutomaticReturnDecisionTest', 'RefundOrderCancellationTest', 'OrderInventoryStatusTest', 'RefundDispositionServiceTest', 'RefundAmountContextTest', 'RefundAutoReturnsTest', 'RefundAutomaticDecisionTest', 'GiftServiceTest', 'OrderBusinessConfigurationTest', 'TenantRoutingTest', 'RefundConfigurationTest', 'RefundWorkflowTest', 'RefundPlatformExecutionTest', 'RefundGoodsTasksTest', 'RefundFulfillmentTest', 'RefundServiceTest', 'RefundPushReaderTest', 'ConfiguredPushReaderTest', 'PushSourceServiceTest',
              'RefundBusinessListTest', 'OrderReviewPolicyTest', 'OrderAutoReviewServiceTest', 'OrderUpdateServiceTest', 'ExchangeServiceTest', 'OrderShipmentDispatchServiceTest', 'OrderShipmentServiceTest']
    env = dict(os.environ, ORDER_TEST_URL=test_jdbc_url('order_workspace_test'), INVENTORY_TEST_PASSWORD=ENV['MYSQL_ROOT_PASSWORD'], JAVA_HOME='/usr/lib/jvm/java-17-openjdk-amd64')
    command = [str(HERE / 'tools/apache-maven-3.9.9/bin/mvn'), '-B', '-f', str(cache / 'source/pom.xml'), '-Plocal-docker',
               '-Dmaven.repo.local=' + str(cache / 'm2'), '-pl', 'oms-modules/oms-order', '-am', '-Dtest=' + ','.join(suites),
               '-Dsurefire.failIfNoSpecifiedTests=false', 'test']
    with (HERE / 'logs/refund-tests.log').open('w') as log:
        result = subprocess.run(command, env=env, stdout=log, stderr=subprocess.STDOUT)
    if result.returncode:
        raise SystemExit('Refund tests failed; see docker/local/logs/refund-tests.log')
    total = 0
    for report in (cache / 'source/oms-modules/oms-order/target/surefire-reports').glob('TEST-*.xml'):
        data = ET.parse(report).getroot().attrib
        if data['name'].split('.')[-1] in suites:
            assert all(int(data.get(k, 0)) == 0 for k in ['failures', 'errors', 'skipped']), data['name']
            total += int(data['tests'])
    print('PASS refund intake, conversion, review and fulfillment regression tests:', total)


if __name__ == '__main__':
    main()
