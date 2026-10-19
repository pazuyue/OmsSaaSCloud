"""Retained TEST-only stock synchronization: multi-link, pause, zero and intake guard."""
import json,time,subprocess,urllib.parse
from pathlib import Path
from datetime import datetime
from playwright.sync_api import sync_playwright,expect
from verify_goods_workspace_ui import login,request,BASE
from test_multi_virtual_shipping import sql,quote,wait_for
ROOT=Path(__file__).resolve().parents[2];OUT=ROOT/'outputs/platform-products';MANIFEST=OUT/'case.json';CH='qm_oms_saas_channel';INV='qm_oms_saas_inventory';ORDER='qm_oms_saas_order'
def main():
 OUT.mkdir(parents=True,exist_ok=True)
 c=json.loads(MANIFEST.read_text('utf-8')) if MANIFEST.exists() else {'stamp':datetime.now().strftime('%Y%m%d%H%M%S')}
 def save():MANIFEST.write_text(json.dumps(c,ensure_ascii=False,indent=2),encoding='utf-8')
 save();token=login()
 def api(path,body=None):
  r=request(path,token=token,payload=body,method='POST' if body is not None else 'GET');return r.get('data',r)
 def inventory_receive(body):
  r=subprocess.run(['wsl.exe','--','docker','exec','-i','oms-local-inventory','curl','-fsS','-H','from-source: inner','-H','Content-Type: application/json','--data-binary','@-','http://localhost:9303/api/WmsInventory/addInventory?company_code=QM'],input=json.dumps(body).encode(),capture_output=True,timeout=45);assert r.returncode==0,'Inventory service unavailable';v=json.loads(r.stdout);assert v['code']==200,v.get('msg')
 def detail():return api('/channel/stockSync/'+str(c['group_id']))
 def scan():
  r=subprocess.run(['wsl.exe','--','docker','exec','oms-local-channel','curl','-fsS','-X','POST','-H','from-source: inner','http://localhost:9304/stockSync/internal/scan-all'],capture_output=True,timeout=60)
  assert r.returncode==0,'Stock scanner unavailable';v=json.loads(r.stdout);assert v['code']==200,v.get('msg')
 def run():
  # Dedicated fixture progression: no platform orders exist for this TEST-only shop.
  sql("UPDATE oms_order_pull_config SET enabled=1,cursor_time=NOW(),last_error='',next_run_at=DATE_ADD(NOW(),INTERVAL 1 DAY) WHERE company_code='QM' AND channel_id="+str(c['channel_id']),ORDER)
  api('/channel/stockSync/'+str(c['group_id'])+'/run',{'revision':detail()['revision']});scan();return detail()
 def quantities():return [int(v.split('\t')[1]) for v in sql("SELECT item_id,quantity FROM channel_stock_sync_sim WHERE company_code='QM' AND channel_id="+str(c['channel_id'])+" ORDER BY item_id",CH).splitlines()]
 def ready():
  try:return api('/channel/stockSync/options')
  except (AssertionError,OSError):return None
 wait_for(ready,lambda r:r is not None and 'shops' in r,'Stock sync not ready',180)
 name='[商品绑定验收] '+c['stamp'];c['sku']='BIND'+c['stamp'];save()
 if not c.get('channel_id'):
  existing=sql("SELECT channel_id FROM t_channel WHERE company_code='QM' AND channel_name="+quote(name)+" ORDER BY channel_id DESC LIMIT 1",CH)
  if existing:c['channel_id']=int(existing);save()
 if not c.get('channel_id'):
  api('/channel/channel',{'channelName':name,'channelType':'TM','enabled':1,'toChannelEnabled':1,'syncEnabled':1,'mmodelType':1})
  c['channel_id']=api('/channel/channel/list?channelName='+urllib.parse.quote(name))['rows'][0]['channelId'];save()
 ch=c['channel_id']
 if not c.get('app_id'):
  sql("INSERT INTO channel_platform_app(company_code,name,enabled,simulation_mode,article_code,item_code) VALUES ('QM',"+quote(name)+",1,1,'SIMULATED_SERVICE','SIMULATED_ITEM')",CH)
  c['app_id']=int(sql('SELECT id FROM channel_platform_app WHERE name='+quote(name)+' ORDER BY id DESC LIMIT 1',CH));save()
 binding=api('/channel/platform/shops/'+str(ch))
 if not binding.get('app_id'):api('/channel/platform/shops/'+str(ch)+'/binding',{'app_id':c['app_id'],'expected_shop_id':'9900'+str(ch)})
 api('/channel/platform/shops/'+str(ch)+'/simulate',{})
 assert api('/channel/platform/shops/'+str(ch))['simulation'] is True
 sample=api('/goods/info/list?pageNum=1&pageSize=1',{})['rows'][0]
 if not api('/goods/info/list?pageNum=1&pageSize=5',{'skuSn':c['sku']})['rows']:
  api('/goods/info',{'skuSn':c['sku'],'goodsSn':c['sku'],'goodsName':name,'categoryCode':sample['categoryCode'],'colorCode':sample['colorCode'],'sizeCode':sample['sizeCode'],'marketPrice':10,'validity':30,'isPackage':0,'isFd':0})
 if not c.get('received'):
  inventory_receive({'relationSn':'SYNC-IN-'+c['stamp'],'wmsInventoryBatch':{'companyCode':'QM','storeCode':'VC0001','skuSn':c['sku'],'batchCode':'SYNC-'+c['stamp'],'zpActualNumber':100,'cpActualNumber':0,'transactionPrice':1}})
  sql("INSERT INTO oms_channel_inventory(company_code,channel_id,sku_sn,quota_stock,stock_scope_json) VALUES ('QM',"+str(ch)+","+quote(c['sku'])+",100,'[\"VC0001\"]')",INV);c['received']=True;save()
 if not sql("SELECT id FROM oms_order_pull_config WHERE company_code='QM' AND channel_id="+str(ch),ORDER):
  api('/order/orders/configs',{'channel_id':ch,'source_key':'sysinfo113','seller_nick':name,'enabled':0,'auto_convert':0,'allowed_trade_types':['fixed'],'allow_public_stock':1,'interval_seconds':30,'initial_time':datetime.now().strftime('%Y-%m-%d %H:%M:%S')})
 # Simulated catalog represents platform reads; no orders are required to discover products.
 if not c.get('catalog_seeded'):
  for i in range(1,4):
   item='98'+c['stamp']+str(i);sku='97'+c['stamp']+str(i)
   payload={'num_iid':item,'title':['日常销售链接','活动销售链接','直播销售链接'][i-1],'approve_status':'instock' if i==3 else 'onsale','num':0,'skus':{'sku':[{'sku_id':sku,'outer_id':c['sku'],'properties':'1:'+str(i),'properties_name':'验收规格 '+str(i),'quantity':0,'status':'normal'}]}}
   sql("INSERT IGNORE INTO channel_product_sim(company_code,channel_id,item_id,payload) VALUES ('QM',"+str(ch)+","+quote(item)+","+quote(json.dumps(payload,ensure_ascii=False))+")",CH)
   sql("INSERT IGNORE INTO channel_stock_sync_sim(company_code,channel_id,item_id,sku_id,quantity) VALUES ('QM',"+str(ch)+","+quote(item)+","+quote(sku)+",0)",CH)
  c['catalog_seeded']=True;save()
 def product_scan():
  r=subprocess.run(['wsl.exe','--','docker','exec','oms-local-channel','curl','-fsS','-X','POST','-H','from-source: inner','http://localhost:9304/platformProducts/internal/scan-all'],capture_output=True,timeout=60);assert r.returncode==0;v=json.loads(r.stdout);assert v['code']==200,v.get('msg')
 if not c.get('catalog_pulled'):
  task=api('/channel/platformProducts/pull/'+str(ch),{'mode':'ALL'})
  for _ in range(8):
   product_scan();t=api('/channel/platformProducts/tasks/'+str(task['id']))
   if t['state']=='SUCCESS':break
  assert t['state']=='SUCCESS',t;c['catalog_pulled']=True;save()
 products=api('/channel/platformProducts?channelId='+str(ch))['rows'];assert len(products)==3
 if not c.get('group_id'):
  links=[]
  for i,p in enumerate(sorted(products,key=lambda p:p['item_id'])):
   assert p['binding_state'] in ['UNBOUND','BOUND']
   suggestions=api('/channel/platformProducts/'+str(p['id'])+'/suggestions');assert suggestions['can_suggest'] is True
   bound=api('/channel/platformProducts/'+str(p['id'])+'/binding',{'action':'BIND','sku_sn':c['sku'],'binding_revision':p['binding_revision'],'reason':'[验收] 核对商品规格后显式绑定'})
   links.append({'binding_id':bound['id'],'binding_revision':bound['binding_revision'],'name':['日常销售','活动销售','直播销售'][i],'weight':[60,30,10][i],'quantity_cap':None,'enabled':1})
  c['group_id']=api('/channel/stockSync',{'channel_id':ch,'sku_sn':c['sku'],'safety_stock':10,'quantity_cap':None,'enabled':0,'links':links})['id'];save()
 if not c.get('specified_pull_verified'):
  p=sorted(products,key=lambda p:p['item_id'])[0];revision=p['binding_revision'];task=api('/channel/platformProducts/pull/'+str(ch),{'mode':'IDS','item_ids':[p['item_id']]})
  for _ in range(5):
   product_scan();t=api('/channel/platformProducts/tasks/'+str(task['id']))
   if t['state']=='SUCCESS':break
  assert t['state']=='SUCCESS',t;assert api('/channel/platformProducts/'+str(p['id']))['binding_revision']==revision
  c['specified_pull_verified']=True;save()
 if not c.get('final_state'):
  # Reset only this dedicated TEST group's link participation; physical stock is never rewritten.
  d=detail()
  for l in d['links']:l['enabled']=1
  api('/channel/stockSync',d)
  d=run();assert d['state']=='SUCCESS',d['last_error'];assert quantities()==[54,27,9],quantities()
  before=int(sql("SELECT total_stock FROM oms_inventory WHERE company_code='QM' AND sku_sn="+quote(c['sku']),INV));assert before==100
  # Stop managing the third link. Its nine platform units remain part of the budget.
  d=detail();d['links'][2]['enabled']=0;api('/channel/stockSync',d);d=run();assert d['state']=='SUCCESS',d['last_error'];q=quantities();assert q[2]==9 and sum(q)==90,q
  # Zero its inventory before freeing the allocation share.
  api('/channel/stockSync/'+str(c['group_id'])+'/links/'+str(d['links'][2]['id'])+'/action',{'revision':d['revision'],'action':'ZERO_DISABLE','confirmed':True,'reason':'[验收] 清零退出直播链接，释放投放额度'})
  d=run();assert quantities()==[60,30,0],quantities()
  # Exited link is immutable. Add the existing binding as a new management relation.
  d=detail();b=api('/channel/platformProducts/bound?channelId='+str(ch)+'&skuSn='+c['sku'])[-1]
  d['links'].append({'binding_id':b['binding_id'],'binding_revision':b['binding_revision'],'name':'直播销售（重新加入）','weight':10,'quantity_cap':None,'enabled':1});api('/channel/stockSync',d)
  sql("UPDATE oms_order_pull_config SET cursor_time=DATE_SUB(NOW(),INTERVAL 10 MINUTE) WHERE company_code='QM' AND channel_id="+str(ch),ORDER)
  api('/channel/stockSync/'+str(c['group_id'])+'/run',{'revision':detail()['revision']});scan();d=detail();assert d['state']=='BLOCKED',d['last_error'];assert quantities()==[54,27,0],quantities()
  d=run();assert d['state']=='SUCCESS',d['last_error'];assert quantities()==[54,27,9]
  after=int(sql("SELECT total_stock FROM oms_inventory WHERE company_code='QM' AND sku_sn="+quote(c['sku']),INV));assert after==before
  logs=api('/channel/platform/log-center?category=STOCK&channelId='+str(ch)+'&pageSize=100')
  assert logs['rows'] and all(x['category']=='STOCK' and x['environment']=='SIMULATION' for x in logs['rows'] if x['entry_kind']!='CONFIG')
  # Query paths remain the same as the shared platform log center.
  c['quantities']=quantities();c['physical_stock_unchanged']=after;c['final_state']=d['state'];save()
 d=detail();before=after=c['physical_stock_unchanged']
 with sync_playwright() as p:
  browser=p.chromium.launch(headless=True);context=browser.new_context(viewport={'width':1640,'height':1100});context.add_cookies([{'name':'Admin-Token','value':token,'url':BASE}]);page=context.new_page();errors=[];page.on('pageerror',lambda e:errors.append(str(e)));page.goto(BASE+'/oms-inventory/platformStock?id='+str(c['group_id']),wait_until='networkidle');page.add_style_tag(content='*{animation:none!important;transition:none!important}')
  expect(page.get_by_text('库存同步组 · '+c['sku'],exact=True)).to_be_visible();expect(page.get_by_text('日常销售',exact=True).first).to_be_visible();page.screenshot(animations='disabled',path=str(OUT/'group.png'),full_page=True)
  drawer=page.locator('.el-drawer:visible');drawer.get_by_role('button',name='预览分配',exact=True).click();expect(drawer.get_by_text('未提交平台',exact=True)).to_be_visible();drawer.locator('.summary').scroll_into_view_if_needed();page.screenshot(animations='disabled',path=str(OUT/'preview.png'),full_page=True)
  page.goto(BASE+'/oms-channel/platformProducts?channelId='+str(ch),wait_until='networkidle');expect(page.locator('.products h2')).to_be_visible();expect(page.locator('.products .el-table__body-wrapper tr')).to_have_count(3);page.screenshot(animations='disabled',path=str(OUT/'catalog.png'),full_page=True)
  page.get_by_role('button',name='详情 / 规格',exact=True).first.click();details=page.locator('.el-drawer:visible');expect(details.get_by_text('平台商品详情',exact=True)).to_be_visible();details.get_by_role('button',name='查看 / 处理绑定',exact=True).first.click();binding=page.locator('.el-drawer:visible').last;expect(binding.get_by_text('当前绑定有效',exact=True)).to_be_visible();page.screenshot(animations='disabled',path=str(OUT/'binding.png'),full_page=True);binding.get_by_role('button',name='关闭',exact=True).click();details.get_by_role('button',name='库存同步',exact=True).first.click();expect(page.get_by_text('库存同步组 · '+c['sku'],exact=True)).to_be_visible()
  assert not errors,errors;browser.close()
 (OUT/'result.json').write_text(json.dumps({'group_id':c['group_id'],'sku':c['sku'],'quantities':quantities(),'stock_before':before,'stock_after':after,'state':d['state'],'checks':['catalog sale + warehouse','explicit binding','shared identity + revision','60/30/10','paused exposure','zero exit','stale intake blocks increases','simulation only','UI preview']},ensure_ascii=False,indent=2),encoding='utf-8')
 print('PASS TEST product catalog + explicit bindings + stock sync: three links 54/27/9, pause exposure, zero exit, stale-intake guard, physical stock unchanged, UI')
if __name__=='__main__':main()
