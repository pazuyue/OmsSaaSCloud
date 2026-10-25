"""Run channel protocol and lifecycle tests against an isolated local MySQL schema."""
import os
import shutil
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path
from local_db import execute, ENV, ROOT, HERE, test_jdbc_url

DB='channel_platform_test'
def main():
    execute('CREATE DATABASE IF NOT EXISTS '+DB+' CHARACTER SET utf8mb4')
    execute(f'CREATE TABLE IF NOT EXISTS {DB}.t_channel LIKE qm_oms_saas_channel.t_channel')
    execute((HERE/'migrations/20261010_channel_platform.sql').read_text(encoding='utf-8'),DB)
    cache=Path.home()/'.cache/oms-local'
    from configure_exchanges import install_channel
    install_channel(DB)
    from configure_refund_execution import install_channel as refund_channel
    refund_channel(DB)
    from configure_platform_log_center import install
    install(DB)
    relative=Path('oms-modules/oms-channel/src')
    shutil.copytree(ROOT/relative,cache/'source'/relative,dirs_exist_ok=True)
    env=dict(os.environ,CHANNEL_TEST_URL=test_jdbc_url(DB),INVENTORY_TEST_PASSWORD=ENV['MYSQL_ROOT_PASSWORD'],JAVA_HOME='/usr/lib/jvm/java-17-openjdk-amd64',TZ='Asia/Shanghai')
    command=[str(HERE/'tools/apache-maven-3.9.9/bin/mvn'),'-B','-f',str(cache/'source/pom.xml'),'-Plocal-docker','-Dmaven.repo.local='+str(cache/'m2'),'-pl','oms-modules/oms-channel','-Dtest=RefundPlatformServiceTest,ChannelPlatformTest,TmallClientTest,ExchangePlatformTest,PlatformLogTest','test']
    with (HERE/'logs/channel-platform-tests.log').open('w') as log: result=subprocess.run(command,env=env,stdout=log,stderr=subprocess.STDOUT)
    if result.returncode: raise SystemExit(result.returncode)
    count=0
    suites={'RefundPlatformServiceTest','ChannelPlatformTest','TmallClientTest','ExchangePlatformTest','PlatformLogTest'}
    seen=set()
    for report in (cache/'source/oms-modules/oms-channel/target/surefire-reports').glob('TEST-*.xml'):
        data=ET.parse(report).getroot().attrib
        suite=data['name'].split('.')[-1]
        if suite not in suites:continue
        seen.add(suite)
        assert int(data.get('skipped',0))==int(data['errors'])==int(data['failures'])==0,data
        count+=int(data['tests'])
    assert seen==suites,seen
    assert count>=39,count
    execute('DROP DATABASE '+DB)
    print(f'PASS channel platform: {count} tests; isolated schema removed')

if __name__=='__main__':main()
