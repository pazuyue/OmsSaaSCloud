"""Linux-only internal receive helper, restricted to this isolated tenant-routing fixture."""
import json,subprocess,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
case=json.loads((ROOT/'outputs/tenant-routing/case.json').read_text(encoding='utf8'))
body=json.load(sys.stdin);line=body['wmsInventoryBatch']
assert case['sku'].startswith('ACCEPT_IMM_') and line['skuSn']==case['sku'] and line['companyCode']=='QM'
warehouse=next(w for w in case['warehouses'] if w['code']==line['storeCode'])
assert line['zpActualNumber']==warehouse['quantity'] and body['relationSn'].startswith('TEST-MULTI-IN-'+case['case_id']+'-V')
result=subprocess.run(['docker','exec','-i','oms-local-inventory','curl','-sS','--max-time','40','-H','Content-Type: application/json','-H','from-source: inner','--data-binary','@-','http://localhost:9303/api/WmsInventory/addInventory?company_code=QM'],input=json.dumps(body,ensure_ascii=False).encode(),capture_output=True)
if result.returncode:raise RuntimeError('Isolated internal inventory call failed')
reply=json.loads(result.stdout);assert reply.get('code')==200,reply.get('msg');print(json.dumps(reply,ensure_ascii=False))
