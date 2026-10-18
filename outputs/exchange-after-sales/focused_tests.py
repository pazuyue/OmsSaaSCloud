import os,subprocess,sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[2]/'docker/local'))
from local_db import ENV,ROOT,test_jdbc_url
cache=Path.home()/'.cache/oms-local'
env=dict(os.environ,ORDER_TEST_URL=test_jdbc_url('order_workspace_test'),INVENTORY_TEST_PASSWORD=ENV['MYSQL_ROOT_PASSWORD'],JAVA_HOME='/usr/lib/jvm/java-17-openjdk-amd64')
command=[str(ROOT/'docker/local/tools/apache-maven-3.9.9/bin/mvn'),'-B','-f',str(cache/'source/pom.xml'),'-Plocal-docker','-Dmaven.repo.local='+str(cache/'m2'),'-pl','oms-modules/oms-order','-am','-Dtest=ExchangeServiceTest','-Dsurefire.failIfNoSpecifiedTests=false','test']
with (ROOT/'docker/local/logs/exchange-after-sales-tests.log').open('w') as log:r=subprocess.run(command,env=env,stdout=log,stderr=subprocess.STDOUT)
print('Focused exchange regression exit:',r.returncode);sys.exit(r.returncode)
