"""Back up and unify only local OMS tenant routing; never changes jobs or business rows."""
import json,sys,subprocess
from pathlib import Path
from datetime import datetime
import yaml
from configure_order import nacos_get,nacos_set
from local_db import HERE,execute

SERVICES={'oms-order':'qm_oms_saas_order','oms-inventory':'qm_oms_saas_inventory','oms-supplychain':'qm_oms_saas_commodity','oms-channel':'qm_oms_saas_channel','oms-modules-goods':'qm_oms_saas_commodity'}

def main():
    assert sys.argv[1:] in ([],['--apply']),'Usage: configure_tenant_routing.py [--apply]'
    apply=sys.argv[1:]==['--apply'];planned=[]
    for service,database in SERVICES.items():
        name=service+'-dev.yml';original=nacos_get(name);config=yaml.safe_load(original)
        dynamic=config['spring']['datasource']['dynamic'];assert set(dynamic['datasource'])=={'master'},'Review non-local datasource configuration manually'
        assert database in str(dynamic['datasource']['master']),service+' unexpected business database'
        existing=config.get('oms',{}).get('tenant',{}).get('routes',{})
        assert not existing or existing=={'QM':'master'},'Preserve and review existing company registry'
        config.pop('oms.order.tenant-routes',None);config.pop('oms.wms.tenant-routes',None)
        config.setdefault('oms',{}).setdefault('tenant',{})['routes']={'QM':'master'}
        dynamic['strict']=True
        planned.append((service,name,original,yaml.safe_dump(config,allow_unicode=True,sort_keys=False)))
    folder=HERE/'backups'/('tenant-routing-'+datetime.now().strftime('%Y%m%d-%H%M%S'));folder.mkdir(parents=True)
    for service,name,original,updated in planned:(folder/name).write_text(original,encoding='utf8')
    # Also preserve modules not covered by the order/warehouse binary backup.
    if apply:
        for container,label in [('oms-local-goods','goods'),('oms-local-channel','channel')]:
            subprocess.run(['docker','cp',container+':/app/app.jar',str(folder/(label+'.jar'))],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        applied=[]
        try:
            for service,name,original,updated in planned:nacos_set(name,updated);applied.append((name,original))
        except Exception:
            for name,original in reversed(applied):nacos_set(name,original)
            raise
    print(json.dumps({'applied':apply,'services':[s for s,*_ in planned],'company_routes':{'QM':'master'},'strict':True,'backup':str(folder)},ensure_ascii=False))

if __name__=='__main__':main()
