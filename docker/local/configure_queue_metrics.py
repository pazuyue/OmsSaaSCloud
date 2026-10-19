"""Patch only metric exposure in CURRENT service profiles, protected by CAS.
Never use locally generated snapshots as the source for online configuration.
"""
import copy,hashlib,json,time,yaml,urllib.request,urllib.parse
from local_db import HERE

def flat(x,prefix=''):
 result={}
 for key,value in x.items():
  if isinstance(value,dict):result.update(flat(value,prefix+key+'.'))
  else:result[prefix+key]=value
 return result
if __name__=='__main__':
 path=HERE/'generated/nacos.json';configs=json.loads(path.read_text('utf-8'));opener=urllib.request.build_opener(urllib.request.ProxyHandler({}));base='http://127.0.0.1:8848/nacos/v1/cs/configs';key='management.endpoints.web.exposure.include'
 for name in ['ruoyi-system-dev.yml','oms-channel-dev.yml','oms-order-dev.yml']:
  query=urllib.parse.urlencode({'dataId':name,'group':'DEFAULT_GROUP'});raw=opener.open(base+'?'+query,timeout=15).read().decode('utf-8');cfg=yaml.safe_load(raw);before=flat(cfg)
  if key in cfg or 'management' not in cfg:cfg[key]='health,info,prometheus'
  else:cfg.setdefault('management',{}).setdefault('endpoints',{}).setdefault('web',{}).setdefault('exposure',{})['include']='health,info,prometheus'
  after=flat(cfg);assert {k for k in set(before)|set(after) if before.get(k)!=after.get(k)}<={key},'Refusing unrelated configuration changes'
  content=yaml.safe_dump(cfg,allow_unicode=True,sort_keys=False)
  if before!=after:
   body=urllib.parse.urlencode({'dataId':name,'group':'DEFAULT_GROUP','content':content,'type':'yaml','casMd5':hashlib.md5(raw.encode()).hexdigest()}).encode();assert opener.open(base,data=body,timeout=15).read()==b'true','Configuration changed concurrently; no overwrite'
  for _ in range(10):
   verified=yaml.safe_load(opener.open(base+'?'+query,timeout=15).read().decode('utf-8'))
   if flat(verified)==after:break
   time.sleep(1)
  assert flat(verified)==after,'Metrics configuration has not propagated';configs[name]=yaml.safe_dump(verified,allow_unicode=True,sort_keys=False)
 path.write_text(json.dumps(configs,ensure_ascii=False,indent=2),encoding='utf-8');print('Patched only Prometheus exposure in three current service profiles; tenant and connection settings preserved')
