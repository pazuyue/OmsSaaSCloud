"""Run message regressions in a disposable schema, optionally with the real Java Agent."""
import os
import shutil
import subprocess
import sys
from pathlib import Path
import xml.etree.ElementTree as ET
from local_db import HERE, ROOT, ENV, execute, test_jdbc_url
from configure_messaging import install


def main():
    execute('CREATE DATABASE IF NOT EXISTS message_delivery_test CHARACTER SET utf8mb4')
    install('message_delivery_test')
    cache=Path.home()/'.cache/oms-local'
    shutil.copytree(ROOT/'oms-modules/oms-messaging/src',cache/'source/oms-modules/oms-messaging/src',dirs_exist_ok=True)
    agent='--agent' in sys.argv
    suites=['MessageDeliveryTest','MessageExceptionTest','MessageTracingTest']
    env=dict(os.environ,MESSAGE_TEST_URL=test_jdbc_url('message_delivery_test'),INVENTORY_TEST_PASSWORD=ENV['MYSQL_ROOT_PASSWORD'],JAVA_HOME='/usr/lib/jvm/java-17-openjdk-amd64',SW_AGENT_NAME='local::tracing-tests',SW_AGENT_COLLECTOR_BACKEND_SERVICES='127.0.0.1:11800',SW_AGENT_KEEP_TRACING='true',SW_LOGGING_OUTPUT='CONSOLE')
    command=[str(HERE/'tools/apache-maven-3.9.9/bin/mvn'),'-B','-f',str(cache/'source/pom.xml'),'-Plocal-docker','-Dmaven.repo.local='+str(cache/'m2'),'-pl','oms-modules/oms-messaging','-Dtest='+','.join(suites),'test']
    if agent:
        command.insert(-1,'-DargLine=-javaagent:'+str(HERE/'tracing/runtime/default/skywalking-agent.jar')+' -Dskywalking.test.agent=true')
    log=HERE/'logs'/('tracing-agent-tests.log' if agent else 'tracing-tests.log')
    with log.open('w') as output:
        result=subprocess.run(command,env=env,stdout=output,stderr=subprocess.STDOUT)
    if result.returncode:raise SystemExit('Tracing regressions failed: '+str(log))
    total=0
    for suite in suites:
        report=ET.parse(cache/f'source/oms-modules/oms-messaging/target/surefire-reports/TEST-com.oms.messaging.{suite}.xml').getroot().attrib
        assert all(int(report.get(key,0))==0 for key in ('failures','errors','skipped')),report
        total+=int(report['tests'])
    print('PASS tracing and message regressions:',total,'agent='+str(agent))


if __name__=='__main__':main()
