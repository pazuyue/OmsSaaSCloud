"""Retained, simulation-only acceptance for channel settings and automatic catalog binding."""
import json,sys,time,subprocess,urllib.parse
from pathlib import Path
from datetime import datetime
from playwright.sync_api import sync_playwright,expect
from verify_goods_workspace_ui import login,request,BASE
from test_multi_virtual_shipping import sql,quote,wait_for
OUT=Path(__file__).resolve().parents[2]/'outputs/channel-automation';CH='qm_oms_saas_channel';MANIFEST=OUT/'case.json'
def main():
 OUT.mkdir(parents=True,exist_ok=True);c=json.loads(MANIFEST.read_text('utf-8')) if MANIFEST.exists() else {'stamp':datetime.now().strftime('%Y%m%d%H%M%S')}
 def save():MANIFEST.write_text(json.dumps(c,ensure_ascii=False,indent=2),encoding='utf-8')
 save();token=login()
 def api(path,body=None,method=None):
  r=request(path,token=token,payload=body,method=method or ('POST' if body is not None else 'GET'));return r.get('data',r)
 def ready():
  try:return api('/channel/channelSettings/options')
  except (AssertionError,OSError):return None
 wait_for(ready,lambda r:r is not None,'Channel automation not ready',180)
 name='[自动商品验收] '+c['stamp'];c['sku']='AUTO'+c['stamp'];save()
 if not c.get('channel_id'):
  api('/channel/channel',{'channelName':name,'channelType':'TM','enabled':1,'toChannelEnabled':1,'syncEnabled':0,'mmodelType':1});c['channel_id']=api('/channel/channel/list?channelName='+urllib.parse.quote(name))['rows'][0]['channelId'];save()
 ch=c['channel_id'];platform=api('/channel/platform/shops/'+str(ch))
 if not platform.get('app_id'):
  source=json.loads((OUT.parent/'platform-products/case.json').read_text('utf-8'));api('/channel/platform/shops/'+str(ch)+'/binding',{'app_id':source['app_id'],'expected_shop_id':'9911'+str(ch)})
 api('/channel/platform/shops/'+str(ch)+'/simulate',{});assert api('/channel/platform/shops/'+str(ch))['simulation'] is True
 if not api('/goods/info/list?pageNum=1&pageSize=1',{'skuSn':c['sku']})['rows']:
  sample=api('/goods/info/list?pageNum=1&pageSize=1',{})['rows'][0];api('/goods/info',{'skuSn':c['sku'],'goodsSn':c['sku'],'goodsName':name,'categoryCode':sample['categoryCode'],'colorCode':sample['colorCode'],'sizeCode':sample['sizeCode'],'marketPrice':10,'validity':30,'isPackage':0,'isFd':0})
 for i in range(1,3):
  item='96'+c['stamp']+str(i);specs=[{'sku_id':'95'+c['stamp']+str(i)+'1','outer_id':c['sku'],'properties':'1:1','properties_name':'验收规格 A','quantity':10,'status':'normal'}]
  if i==1:specs.append({'sku_id':'95'+c['stamp']+'12','outer_id':'MISSING_'+c['stamp'],'properties':'1:2','properties_name':'待补充 OMS 商品','quantity':3,'status':'normal'})
  payload={'num_iid':item,'title':['自动商品（多规格）','自动商品（第二链接）'][i-1],'approve_status':'onsale' if i==1 else 'instock','modified':'2026-10-19 12:00:00','num':13,'skus':{'sku':specs}}
  sql("INSERT IGNORE INTO channel_product_sim(company_code,channel_id,item_id,payload) VALUES ('QM',"+str(ch)+","+quote(item)+","+quote(json.dumps(payload,ensure_ascii=False))+")",CH)
 policy=api('/channel/channelSettings/products/'+str(ch));assert policy['auto_pull']==policy['auto_bind']==policy['rematch_enabled']==1
 def scan():
  r=subprocess.run(['wsl.exe','--','docker','exec','oms-local-channel','curl','-fsS','-X','POST','-H','from-source: inner','http://localhost:9304/platformProducts/internal/scan-all'],capture_output=True,timeout=55);assert r.returncode==0;assert json.loads(r.stdout)['code']==200
 if not api('/channel/platformProducts/items?channelId='+str(ch))['rows']:
  sql("UPDATE channel_product_policy SET next_run_at=NOW() WHERE company_code='QM' AND channel_id="+str(ch),CH)
  for _ in range(12):
   scan();rows=api('/channel/platformProducts/items?channelId='+str(ch))['rows']
   if len(rows)==2 and sum(int(r['bound_count']) for r in rows)==2:break
   time.sleep(1)
 rows=api('/channel/platformProducts/items?channelId='+str(ch))['rows'];assert len(rows)==2,rows
 summary=api('/channel/platformProducts/summary?channelId='+str(ch));assert summary['auto_bound']==2 and summary['unbound']==1,summary
 first=next(x for x in rows if x['item_id'].endswith('1'));specs=api('/channel/platformProducts/items/'+str(ch)+'/'+first['item_id']+'/skus')['rows'];bound=next(x for x in specs if x['binding_state']=='BOUND');unbound=next(x for x in specs if x['binding_state']=='UNBOUND');assert bound['binding_source']=='AUTO' and unbound['match_issue']=='商家编码未匹配到OMS SKU'
 # Reconfirming the unchanged mapping is a no-op.
 d=api('/channel/platformProducts/'+str(bound['id']));api('/channel/platformProducts/'+str(bound['id'])+'/binding',{'action':'BIND','sku_sn':c['sku'],'binding_revision':d['binding_revision'],'reason':'[验收] 无变化核对'});assert api('/channel/platformProducts/'+str(bound['id']))['binding_revision']==d['binding_revision']
 logs=api('/channel/platform/log-center?channelId='+str(ch)+'&itemId='+first['item_id']+'&category=PRODUCT')['rows'];assert any(x['action']=='PRODUCT_DETAIL' and x['result']=='SUCCESS' for x in logs)
 payload_log=next(x for x in logs if x['entry_kind']=='SIMULATION' and x['interface_name']=='taobao.item.seller.get');payload=api('/channel/platform/log-center/'+str(payload_log['id'])+'/payload');assert first['item_id'] in payload['response_json'] and c['sku'] in payload['response_json'];assert 'PRIVATE_' not in json.dumps(payload)
 with sync_playwright() as p:
  browser=p.chromium.launch(headless=True);context=browser.new_context(viewport={'width':1700,'height':1150});context.add_cookies([{'name':'Admin-Token','value':token,'url':BASE}]);page=context.new_page();errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
  def shot(name):page.screenshot(path=str(OUT/name),full_page=True,animations='disabled')
  page.goto(BASE+'/oms-channel/settings?tab=products&channelId='+str(ch),wait_until='networkidle');expect(page.get_by_role('button',name='保存商品同步与绑定设置',exact=True)).to_be_visible();page.get_by_role('button',name='保存商品同步与绑定设置',exact=True).click();expect(page.get_by_text('商品同步与绑定设置已保存',exact=True)).to_be_visible();shot('settings-products.png')
  page.get_by_role('tab',name='店铺对接 · 店铺级',exact=True).click();expect(page.get_by_text('店铺业务开关',exact=True)).to_be_visible();expect(page.get_by_text('店铺授权',exact=False).last).to_be_visible();shot('settings-shop.png')
  page.get_by_role('tab',name='日志与提醒',exact=True).click();expect(page.get_by_role('button',name='保存日志设置',exact=True)).to_be_visible();shot('settings-logs.png')
  page.get_by_role('tab',name='平台应用 · 公司级',exact=True).click();expect(page.get_by_role('button',name='新增应用',exact=True)).to_be_visible();shot('settings-apps.png')
  page.get_by_role('tab',name='商品同步与绑定 · 店铺级',exact=True).click();page.get_by_role('button',name='查看平台商品',exact=True).click();expect(page.locator('.products h2')).to_be_visible();expect(page.get_by_text('有效绑定 1 / 2',exact=True).first).to_be_visible();shot('catalog.png')
  page.get_by_role('button',name='详情 / 规格',exact=True).nth(1).click();drawer=page.locator('.el-drawer:visible');expect(drawer.get_by_text('待补充 OMS 商品',exact=False).first).to_be_visible();drawer.get_by_role('button',name='查看 / 处理绑定',exact=True).last.click();binding=page.locator('.el-drawer:visible').last;expect(binding.get_by_text('商家编码未匹配到OMS SKU',exact=True).first).to_be_visible();binding.locator('input[placeholder="搜索 OMS 商品"]').fill(c['sku']);binding.get_by_role('button',name='搜索',exact=True).click();binding.get_by_role('button',name='选择',exact=True).click();expect(binding.get_by_text('编码不同，请核对绑定依据',exact=True)).to_be_visible();shot('binding-compare.png');binding.get_by_role('button',name='关闭',exact=True).click()
  drawer.get_by_role('tab',name='同步与交互记录',exact=True).click();drawer.locator('.el-table__body-wrapper tr').filter(has_text='查询平台商品库存').first.get_by_role('button',name='详情 / 报文',exact=True).click();report=page.locator('.el-drawer:visible').last;expect(report.get_by_text('商品平台交互与报文',exact=True)).to_be_visible();expect(report.locator('pre').last).to_contain_text(c['sku']);shot('platform-payload.png')
  assert not errors,errors;browser.close()
 c['checks']=['default automatic pull','exact automatic binding','multiple links share OMS SKU','unmatched reason','grouped items + lazy SKU pages','unchanged binding no-op','precise redacted platform payload','centralized settings','binding comparison UI'];c['summary']=summary;save();print('PASS channel automation API and browser acceptance; retained channel',ch,'SKU',c['sku'])
if __name__=='__main__':main()
