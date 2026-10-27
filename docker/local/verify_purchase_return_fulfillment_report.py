"""Verify the served report and render its preview without mutating business records."""
import json,shutil
from pathlib import Path
from datetime import datetime
from playwright.sync_api import sync_playwright
ROOT=Path(__file__).resolve().parents[2];OUT=ROOT/'outputs/purchase-return-fulfillment'

def main():
 state=json.loads((OUT/'manifest.json').read_text('utf-8'));tests=json.loads((OUT/'regression.json').read_text('utf-8'));ui=json.loads((OUT/'browser-check.json').read_text('utf-8'));query=json.loads((OUT/'query-check.json').read_text('utf-8'));errors=[]
 with sync_playwright() as p:
  browser=p.chromium.launch(headless=True);page=browser.new_page(viewport={'width':1720,'height':1150});page.on('pageerror',lambda e:errors.append(str(e)))
  response=page.goto('http://127.0.0.1:8088/acceptance/purchase-return-fulfillment/验收报告.html',wait_until='networkidle');assert response.status==200 and response.body()==(OUT/'验收报告.html').read_bytes()
  assert page.locator('section[id^="case-"]').count()==len(state['results'])==18
  assert page.locator('img').count()==14 and page.locator('img').evaluate_all('(images)=>images.every(i=>i.complete&&i.naturalWidth>0)')
  assert page.locator('a[href*="purchaseReturns?id="]').count()>=18
  metrics=page.locator('.metrics article').all_inner_texts();assert str(tests['total_tests']) in metrics[0] and str(len(ui['checks'])) in metrics[2] and str(len(query['checks'])) in metrics[3],metrics
  page.screenshot(path=str(OUT/'report-preview.png'));page.locator('#case-07').scroll_into_view_if_needed();page.screenshot(path=str(OUT/'report-stock-preview.png'));assert not errors,errors;browser.close()
 result=dict(result='PASS',backend_tests=tests['total_tests'],scenarios=18,browser_checks=len(ui['checks']),query_checks=len(query['checks']),images=14,served_content_matches=True,document_links_verified=True,javascript_errors=errors,checked_at=datetime.now().isoformat())
 (OUT/'report-check.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),'utf-8')
 dest=ROOT/'docker/local/dist/acceptance/purchase-return-fulfillment'
 for name in ['report-preview.png','report-stock-preview.png','report-check.json']:shutil.copy2(OUT/name,dest/name)
 print('PASS served report: 18 detailed scenarios, 14 images, current test counts, document links and browser render')

if __name__=='__main__':main()
