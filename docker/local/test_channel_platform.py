"""Run channel protocol and lifecycle tests against an isolated local MySQL schema."""
import os
import shutil
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path
from local_db import execute, ENV, ROOT, HERE

DB='channel_platform_test'
def main():
    execute('CREATE DATABASE IF NOT EXISTS '+DB+' CHARACTER SET utf8mb4')
    execute(f'CREATE TABLE IF NOT EXISTS {DB}.t_channel LIKE qm_oms_saas_channel.t_channel')
    execute((HERE/'migrations/20261010_channel_platform.sql').read_text(encoding='utf-8'),DB)
    cache=Path.home()/'.cache/oms-local'
    relative=Path('oms-modules/oms-channel/src/test')
    shutil.copytree(ROOT/relative,cache/'source'/relative,dirs_exist_ok=True)
    env=dict(os.environ,CHANNEL_TEST_URL=f'jdbc:mysql://127.0.0.1:13306/{DB}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai',INVENTORY_TEST_PASSWORD=ENV['MYSQL_ROOT_PASSWORD'],JAVA_HOME='/usr/lib/jvm/java-17-openjdk-amd64',TZ='Asia/Shanghai')
    command=[str(HERE/'tools/apache-maven-3.9.9/bin/mvn'),'-B','-f',str(cache/'source/pom.xml'),'-Plocal-docker','-Dmaven.repo.local='+str(cache/'m2'),'-pl','oms-modules/oms-channel','-Dtest=ChannelPlatformTest,TmallClientTest','test']
    with (HERE/'logs/channel-platform-tests.log').open('w') as log: result=subprocess.run(command,env=env,stdout=log,stderr=subprocess.STDOUT)
    if result.returncode: raise SystemExit(result.returncode)
    count=0
    for report in (cache/'source/oms-modules/oms-channel/target/surefire-reports').glob('TEST-*.xml'):
        data=ET.parse(report).getroot().attrib
        assert int(data.get('skipped',0))==int(data['errors'])==int(data['failures'])==0,data
        count+=int(data['tests'])
    assert count>=29,count
    execute('DROP DATABASE '+DB)
    print(f'PASS channel platform: {count} tests; isolated schema removed')

if __name__=='__main__':main()
