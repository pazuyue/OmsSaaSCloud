"""Back up only the local order/inventory deployment and UI before allocation rollout."""
import subprocess,shutil,json
from datetime import datetime
from local_db import HERE,docker_db
def main():
    folder=HERE/'backups'/('allocation-rules-'+datetime.now().strftime('%Y%m%d-%H%M%S'));folder.mkdir(parents=True)
    with (folder/'business.sql').open('wb') as out:docker_db('mysqldump','--single-transaction','--databases','qm_oms_saas_order','qm_oms_saas_inventory',output=out)
    for app in ['order','inventory']:subprocess.run(['docker','cp','oms-local-'+app+':/app/app.jar',str(folder/(app+'.jar'))],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    if (HERE/'dist').is_dir():shutil.copytree(HERE/'dist',folder/'dist')
    print(json.dumps({'backup':str(folder),'jobs_untouched':True}))
if __name__=='__main__':main()
