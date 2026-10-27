"""Publish verified public-carrier services. Preserve existing data, jobs, queues and acceptance artifacts."""
import hashlib,json,os,shutil,subprocess
from pathlib import Path
from datetime import datetime
from local_db import ROOT


def main():
    cache=Path.home()/'.cache/oms-local/source'
    out=ROOT/'outputs/shared-carriers';tests=json.loads((out/'regression.json').read_text('utf-8'))
    assert tests['total_tests']>0 and all(not int(r[k]) for r in tests['suites'] for k in ['failures','errors','skipped'])
    for module in ['oms-common-core','oms-order','oms-supplychain','oms-inventory','oms-channel']:
        for source in (ROOT/'oms-modules'/module/'src/main').rglob('*'):
            if source.is_file():assert source.read_bytes()==(cache/source.relative_to(ROOT)).read_bytes(),str(source)
    history=[]
    for module in ['oms-order','oms-supplychain','oms-inventory','oms-channel']:
        source=cache/'oms-modules'/module/'target'/(module+'.jar')
        destination=ROOT/'docker/local/artifacts'/(module+'.jar')
        assert destination.resolve().is_relative_to((ROOT/'docker/local/artifacts').resolve())
        digest=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
        history.append(dict(module=module,previous_sha256=digest(destination),new_sha256=digest(source)))
        temporary=Path(str(destination)+'.next');shutil.copy2(source,temporary);os.replace(temporary,destination)
    path=out/'deployment.json';previous=json.loads(path.read_text('utf-8')) if path.exists() else []
    path.write_text(json.dumps(previous+[dict(time=datetime.now().isoformat(),artifacts=history)],indent=2),'utf-8')
    subprocess.run(['docker','compose','--env-file','docker/local/.env','-f','docker/local/compose.yaml','up','-d','--no-deps','--force-recreate','order','supplychain','inventory','channel'],cwd=ROOT,check=True)


if __name__=='__main__':main()
