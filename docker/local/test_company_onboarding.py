"""Company lifecycle tests use only a disposable schema; no live companies or jobs changed."""
import os,subprocess,shutil,sys
from pathlib import Path
from local_db import execute,HERE,ROOT,ENV,test_jdbc_url
def main():
    execute('CREATE DATABASE IF NOT EXISTS company_onboarding_test CHARACTER SET utf8mb4')
    execute((HERE/'migrations/20261014_company_onboarding.sql').read_text(encoding='utf8'),'company_onboarding_test')
    from configure_order import column
    column('company_onboarding_test','sys_company','runtime_ready','TINYINT NOT NULL DEFAULT 0')
    column('company_onboarding_test','sys_company','last_detect_at','BIGINT NULL')
    cache=Path.home()/'.cache/oms-local'
    shutil.copytree(ROOT/'ruoyi-modules/ruoyi-system/src',cache/'source/ruoyi-modules/ruoyi-system/src',dirs_exist_ok=True)
    env=dict(os.environ,JAVA_HOME='/usr/lib/jvm/java-17-openjdk-amd64',COMPANY_TEST_URL=test_jdbc_url('company_onboarding_test'),INVENTORY_TEST_PASSWORD=ENV['MYSQL_ROOT_PASSWORD'])
    env['COMPANY_REDIS_TEST_HOST']=subprocess.check_output(['docker','inspect','--format','{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}','oms-local-redis'],text=True).strip()
    cmd=[str(HERE/'tools/apache-maven-3.9.9/bin/mvn'),'-B','-f',str(cache/'source/pom.xml'),'-Plocal-docker','-Dmaven.repo.local='+str(cache/'m2'),'-pl','ruoyi-modules/ruoyi-system','-am','-Dtest=CompanyOnboardingTest,CompanyProbeTest,CompanyStateStoreTest,CompanyUserAssignmentTest','-Dsurefire.failIfNoSpecifiedTests=false','test']
    with (HERE/'logs/company-tests.log').open('w') as log:result=subprocess.run(cmd,env=env,stdout=log,stderr=subprocess.STDOUT)
    print('Company onboarding tests exit:',result.returncode);sys.exit(result.returncode)
if __name__=='__main__':main()
