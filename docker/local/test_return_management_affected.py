"""Re-run the return query suites and package current sources after the full regression."""
import json
import os
import shutil
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path
from local_db import ROOT,HERE,ENV,test_jdbc_url


def main():
    from configure_return_document_detail import install
    install('order_workspace_test','purchase_workspace_test')
    cache=Path.home()/'.cache/oms-local';modules=['oms-modules/oms-order','oms-modules/oms-supplychain'];names=['ReturnManagementQueryTest','ReturnQueryWarehouseTest','ReturnDocumentReadTest','TenantRoutingTest','WmsLogQueryTest','SharedReturnParcelsTest','SharedReturnDispatchTest']
    for module in modules:shutil.copytree(ROOT/module/'src',cache/'source'/module/'src',dirs_exist_ok=True)
    env=dict(os.environ,ORDER_TEST_URL=test_jdbc_url('order_workspace_test'),PURCHASE_TEST_URL=test_jdbc_url('purchase_workspace_test'),INVENTORY_TEST_PASSWORD=ENV['MYSQL_ROOT_PASSWORD'],JAVA_HOME='/usr/lib/jvm/java-17-openjdk-amd64')
    with (HERE/'logs/return-management-affected.log').open('w') as log:
        result=subprocess.run([str(HERE/'tools/apache-maven-3.9.9/bin/mvn'),'-B','-f',str(cache/'source/pom.xml'),'-Plocal-docker','-Dmaven.repo.local='+str(cache/'m2'),'-pl',','.join(modules),'-am','-Dtest='+','.join(names),'-Dsurefire.failIfNoSpecifiedTests=false','package'],env=env,stdout=log,stderr=subprocess.STDOUT)
    if result.returncode:raise SystemExit('Affected tests/package failed; see return-management-affected.log')
    reports=[]
    for module in modules:
        for report in (cache/'source'/module/'target/surefire-reports').glob('TEST-*.xml'):
            values=ET.parse(report).getroot().attrib
            if values['name'].split('.')[-1] in names:
                assert all(int(values.get(k,0))==0 for k in ['failures','errors','skipped']);reports.append({k:values[k] for k in ['name','tests','failures','errors','skipped']})
    assert len(reports)==len(names)
    total=sum(int(x['tests']) for x in reports)
    (ROOT/'outputs/return-management/regression-affected.json').write_text(json.dumps({'suites':reports,'total_tests':total},indent=2))
    print('PASS final affected tests',total)


if __name__=='__main__':main()
