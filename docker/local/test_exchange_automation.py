"""Run exchange/shared-return and affected regressions in isolated test schemas.

Run with WSL Python. Requires the ordinary order and purchase test schemas.
Never installs or modifies a business schema or business queue configuration.
"""
import json
import os
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from local_db import ROOT, HERE, ENV, test_jdbc_url, execute
from configure_messaging import management


def main():
    if sys.argv[1:] not in ([], ['--partial-continuous'], ['--return-split'], ['--return-management']):
        raise SystemExit('Usage: test_exchange_automation.py [--partial-continuous|--return-split|--return-management]')
    if sys.argv[1:] == ['--return-management']:
        from configure_return_management import install_order, install_supplychain
        install_order('order_workspace_test'); install_supplychain('purchase_workspace_test')
        from configure_return_document_detail import install
        install('order_workspace_test','purchase_workspace_test')
    execute('CREATE DATABASE IF NOT EXISTS queue_control_test CHARACTER SET utf8mb4')
    management('queue_control_test')
    execute((HERE / 'migrations/20261024_exchange_queue.sql').read_text(encoding='utf-8'), 'queue_control_test')
    cache = Path.home() / '.cache/oms-local'
    modules = ['oms-modules/oms-order', 'oms-modules/oms-supplychain', 'ruoyi-modules/ruoyi-system']
    for module in modules:
        shutil.copytree(ROOT / module / 'src', cache / 'source' / module / 'src', dirs_exist_ok=True)
    env = dict(os.environ, ORDER_TEST_URL=test_jdbc_url('order_workspace_test'),
               PURCHASE_TEST_URL=test_jdbc_url('purchase_workspace_test'),
               QUEUE_CONTROL_TEST_URL=test_jdbc_url('queue_control_test'),
               INVENTORY_TEST_PASSWORD=ENV['MYSQL_ROOT_PASSWORD'], JAVA_HOME='/usr/lib/jvm/java-17-openjdk-amd64')
    baseline = ROOT / 'outputs/exchange-automation'
    out = ROOT / ('outputs/exchange-return-split' if sys.argv[1:] == ['--return-split'] else 'outputs/exchange-partial-continuous') if sys.argv[1:] else baseline
    if sys.argv[1:] == ['--return-management']:
        out = ROOT / 'outputs/return-management'
    out.mkdir(parents=True, exist_ok=True)
    names = [row['name'].split('.')[-1] for row in json.loads((baseline / 'regression.json').read_text())['suites']]
    if 'ExchangeSyncTest' not in names:
        names.append('ExchangeSyncTest')
    if sys.argv[1:] == ['--return-management']:
        names.extend(['ReturnManagementQueryTest', 'ReturnQueryWarehouseTest', 'ReturnDocumentReadTest'])
    command = [str(HERE / 'tools/apache-maven-3.9.9/bin/mvn'), '-B', '-f', str(cache / 'source/pom.xml'),
               '-Plocal-docker', '-Dmaven.repo.local=' + str(cache / 'm2'), '-pl', ','.join(modules), '-am',
               '-Dtest=' + ','.join(names), '-Dsurefire.failIfNoSpecifiedTests=false', 'package']
    log_path = HERE / ('logs/exchange-return-split-tests.log' if sys.argv[1:] == ['--return-split'] else 'logs/exchange-partial-continuous-tests.log' if sys.argv[1:] else 'logs/exchange-automation-final.log')
    if sys.argv[1:] == ['--return-management']:
        log_path = HERE / 'logs/return-management-tests.log'
    with log_path.open('w') as log:
        result = subprocess.run(command, env=env, stdout=log, stderr=subprocess.STDOUT)
    if result.returncode:
        raise SystemExit('Regression/package failed: ' + str(log_path))
    reports = []
    for module in modules:
        for report in (cache / 'source' / module / 'target/surefire-reports').glob('TEST-*.xml'):
            data = ET.parse(report).getroot().attrib
            if data['name'].split('.')[-1] in names:
                assert all(int(data.get(key, 0)) == 0 for key in ['errors', 'failures', 'skipped']), data
                reports.append({key: data[key] for key in ['name', 'tests', 'failures', 'errors', 'skipped']})
    assert len(reports) == len(names), (len(reports), len(names))
    total = sum(int(row['tests']) for row in reports)
    (out / 'regression-final.json').write_text(json.dumps({'suites': reports, 'total_tests': total}, indent=2))
    print('PASS', total, 'tests; zero failures, errors or skips')


if __name__ == '__main__':
    main()
