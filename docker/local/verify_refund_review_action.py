"""Open/cancel the retained pending-refund review UI without adopting a plan or paying."""
import copy
import json
from pathlib import Path
from playwright.sync_api import sync_playwright, expect
from verify_goods_workspace_ui import BASE, login, request

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'outputs/refund-review-action'


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    manifest = json.loads((ROOT / 'outputs/after-ship-refund-auto/retest/manifest.json').read_text('utf-8'))
    ref = manifest['cases']['02B']['refunds']['target']
    token = login()
    detail_path = '/order/refunds/business/' + str(ref['id'])
    before = request(detail_path, token=token)['data']
    assert before['review_state'] == 'PENDING', 'This UI case must remain unapproved'
    user = request('/system/user/getInfo', token=token)
    errors, writes, checks = [], [], []
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)

        def opened(readonly=False, fixture=None):
            ctx = browser.new_context(viewport={'width': 1640, 'height': 900})
            ctx.add_cookies([{'name': 'Admin-Token', 'value': token, 'url': BASE}])
            if readonly:
                info = copy.deepcopy(user)
                info['permissions'] = ['order:refund:list', 'order:refund:query', 'order:gift:query', 'order:query']
                ctx.route('**/prod-api/system/user/getInfo', lambda route: route.fulfill(json=info))
            if fixture:
                ctx.route('**/prod-api' + detail_path, lambda route: route.fulfill(json={'code': 200, 'data': fixture}))
            page = ctx.new_page(); page.set_default_timeout(30000)
            page.on('pageerror', lambda e: errors.append(str(e)))
            page.on('request', lambda q: writes.append(q.url) if '/prod-api/' in q.url and q.method not in ['GET', 'HEAD', 'OPTIONS'] else None)
            page.goto(BASE + '/oms-order/businessRefunds?id=' + str(ref['id']), wait_until='networkidle')
            dialog = page.get_by_role('dialog', name='业务退款详情 · 仅退款', exact=True)
            expect(dialog.locator('.refund-identity-main > strong')).to_have_text(ref['refund_sn'])
            return ctx, page, dialog

        ctx, page, dialog = opened()
        for scope in ['.refund-progress .progress-heading', '.refund-footer']:
            dialog.locator(scope).get_by_role('button', name='核对审核方案', exact=True).click()
            review = page.get_by_role('dialog', name='审核仅退款方案', exact=True)
            expect(review).to_be_visible()
            expect(review.get_by_role('button', name='保存审核方案', exact=True)).to_be_disabled()
            expect(review.get_by_label('已核对用途、货物处理及本次平台版本')).not_to_be_checked()
            page.screenshot(path=str(OUT / ('top-review.png' if 'progress-heading' in scope else 'footer-review.png')), animations='disabled')
            review.get_by_role('button', name='取消', exact=True).click()
            expect(review).not_to_be_visible()
            checks.append(scope + ': opens review directly, requires confirmation and cancels without saving')
        dialog.locator('.refund-progress').get_by_role('button', name='展开完整进度', exact=True).click()
        dialog.locator('.progress-track').first.click()
        expect(dialog.get_by_role('tab', name='审核与来源', exact=True)).to_have_attribute('aria-selected', 'true')
        expect(dialog.get_by_role('button', name='审核退款方案', exact=True)).to_be_in_viewport()
        expect(page.get_by_role('dialog', name='审核仅退款方案', exact=True)).not_to_be_visible()
        page.screenshot(path=str(OUT / 'approval-details-in-view.png'), animations='disabled')
        checks.append('Read-only detail navigation scrolls the selected content into the dialog viewport')
        ctx.close()

        stale = copy.deepcopy(before)
        stale.update(review_state='STALE', state='BLOCKED', approved_version=before['platform']['version'])
        ctx, page, dialog = opened(fixture=stale)
        dialog.locator('.refund-footer').get_by_role('button', name='核对审核方案', exact=True).click()
        review = page.get_by_role('dialog', name='审核仅退款方案', exact=True)
        expect(review).to_be_visible()
        expect(review.get_by_role('button', name='保存审核方案', exact=True)).to_be_disabled()
        review.get_by_role('button', name='取消', exact=True).click()
        checks.append('Stale-plan UI fixture opens re-review without submitting a replacement plan')
        ctx.close()

        ctx, page, dialog = opened(readonly=True)
        dialog.locator('.refund-footer').get_by_role('button', name='核对审核方案', exact=True).click()
        expect(dialog.get_by_role('tab', name='审核与来源', exact=True)).to_have_attribute('aria-selected', 'true')
        expect(page.get_by_role('dialog', name='审核仅退款方案', exact=True)).not_to_be_visible()
        expect(dialog.get_by_role('button', name='审核退款方案', exact=True)).to_have_count(0)
        page.screenshot(path=str(OUT / 'readonly-review-details.png'), animations='disabled')
        checks.append('Read-only permission fixture can view the plan but cannot open its editor')
        ctx.close(); browser.close()
    after = request(detail_path, token=token)['data']
    fields = ['review_state', 'review_by', 'approved_version', 'control_version', 'goods_state', 'funds_state', 'state']
    assert {f: before[f] for f in fields} == {f: after[f] for f in fields}, 'The UI smoke test changed the retained refund'
    assert before['events'] == after['events'], 'Opening or canceling review created a business event'
    assert not errors, errors
    assert not writes, writes
    (OUT / 'result.json').write_text(json.dumps({'refund_id': ref['id'], 'refund_sn': ref['refund_sn'], 'checks': checks, 'page_errors': errors, 'browser_write_requests': writes, 'refund_state_unchanged': True}, ensure_ascii=False, indent=2), 'utf-8')
    print('PASS direct review actions, scroll location, re-review and read-only permissions; no writes or refund changes')


if __name__ == '__main__':
    main()
