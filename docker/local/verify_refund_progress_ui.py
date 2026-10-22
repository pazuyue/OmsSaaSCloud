"""Read-only progress/platform UI acceptance; intermediate states use browser responses only."""
import copy
import json
import re
from pathlib import Path
from playwright.sync_api import sync_playwright, expect
from verify_merged_composite_gift_refunds import Acceptance
from verify_goods_workspace_ui import BASE, request

OUT = Path(__file__).resolve().parents[2] / 'outputs/refund-progress-ui'


def main():
    acceptance = Acceptance()
    OUT.mkdir(exist_ok=True)
    before = {'stock': acceptance.snapshot(), 'preceding': acceptance.protected_order()}
    (OUT / 'before.json').write_text(json.dumps(before, ensure_ascii=False, indent=2), 'utf-8')
    assert json.loads((OUT / 'before-deploy.json').read_text('utf-8')) == before, 'Deployment changed acceptance inventory'
    checks, errors, writes = [], [], []
    refund_id = acceptance.c['refunds']['kit']['id']
    base = '/order/refunds/business/' + str(refund_id)
    try:
        original = acceptance.api(base)
        assert original['platform_interaction']['execution_supported'] is False
        assert not original['platform_interaction']['operations']
        assert len(original['progress_context']['plans']) == 9
        assert original['platform_interaction']['observations']
        with sync_playwright() as playwright:
            browser = playwright.chromium.launch(headless=True)
            context = browser.new_context(viewport={'width': 1700, 'height': 1150})
            context.add_cookies([{'name': 'Admin-Token', 'value': acceptance.token, 'url': BASE}])

            def opened(row=None, gift_failure=False, width=1700):
                reads = []
                page = context.new_page()
                page.set_viewport_size({'width': width, 'height': 1150})
                page.set_default_timeout(30000)
                page.on('pageerror', lambda error: errors.append(str(error)))

                def observed(req):
                    if '/prod-api/order/' in req.url:
                        reads.append((req.method, req.url))
                        if req.method not in ['GET', 'HEAD', 'OPTIONS']:
                            writes.append((req.method, req.url))

                page.on('request', observed)
                if row is not None:
                    page.route('**/prod-api' + base, lambda route: route.fulfill(json={'code': 200, 'data': row}))
                if gift_failure:
                    page.route('**/prod-api/order/gifts/orders/' + str(original['order_id']), lambda route: route.fulfill(json={'code': 500, 'msg': 'UI fixture: gift report unavailable'}))
                page.goto(BASE + '/oms-order/businessRefunds?id=' + str(refund_id), wait_until='networkidle')
                dialog = page.get_by_role('dialog', name=re.compile('^业务退款详情'))
                expect(dialog.locator('.refund-progress .progress-track')).to_have_count(5)
                return page, dialog, reads

            page, dialog, reads = opened()
            progress = dialog.locator('.refund-progress')
            expect(progress.locator('.progress-main>strong')).to_have_text('本退款处理完成')
            expect(progress.locator('.progress-track').filter(has_text='关联赠品')).to_contain_text('7 件 + 1 套')
            page.screenshot(path=str(OUT / 'completed-progress.png'), full_page=True, animations='disabled')
            progress.locator('.progress-track').filter(has_text='平台处理').click()
            expect(dialog.get_by_role('tab', name='平台处理与资金', exact=True)).to_have_attribute('aria-selected', 'true')
            panel = dialog.locator('.refund-platform-progress')
            expect(panel).to_contain_text('需在平台处理')
            expect(panel).to_contain_text('平台已退款 ¥40.00')
            expect(panel.locator('.platform-facts article').nth(2)).to_contain_text('无OMS提交记录')
            expect(panel.locator('.platform-observations')).to_contain_text('退款成功')
            expect(panel.get_by_role('button', name='同意退款', exact=True)).to_have_count(0)
            panel.scroll_into_view_if_needed()
            page.screenshot(path=str(OUT / 'platform-results.png'), full_page=True, animations='disabled')
            panel.get_by_role('button', name='查看来源原文', exact=True).first.click()
            raw = page.get_by_role('dialog', name='平台推送原文', exact=True)
            expect(raw.locator('pre')).to_contain_text(original['refund_id'])
            assert any('/platform/' + str(original['platform_refund_id']) + '/snapshots/' in url for _, url in reads)
            page.close()
            checks.append('真实完成单：五项进度、赠品件套单位、平台同步来源和资金金额；原文使用平台退款ID')

            pending = copy.deepcopy(original)
            pending.update(state='PROCESSING', funds_state='NONE')
            pending['platform'].update(platform_status='WAIT_SELLER_AGREE', dispute_type='REFUND', refund_fee='40.00')
            page, dialog, _ = opened(pending)
            progress = dialog.locator('.refund-progress')
            expect(progress).to_contain_text('待平台同意退款')
            expect(progress.locator('.progress-main>strong')).to_have_text('平台仍待卖家处理')
            progress.get_by_role('button', name='查看平台处理', exact=True).first.click()
            expect(dialog.get_by_role('tab', name='平台处理与资金', exact=True)).to_have_attribute('aria-selected', 'true')
            expect(dialog.locator('.refund-platform-progress')).to_contain_text('本地审核不会向平台提交这些操作')
            page.screenshot(path=str(OUT / 'local-approved-platform-pending.png'), full_page=True, animations='disabled')
            page.close()
            checks.append('本地已审核、商品完成但平台仍待同意退款：显示平台待办，不显示整单完成或伪造已提交')

            for state, title in [('UNKNOWN', '平台操作结果待确认'), ('SUCCESS', '等待平台处理结果同步'), ('FAILED', '平台操作失败待处理')]:
                fixture = copy.deepcopy(pending)
                fixture['platform_interaction']['operations'] = [{'id': 99991, 'request_id': 'UI_ONLY_PLATFORM_COMMAND',
                    'action': 'AGREE', 'state': state, 'source_version': fixture['platform']['version'], 'platform_version': '1',
                    'fee': '40.00', 'actor': 'UI fixture', 'error_code': 'TEST' if state == 'FAILED' else '',
                    'result_message': 'UI only response fixture', 'create_time': fixture['reviewed_at'], 'reason': 'UI fixture'}]
                page, dialog, _ = opened(fixture)
                expect(dialog.locator('.progress-main>strong')).to_have_text(title)
                dialog.get_by_role('tab', name='平台处理与资金', exact=True).click()
                expect(dialog.locator('.platform-operation-records')).to_contain_text('同意退款')
                expect(dialog.locator('.refund-platform-progress')).to_contain_text('尚未确认退款成功')
                if state == 'UNKNOWN':
                    expect(dialog.locator('.refund-progress')).to_contain_text('避免重复提交')
                    page.screenshot(path=str(OUT / 'platform-unknown-result.png'), full_page=True, animations='disabled')
                page.close()
            checks.append('平台交互页面夹具：失败、结果不明、操作返回成功均独立于最终资金成功；无平台写请求')

            gift_pending = copy.deepcopy(original)
            gift_pending['gift_progress']['unposted_quantity'] = 1
            gift_pending['gift_progress']['posted_quantity'] -= 1
            for unit in gift_pending['gift_progress']['unit_progress']:
                if unit['physical_unit'] == '套':
                    unit['unposted_quantity'] = 1
                    unit['posted_quantity'] = 0
            gift_plan = next(p for p in gift_pending['progress_context']['plans'] if p.get('gift_plan_id') and p['stock_unit'] == '套')
            gift_plan['posted_quantity'] = 0
            page, dialog, reads = opened(gift_pending)
            progress = dialog.locator('.refund-progress')
            expect(progress.locator('.progress-main>strong')).to_have_text('平台已退款，关联赠品实收待入库')
            progress.get_by_role('button', name='去处理', exact=True).first.click()
            expect(dialog.get_by_role('tab', name='退货与入库', exact=True)).to_have_attribute('aria-selected', 'true')
            expect(dialog.locator('.plan-expanded')).to_have_count(1)
            expect(dialog.locator('.plan-expanded')).to_contain_text(gift_plan['sku_sn'])
            assert sum(url.endswith(base + '/goods') for method, url in reads if method == 'GET') == 1
            page.screenshot(path=str(OUT / 'gift-task-navigation.png'), full_page=True, animations='disabled')
            page.close()
            checks.append('已退款但赠品待入库：总状态不完成，待办直达原赠品安排，明细只加载一次')

            page, dialog, _ = opened(gift_failure=True)
            expect(dialog.locator('.progress-main>strong')).to_contain_text('赠品进度需重新核对')
            expect(dialog.locator('.refund-progress')).to_contain_text('赠品核对加载失败')
            page.close()
            checks.append('赠品加载失败：明确提示核对失败，不误判无赠品或全部完成')

            page, dialog, _ = opened(width=1280)
            expect(dialog.locator('.progress-main>strong')).to_have_text('本退款处理完成')
            assert dialog.evaluate('(el) => el.scrollWidth <= el.clientWidth + 2')
            page.screenshot(path=str(OUT / 'progress-1280.png'), full_page=True, animations='disabled')
            page.close()
            browser.close()
        after = {'stock': acceptance.snapshot(), 'preceding': acceptance.protected_order()}
        assert before == after, 'Read-only progress acceptance changed inventory'
        assert not errors, errors
        assert not writes, writes
        (OUT / 'result.json').write_text(json.dumps({'checks': checks, 'browser_errors': errors, 'business_writes': writes,
            'stock_unchanged': True, 'preceding_case_unchanged': True, 'refund_id': refund_id, 'after': after}, ensure_ascii=False, indent=2), 'utf-8')
        print('PASS refund progress/platform UI:', len(checks), 'scenarios; no business writes; stock unchanged')
    finally:
        request('/auth/logout', token=acceptance.token, method='DELETE')


if __name__ == '__main__':
    main()
