"""Run Quartz reporting tests without starting a live scheduler."""
import os, shutil, subprocess
from pathlib import Path
from local_db import ROOT, HERE
cache=Path.home()/'.cache/oms-local'
source=ROOT/'ruoyi-modules/ruoyi-job'
shutil.copytree(source/'src',cache/'source/ruoyi-modules/ruoyi-job/src',dirs_exist_ok=True)
shutil.copy2(source/'pom.xml',cache/'source/ruoyi-modules/ruoyi-job/pom.xml')
command=[str(ROOT/'docker/local/tools/apache-maven-3.9.9/bin/mvn'),'-B','-f',str(cache/'source/pom.xml'),'-Plocal-docker','-Dmaven.repo.local='+str(cache/'m2'),'-pl','ruoyi-modules/ruoyi-job','-am','-Dtest=FulfillmentScanTaskTest','-Dsurefire.failIfNoSpecifiedTests=false','test']
with (HERE/'logs/fulfillment-job-tests.log').open('w') as log:r=subprocess.run(command,env=dict(os.environ,JAVA_HOME='/usr/lib/jvm/java-17-openjdk-amd64'),stdout=log,stderr=subprocess.STDOUT)
print('Quartz scan tests exit:',r.returncode)
raise SystemExit(r.returncode)
