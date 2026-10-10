"""Run real MySQL master-data workflow tests in a disposable schema."""
import os
import shutil
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path
from local_db import execute, ENV, ROOT

DB = 'goods_workspace_test'

def main():
    execute('CREATE DATABASE IF NOT EXISTS ' + DB + ' CHARACTER SET utf8mb4')
    for table in ['goods_sku_sn_info', 'goods_category', 'goods_color', 'goods_size', 'goods_master_lock', 'goods_import_batch', 'goods_import_row']:
        execute(f'CREATE TABLE IF NOT EXISTS {DB}.{table} LIKE qm_oms_saas_commodity.{table}')
    cache = Path.home() / '.cache/oms-local'
    test = Path('oms-modules/oms-goods-administration/src/test/java/com/oms/goods/GoodsWorkspaceTest.java')
    shutil.copy2(ROOT / test, cache / 'source' / test)
    env = dict(os.environ, GOODS_TEST_URL=f'jdbc:mysql://127.0.0.1:13306/{DB}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai', INVENTORY_TEST_PASSWORD=ENV['MYSQL_ROOT_PASSWORD'], JAVA_HOME='/usr/lib/jvm/java-17-openjdk-amd64')
    command = [str(ROOT / 'docker/local/tools/apache-maven-3.9.9/bin/mvn'), '-B', '-f', str(cache / 'source/pom.xml'), '-Plocal-docker', '-Dmaven.repo.local=' + str(cache / 'm2'), '-pl', 'oms-modules/oms-goods-administration', '-Dtest=GoodsWorkspaceTest', 'test']
    with (ROOT / 'docker/local/logs/goods-workspace-tests.log').open('w') as log:
        result = subprocess.run(command, env=env, stdout=log, stderr=subprocess.STDOUT)
    if result.returncode:
        raise SystemExit(result.returncode)
    report = ET.parse(cache / 'source/oms-modules/oms-goods-administration/target/surefire-reports/TEST-com.oms.goods.GoodsWorkspaceTest.xml').getroot().attrib
    assert int(report['tests']) >= 22 and int(report.get('skipped', 0)) == 0 and int(report['errors']) == 0 and int(report['failures']) == 0, report
    execute('DROP DATABASE ' + DB)
    print('PASS goods workspace MySQL integration tests:', report['tests'])

if __name__ == '__main__':
    main()
