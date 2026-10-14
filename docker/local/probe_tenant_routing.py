"""Read-only internal probes: an unregistered company must never fall back to primary."""
import json,subprocess
from pathlib import Path
from configure_order import nacos_get
import yaml
results=[]
for container,port,service in [('order',9305,'oms-order'),('inventory',9303,'oms-inventory'),('supplychain',9302,'oms-supplychain'),('channel',9304,'oms-channel'),('goods',9301,'oms-modules-goods')]:
    config=yaml.safe_load(nacos_get(service+'-dev.yml'));assert config['oms']['tenant']['routes']=={'QM':'master'} and config['spring']['datasource']['dynamic']['strict'] is True
    result=subprocess.run(['docker','exec','oms-local-'+container,'curl','-sS','--max-time','10','-H','from-source: inner','-w','\n%{http_code}','http://localhost:'+str(port)+'/orderCatalog/internal/channels?company_code=UNREGISTERED'],capture_output=True,text=True)
    assert result.returncode==0
    body,status=result.stdout.rsplit('\n',1);assert status=='503' and '公司未配置明确数据源映射' in json.loads(body)['msg']
    results.append({'service':service,'unregistered_company_http_status':int(status),'strict_configured':True})
out=Path(__file__).resolve().parents[2]/'outputs/tenant-routing/internal-probes.json';out.write_text(json.dumps(results,ensure_ascii=False,indent=2),encoding='utf8')
print('PASS all five services reject unregistered internal company; no database fallback')
