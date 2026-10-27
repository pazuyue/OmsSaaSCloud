"""Publish verified goods/supplychain artifacts atomically before recreating only those services."""
import hashlib
import json
import os
import shutil
import subprocess
from pathlib import Path
from local_db import ROOT


def main():
    cache=Path.home()/'.cache/oms-local/source';out=ROOT/'outputs/supplychain-iteration';out.mkdir(exist_ok=True);history=[]
    for name in ['oms-common-core','oms-supplychain','oms-goods-administration']:
        for file in (ROOT/'oms-modules'/name/'src/main').rglob('*'):
            if file.is_file():assert file.read_bytes()==(cache/file.relative_to(ROOT)).read_bytes(),str(file)
    for name in ['oms-supplychain','oms-goods-administration']:
        source=cache/'oms-modules'/name/'target'/(name+'.jar');destination=ROOT/'docker/local/artifacts'/(name+'.jar');digest=lambda p:hashlib.sha256(p.read_bytes()).hexdigest();history.append({'module':name,'previous_sha256':digest(destination),'new_sha256':digest(source),'sources_match_cache':True});temporary=Path(str(destination)+'.next');shutil.copy2(source,temporary);os.replace(temporary,destination)
    path=out/'deployment.json';prior=json.loads(path.read_text()) if path.exists() else [];path.write_text(json.dumps(prior+history,indent=2));print('Verified artifacts copied before service restart',flush=True)
    subprocess.run(['docker','compose','--env-file','docker/local/.env','-f','docker/local/compose.yaml','up','-d','--no-deps','--force-recreate','goods','supplychain'],cwd=ROOT,check=True)


if __name__=='__main__':main()
