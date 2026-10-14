"""Publish this deployment's generated settings to its local Nacos instance."""
import json
import yaml
import urllib.parse
import urllib.request
from pathlib import Path

configs = json.loads((Path(__file__).parent / 'generated/nacos.json').read_text(encoding='utf-8'))
for name, content in configs.items():
    if name in {service+'-dev.yml' for service in ['oms-order','oms-inventory','oms-supplychain','oms-channel','oms-modules-goods']}:
        config=yaml.safe_load(content)
        config.setdefault('oms',{}).setdefault('tenant',{}).setdefault('routes',{'QM':'master'})
        config['spring']['datasource']['dynamic']['strict']=True
        content=yaml.safe_dump(config,allow_unicode=True,sort_keys=False)
    data = urllib.parse.urlencode({'dataId': name, 'group': 'DEFAULT_GROUP', 'content': content, 'type': 'yaml' if name.endswith('.yml') else 'json'}).encode()
    with urllib.request.urlopen('http://localhost:8848/nacos/v1/cs/configs', data=data, timeout=15) as response:
        assert response.read() == b'true', 'Failed to publish ' + name
    print('Configured', name)
