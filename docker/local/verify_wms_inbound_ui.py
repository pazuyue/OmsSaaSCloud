"""Local gateway + scheduler + inventory + browser acceptance against an HTTP WMS fixture."""
import time,json,hashlib,urllib.parse,urllib.request,subprocess
from datetime import datetime
from playwright.sync_api import sync_playwright,expect
from verify_goods_workspace_ui import login,request,BASE,LOGS
ROOT='/supplychain/purchaseWorkspace'

def main():
 token=login();prefix='PURUI'+str(int(time.time()))
 print('WMS acceptance fixture:',prefix,flush=True)
 def post(path,body=None):return request(path,body or {},token)
 def read(kind,key):return request(ROOT+'/'+kind+'/'+str(key),token=token)['data']
 def wait(predicate,label):
  for _ in range(35):
   value=predicate()
   if value:return value
   time.sleep(1)
  raise AssertionError('Timed out: '+label)
 try:
  connection=post('/supplychain/wmsIntegration/connections',dict(name=prefix+'对接',provider='JD_HUFU',api_version='1.0',environment='TEST',endpoint=(LOGS/'wms-http-endpoint.txt').read_text().strip(),app_key='UI-APP',customer_id='UI-CUSTOMER',secret='ui-test-secret',enabled=1))['data']
  configs=request('/supplychain/wmsIntegration/connections',token=token)['data'];config=next(c for c in configs if c['id']==connection)
  assert 'secret_cipher' not in config and 'secret' not in config
  def master(kind,body):return post('/supplychain/'+kind,body)['data']
  owner=master('owner',dict(ownerCode=prefix+'-O',ownerName=prefix+'货主',isEnable=2))
  real=master('realStore',dict(realStoreCode=prefix+'-W',wmsName=prefix+'实仓',status=2,wmsType=1))
  relation=master('ownerWarehouse',dict(ownerId=owner,realStoreId=real,status=2))
  virtual=master('simulationStore',dict(wmsSimulationCode=prefix+'-V',wmsSimulationName=prefix+'真实入库虚仓',ownerWarehouseId=relation,status=2,inboundMode=1,outboundMode=2,connectionId=connection,externalWarehouse='UI-W',externalOwner='UI-O'))
  master('simulationStore',dict(wmsSimulationCode=prefix+'-V2',wmsSimulationName=prefix+'虚拟入库虚仓',ownerWarehouseId=relation,status=2,inboundMode=2,outboundMode=2))
  catalog=request('/goods/info/options',token=token)['data'];sku=prefix+'-SKU'
  post('/goods/info',dict(skuSn=sku,goodsSn=prefix,goodsName=prefix+'商品',marketPrice=10,validity=30,categoryCode=next(c['id'] for c in catalog['category'] if c['level']==3),colorCode=catalog['color'][0]['id'],sizeCode=catalog['size'][0]['id']))
  supplier=post(ROOT+'/supplier',dict(supplierName=prefix+'供应商',companyName=prefix,status=2))['data']
  po=post(ROOT+'/purchase',dict(poName=prefix+'采购',supplierSn=read('supplier',supplier)['supplierSn'],wmsSimulationCode=prefix+'-V',lines=[dict(skuSn=sku,quantity=5,purchasePrice=2)]))['data']
  post(ROOT+f'/purchase/{po}/approve');n=post(ROOT+f'/purchase/{po}/receipt',dict(lines=[dict(lineId=read('purchase',po)['lines'][0]['id'],quantity=5)]))['data']
  t=post(ROOT+f'/receipt/{n}/approve')['data']
  def task():return request(f'/supplychain/wmsIntegration/tickets/{t}',token=token)['data']
  wait(lambda:task()['dispatch_state']=='ACCEPTED','scheduled dispatch')
  assert json.loads((LOGS/'wms-http-last-request.json').read_text(encoding='utf-8'))['valid']
  assert read('purchase',po)['numberActually']==0
  ticket=read('ticket',t)
  body=json.dumps(dict(entryOrder=dict(entryOrderCode=ticket['sn'],entryOrderId='UI-EXT-ORDER',outBizCode=prefix+'-M1',ownerCode='UI-O',warehouseCode='UI-W',status='FULFILLED',confirmType='0',totalOrderLines=1),orderLines=[dict(itemCode=sku,ownerCode='UI-O',actualQty=5,batchs=[dict(batchCode=prefix+'-B1',inventoryType='ZP',actualQty=3),dict(batchCode=prefix+'-B2',inventoryType='CC',actualQty=2)])]),ensure_ascii=False,separators=(',',':'))
  params=dict(app_key='UI-APP',customerId='UI-CUSTOMER',method='jingdong.hufu.entryorder.confirm',v='1.0',sign_method='md5',format='json',timestamp=datetime.now().strftime('%Y-%m-%d %H:%M:%S'))
  params['sign']=hashlib.md5(('ui-test-secret'+''.join(k+v for k,v in sorted(params.items()))+body+'ui-test-secret').encode()).hexdigest().upper()
  def callback(p):
   url=BASE+'/prod-api/supplychain/wmsCallback/'+config['company_code'].lower()+'/'+config['callback_key']+'?'+urllib.parse.urlencode(p)
   req=urllib.request.Request(url,data=body.encode(),headers={'Content-Type':'application/json'},method='POST')
   with urllib.request.urlopen(req,timeout=20) as response:return json.load(response)
  response=callback(dict(params,sign='INVALID'));assert response.get('flag')=='failure',response
  response=callback(params);assert response.get('flag')=='success',response
  response=callback(params);assert response.get('flag')=='success',response
  try:wait(lambda:read('receipt',n)['noState']==4,'callback stock posting')
  except AssertionError:
   print('Posting diagnostics:',read('ticket',t),flush=True);raise
  assert read('purchase',po)['numberActually']==5 and read('purchase',po)['poState']==4
  subprocess.run(['wsl','-d','Ubuntu-24.04','--','python3','/mnt/d/Users/yueguang/OmsSaaSCloud/docker/local/verify_wms_stock.py',prefix],check=True)
  logs=request('/supplychain/wmsIntegration/logs',token=token)['data']
  assert any(l['direction']=='IN' and l['result']=='FAILED' and l['connection_id']==connection for l in logs)
  with sync_playwright() as p:
   browser=p.chromium.launch(headless=True);context=browser.new_context(viewport={'width':1536,'height':1000});context.add_cookies([{'name':'Admin-Token','value':token,'url':BASE}]);page=context.new_page();page.set_default_timeout(25000);errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
   page.goto(BASE+'/oms-supplychain/simulationStore?ownerId='+str(owner),wait_until='networkidle')
   expect(page.locator('.warehouse-workspace .el-table__body-wrapper tr')).to_have_count(2)
   expect(page.locator('.warehouse-workspace')).to_contain_text('WMS 回传')
   expect(page.locator('.warehouse-workspace')).to_contain_text('自动虚拟')
   page.screenshot(path=str(LOGS/'wms-virtual-modes.png'),full_page=True,animations='disabled')
   page.get_by_role('button',name='仓库对接配置',exact=True).click();expect(page.locator('.el-dialog:visible')).to_contain_text('京东虎符');page.screenshot(path=str(LOGS/'wms-connections-ui.png'),full_page=True,animations='disabled')
   page.locator('.el-dialog:visible').get_by_role('button',name='关闭',exact=True).click()
   page.goto(BASE+'/oms-supplychain/wmsTickets?sn='+ticket['sn'],wait_until='networkidle');page.get_by_role('button',name='详情',exact=True).last.click();drawer=page.locator('.el-drawer:visible')
   expect(drawer.locator('.wms-inbound-panel')).to_contain_text('收货完成');expect(drawer).to_contain_text('入账成功');page.screenshot(path=str(LOGS/'wms-inbound-completed.png'),full_page=True,animations='disabled')
   drawer.get_by_role('button',name='交互日志',exact=True).click();dialog=page.locator('.el-dialog:visible');expect(dialog).to_contain_text(ticket['sn']);page.screenshot(path=str(LOGS/'wms-interaction-logs.png'),full_page=True,animations='disabled')
   assert not errors,errors
   browser.close()
  print('PASS gateway signed callback, scheduled dispatch, inventory, deduplication and WMS UI',flush=True)
 finally:
  subprocess.run(['wsl','-d','Ubuntu-24.04','--','python3','/mnt/d/Users/yueguang/OmsSaaSCloud/docker/local/cleanup_purchase_ui.py',prefix],check=True)
  request('/auth/logout',{},token,method='DELETE')

if __name__=='__main__':main()
