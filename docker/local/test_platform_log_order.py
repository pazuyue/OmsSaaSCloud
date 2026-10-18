"""Focused order-side platform logging tests against the existing disposable order schema."""
import os
import shutil
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path
from local_db import ROOT, HERE, ENV, test_jdbc_url


def main():
    cache = Path.home() / '.cache/oms-local'
    relative = Path('oms-modules/oms-order/src')
    shutil.copytree(ROOT / relative, cache / 'source' / relative, dirs_exist_ok=True)
    suites = ['OrderPlatformShippingServiceTest', 'OrderPlatformInteractionRecorderTest',
              'OrderPlatformLogWorkspaceTest', 'ExchangeServiceTest', 'OrderShipmentServiceTest',
              'OrderAllocationServiceTest']
    env = dict(os.environ, ORDER_TEST_URL=test_jdbc_url('order_workspace_test'),
               INVENTORY_TEST_PASSWORD=ENV['MYSQL_ROOT_PASSWORD'], JAVA_HOME='/usr/lib/jvm/java-17-openjdk-amd64')
    command = [str(HERE / 'tools/apache-maven-3.9.9/bin/mvn'), '-B', '-f', str(cache / 'source/pom.xml'),
               '-Plocal-docker', '-Dmaven.repo.local=' + str(cache / 'm2'), '-pl', 'oms-modules/oms-order',
               '-am', '-Dtest=' + ','.join(suites), '-Dsurefire.failIfNoSpecifiedTests=false', 'test']
    with (HERE / 'logs/platform-log-order-tests.log').open('w') as log:
        result = subprocess.run(command, env=env, stdout=log, stderr=subprocess.STDOUT)
    if result.returncode:
        raise SystemExit('Order platform log tests failed; see docker/local/logs/platform-log-order-tests.log')
    count = 0
    for report in (cache / 'source/oms-modules/oms-order/target/surefire-reports').glob('TEST-*.xml'):
        data = ET.parse(report).getroot().attrib
        if data['name'].split('.')[-1] in suites:
            assert all(int(data.get(key, 0)) == 0 for key in ['failures', 'errors', 'skipped']), data['name']
            count += int(data['tests'])
    print('PASS order platform log and fulfillment tests:', count)


if __name__ == '__main__':
    main()
