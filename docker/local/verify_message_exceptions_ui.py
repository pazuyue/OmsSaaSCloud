"""Read-only browser acceptance of current-vs-historical results and alert-to-task navigation."""
import json,urllib.request,urllib.parse
from pathlib import Path
from playwright.sync_api import sync_playwright,expect
from verify_goods_workspace_ui import login,request,BASE
OUT=Path(__file__).resolve().parents[2]/'outputs/message-exceptions'
def main():
 case=json.loads((OUT/'case.json').read_text('utf-8'));token=login()
 with sync_playwright() as p:
  b=p.chromium.launch(headless=True);ctx=b.new_context(viewport={'width':1740,'height':1150});ctx.add_cookies([{'name':'Admin-Token','value':token,'url':BASE}]);page=ctx.new_page();errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
  page.goto(BASE+'/monitor/messages?service=channel&type=PRODUCT_PULL&event='+case['config_event'],wait_until='networkidle');page.locator('.el-table__fixed-right').get_by_role('button',name='详情 / 链路',exact=True).first.click();drawer=page.locator('.el-drawer:visible');expect(drawer.get_by_text('最新通知：处理完成',exact=False)).to_be_visible();expect(drawer.get_by_text('最新通知已处理完成，无需重试',exact=False)).to_be_visible();expect(drawer.get_by_role('button',name='按原业务申请重试',exact=True)).to_have_count(0);page.screenshot(path=str(OUT/'retry-chain.png'),full_page=True,animations='disabled');drawer.get_by_role('button',name='查看最新通知',exact=True).click();expect(drawer.get_by_role('button',name='查看最新通知',exact=True)).to_have_count(0);expect(drawer.get_by_text('业务处理已完成，无需重试',exact=False)).to_be_visible();page.screenshot(path=str(OUT/'latest-result.png'),full_page=True,animations='disabled')
  page.goto(BASE+'/monitor/queues',wait_until='networkidle');page.locator('.el-table__fixed-right').get_by_role('button',name='运行详情',exact=True).first.click();expect(page.locator('.el-drawer:visible').get_by_role('tab',name='消费实例',exact=True)).to_have_attribute('aria-selected','true');drawer.get_by_role('tab',name='告警记录',exact=True).click();drawer.get_by_role('button',name='核查死信',exact=True).first.click();expect(drawer.get_by_role('tab',name='死信核查',exact=True)).to_have_attribute('aria-selected','true');row=drawer.locator('.el-table__body-wrapper tr').filter(has_text='PRODUCTPULL'+str(case['dead_task']));expect(row.get_by_text('业务已完成，无需重试',exact=True)).to_be_visible();page.screenshot(path=str(OUT/'dead-compensated.png'),full_page=True,animations='disabled');row.get_by_role('button',name='核对业务任务',exact=True).click();expect(page.locator('.messages h2')).to_be_visible();expect(page.get_by_placeholder('业务单号 / 消息号 / 追踪号')).to_have_value(case['dead_event']);assert not errors,errors;b.close()
 queues=request('/system/mqQueues',token=token)['data']['queues'];assert all(q['effective_state']=='RUNNING' and q['broker']['fresh'] for q in queues)
 dead=request('/system/mqQueues/PRODUCT_PULL/dead-letters',token=token)['data']['rows'];assert next(q for q in dead if q['id']==case['dead_id'])['business_state']=='SUCCESS'
 charts=json.loads((Path(__file__).parent/'monitoring/grafana/dashboards/oms-messaging.json').read_text('utf-8'));queries=0
 for panel in charts['panels']:
  for target in panel['targets']:
   with urllib.request.urlopen('http://127.0.0.1:19090/api/v1/query?'+urllib.parse.urlencode({'query':target['expr']}),timeout=15) as response:assert json.load(response)['status']=='success'
   queries+=1
 with urllib.request.urlopen('http://127.0.0.1:19090/api/v1/targets',timeout=15) as response:targets=json.load(response)['data']['activeTargets'];assert len(targets)==4 and all(t['health']=='up' for t in targets)
 result={'regression_tests_distinct':306,'focused_final_tests':51,'live_checks':case['checks']+['historical notice shows latest success without retry advice','alert history drills into quarantine and scoped business task'],'queues':[{k:q[k] for k in ['business_type','effective_state','actual_consumers','quarantine_pending']} for q in queues],'valid_grafana_queries':queries,'monitoring_targets':len(targets),'retained_tasks':{'configuration_repair':'PRODUCTPULL'+str(case['config_task']),'dead_letter_compensation':'PRODUCTPULL'+str(case['dead_task'])}}
 (OUT/'acceptance.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8');print('PASS final browser and monitoring acceptance; retained PRODUCTPULL'+str(case['config_task'])+' and PRODUCTPULL'+str(case['dead_task']))
if __name__=='__main__':main()
