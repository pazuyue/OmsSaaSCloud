"""Publish verified inventory/supplychain artifacts, preserving all existing local documents."""
import hashlib,json,os,shutil,subprocess
from pathlib import Path
from local_db import ROOT


def main():
    cache=Path.home()/'.cache/oms-local/source';out=ROOT/'outputs/purchase-returns';out.mkdir(exist_ok=True);history=[]
    for module in ['oms-common-core','oms-supplychain','oms-inventory','oms-order']:
        for f in (ROOT/'oms-modules'/module/'src/main').rglob('*'):
            if f.is_file():assert f.read_bytes()==(cache/f.relative_to(ROOT)).read_bytes(),str(f)
    for module in ['oms-supplychain','oms-inventory','oms-order']:
        src=cache/'oms-modules'/module/'target'/(module+'.jar');dst=ROOT/'docker/local/artifacts'/(module+'.jar');digest=lambda p:hashlib.sha256(p.read_bytes()).hexdigest();history.append(dict(module=module,previous_sha256=digest(dst),new_sha256=digest(src)));temporary=Path(str(dst)+'.next');shutil.copy2(src,temporary);os.replace(temporary,dst)
    p=out/'deployment.json';old=json.loads(p.read_text()) if p.exists() else [];p.write_text(json.dumps(old+history,indent=2));subprocess.run(['docker','compose','--env-file','docker/local/.env','-f','docker/local/compose.yaml','up','-d','--no-deps','--force-recreate','supplychain','inventory','order','wms-simulator'],cwd=ROOT,check=True)


if __name__=='__main__':main()
