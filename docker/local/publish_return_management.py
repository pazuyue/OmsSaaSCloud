"""Publish verified local order/supplychain jars; source and queue configuration are untouched."""
import hashlib
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path
from local_db import ROOT


def main():
    source=Path.home()/'.cache/oms-local/source';out=ROOT/'outputs/return-management';record=out/'deployment.json'
    history=json.loads(record.read_text()) if record.exists() else []
    assert sys.argv[1:] in ([],['--order-only'])
    names=['oms-order'] if sys.argv[1:] else ['oms-order','oms-supplychain']
    for name in names:
        module='oms-modules/'+name
        for path in (ROOT/module/'src/main').rglob('*'):
            if path.is_file():assert path.read_bytes()==(source/path.relative_to(ROOT)).read_bytes(),str(path)
        jar=source/module/'target'/(name+'.jar');target=ROOT/'docker/local/artifacts'/(name+'.jar')
        digest=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
        history.append({'module':name,'previous_sha256':digest(target),'new_sha256':digest(jar),'sources_match_cache':True})
        temporary=Path(str(target)+'.next');shutil.copy2(jar,temporary);os.replace(temporary,target)
    record.write_text(json.dumps(history,indent=2));print('Verified artifacts copied before service recreation',flush=True)
    subprocess.run(['docker','compose','--env-file','docker/local/.env','-f','docker/local/compose.yaml','up','-d','--no-deps','--force-recreate']+[name.replace('oms-','') for name in names],cwd=ROOT,check=True)


if __name__=='__main__':main()
