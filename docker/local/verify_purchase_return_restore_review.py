"""Read-only acceptance on the user's retained CGT. Browser approval submissions are intercepted."""
import json
from pathlib import Path
from datetime import datetime
from urllib.parse import urlparse
from playwright.sync_api import sync_playwright,expect
from verify_goods_workspace_ui import login,request,BASE
from test_multi_virtual_shipping import sql,quote

ROOT=Path(__file__).resolve().parents[2];OUT=ROOT/'outputs/purchase-return-restore-review';RET='/supplychain/purchaseReturns';DB='qm_oms_saas_commodity'
def main():
 OUT.mkdir(exist_ok=True);state=json.loads((ROOT/'outputs/purchase-return-fulfillment/manifest.json').read_text('utf-8'));c=next(c for c in state['cases'] if c['return_sn']=='CGTa03ce6afb2ca42ed92aed4916ebd9633');token=login();checks=[];errors=[];posts=[];phase={'mode':'actual'}
 def api(p):return request(p,token=token)['data']
 def baseline():
  result={}
  for id in [c['return_id'],c['other_return_id']]:
   d=api(RET+'/'+str(id));result[str(id)]={k:d[k] for k in ['state','quantity','accepted_quantity','posted_quantity','control_version','execution_requests']};result[str(id)]['executions']=[e['id'] for e in d['executions']]
  r=request('/inventory/productInventory?skuSn='+c['sku'],token=token)['rows'][0];assert r['checkStatus']=='CONSISTENT';result['stock']={k:r[k] for k in ['totalStock','availableStock','allocatedStock','reservedStock','frozenStock']}
  result['warehouse_calls']=sql("SELECT COUNT(*),COALESCE(MAX(id),0) FROM wms_interaction_log WHERE company_code='QM' AND ticket_sn IN("+','.join(quote(api(RET+'/'+str(id))['return_sn']) for id in [c['return_id'],c['other_return_id']])+')',DB);return result
 before=baseline();d=api(RET+'/'+str(c['return_id']));r=d['execution_requests'][0];path=RET+'/'+str(c['return_id'])+'/restore/'+str(r['id'])+'/check';check=api(path);l=check['lines'][0]
 assert [l[k] for k in ['source_quantity','own_posted_quantity','other_claimed_quantity','source_available_quantity','required_quantity','source_shortage_quantity','batch_available_quantity']]==[10,2,7,1,3,2,1],l
 assert check['can_approve'] is False and l['claimants'][0]['return_id']==c['other_return_id'];checks.append('真实API明确原实收10、本单已退2、其他单占7、来源余额1、恢复需3、缺口2、批次可用1及占用单号')
 ready=json.loads(json.dumps(check));ready.update(source_ok=True,inventory_ok=True,can_approve=True,message='当前来源额度及批次库存满足本次恢复数量，审核后重新校验并锁定')
 for x in ready['lines']:x.update(other_claimed_quantity=0,other_posted_quantity=0,other_unexecuted_quantity=0,source_available_quantity=8,source_shortage_quantity=0,batch_available_quantity=8,claimants=[])
 for g in ready['inventory_groups']:g.update(available_quantity=8,shortage_quantity=0)
 with sync_playwright() as p:
  browser=p.chromium.launch(headless=True);context=browser.new_context(viewport={'width':1760,'height':1200});context.add_cookies([{'name':'Admin-Token','value':token,'domain':'127.0.0.1','path':'/'}]);context.set_default_timeout(25000)
  def guard(route):
   req=route.request;urlpath=urlparse(req.url).path
   if req.method=='GET' and urlpath=='/prod-api'+path:
    if phase['mode']=='unavailable':route.fulfill(status=200,json={'code':500,'msg':'核对服务暂不可用，请刷新重试'});return
    if phase['mode']=='ready':route.fulfill(status=200,json={'code':200,'data':ready});return
   if req.method in ['GET','OPTIONS']:route.continue_();return
   if req.method=='POST' and urlpath=='/prod-api'+RET+'/'+str(c['return_id'])+'/restore/'+str(r['id'])+'/approve':
    posts.append(dict(path=urlpath,body=req.post_data_json));phase['mode']='actual';route.fulfill(status=200,json={'code':500,'msg':check['message'],'data':check});return
   raise AssertionError('Unexpected mutation '+req.method+' '+urlpath)
  context.route('**/prod-api/**',guard);page=context.new_page();page.on('pageerror',lambda e:errors.append(str(e)))
  def dialog():return page.locator('.el-dialog__wrapper:visible').filter(has=page.get_by_text('审核恢复原计划余量',exact=True)).last
  def refresh():
   with page.expect_response(lambda x:urlparse(x.url).path=='/prod-api'+path):dialog().get_by_role('button',name='刷新额度与库存',exact=True).click()
  try:
   with page.expect_response(lambda x:urlparse(x.url).path=='/prod-api'+RET+'/'+str(c['return_id'])):page.goto(BASE+'/oms-supplychain/purchaseReturns?id='+str(c['return_id']))
   panel=page.locator('.return-detail:visible');panel.get_by_role('tab',name='仓库执行',exact=True).click()
   with page.expect_response(lambda x:urlparse(x.url).path=='/prod-api'+path):panel.get_by_role('button',name='审核恢复原计划余量',exact=True).click()
   form=dialog();expect(form.locator('.summary strong')).to_have_text(['3 件','1 件','2 件']);expect(form.locator('.source-numbers b')).to_have_text(['10 件','2 件','7 件','1 件','3 件','2 件']);expect(form.get_by_role('button',name='审核并恢复原计划余量',exact=True)).to_be_disabled();expect(page.locator('.el-message--error')).to_have_count(0);form.screenshot(path=str(OUT/'01-exact-restore-gap.png'));checks.append('窗口直接展示恢复3、可恢复1、缺口2；不足时审核按钮禁用，不弹出重复错误')
   link=form.get_by_role('link',name=l['claimants'][0]['return_sn'],exact=True);expect(link).to_have_attribute('target','_blank')
   with context.expect_page() as opened:link.click()
   other=opened.value;other.wait_for_url('**/purchaseReturns?id='+str(c['other_return_id']));expect(other.locator('.return-detail h2')).to_have_text(l['claimants'][0]['return_sn']);other.close();expect(form).to_be_visible();checks.append('占用单号可在新标签页打开原单详情，关闭后原恢复核对窗口保留')
   phase['mode']='unavailable';refresh();expect(form.locator('.el-alert--error')).to_have_count(1);expect(form.get_by_text('核对服务暂不可用，请刷新重试',exact=True)).to_be_visible();expect(page.locator('.el-message--error')).to_have_count(0);expect(form.get_by_role('button',name='审核并恢复原计划余量',exact=True)).to_be_disabled();checks.append('核对失败仅有一个窗口内错误，审核保持禁用，未把未知余额显示为0')
   phase['mode']='actual';refresh();expect(form.locator('.summary strong')).to_have_text(['3 件','1 件','2 件']);expect(form.locator('.el-alert--error')).to_have_count(0);checks.append('处理后可主动刷新来源和库存，刷新成功清除上次查询错误，不调用WMS')
   phase['mode']='ready';refresh();expect(form.get_by_role('button',name='审核并恢复原计划余量',exact=True)).to_be_enabled()
   with page.expect_response(lambda x:x.request.method=='POST' and urlparse(x.url).path.endswith('/restore/'+str(r['id'])+'/approve')):form.get_by_role('button',name='审核并恢复原计划余量',exact=True).click()
   expect(form.locator('.summary strong')).to_have_text(['3 件','1 件','2 件']);expect(form.locator('.el-alert--error')).to_have_count(1);expect(page.locator('.el-message--error')).to_have_count(0);expect(form.get_by_role('button',name='审核并恢复原计划余量',exact=True)).to_be_disabled();assert len(posts)==1;assert posts[0]['body']['control_version']==check['control_version'];form.screenshot(path=str(OUT/'02-concurrent-change-single-error.png'));checks.append('模拟打开窗口后额度被占用：提交失败自动刷新最新缺口，保留窗口且只显示一个错误；提交已拦截')
   assert not errors,errors
  except Exception:page.screenshot(path=str(OUT/'failure.png'));raise
  finally:browser.close()
 after=baseline();assert before==after,'Retained business data or warehouse calls changed';checks.append('本单与占用单的状态、数量、版本、执行和申请、SKU库存及仓库交互数量全部未变')
 result=dict(result='PASS',checked_at=datetime.now().isoformat(),checks=checks,source_check=check,before=before,after=after,javascript_errors=errors,intercepted_submissions=posts,note='原单及额度来自真实只读API；额度变化的提交响应在浏览器中模拟，后台锁内拒绝通过真实事务回归验证；未停止、释放或审核原验收单')
 (OUT/'acceptance.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),'utf-8');print('PASS',len(checks),'restore review checks; original CGT, occupying document, stock and WMS calls unchanged')

if __name__=='__main__':main()
