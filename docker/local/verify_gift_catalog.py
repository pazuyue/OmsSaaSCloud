"""Local tag admission/reference acceptance. New marked fixtures only; no order or stock writes."""
import json
import urllib.request
from datetime import datetime
from pathlib import Path
from playwright.sync_api import sync_playwright, expect
from verify_goods_workspace_ui import BASE, login, opener

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'outputs/gift-catalog'


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    token = login()
    stamp = datetime.now().strftime('%Y%m%d%H%M%S')
    prefix = '[标签验收] ' + stamp
    case = {'name': prefix, 'checks': []}

    def api(path, body=None, method=None, ok=True):
        req = urllib.request.Request(BASE + '/prod-api' + path,
            data=json.dumps(body).encode() if body is not None else None, method=method,
            headers={'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'})
        with opener.open(req, timeout=60) as response:
            result = json.load(response)
        assert (result['code'] == 200) == ok, (path, result)
        return result.get('data', result) if ok else result

    def check(message):
        case['checks'].append(message)
        (OUT / 'case.json').write_text(json.dumps(case, ensure_ascii=False, indent=2), 'utf-8')
        print('PASS ' + message, flush=True)

    choices = api('/goods/info/options')
    category = next(row['id'] for row in choices['category'] if int(row['level']) == 3)
    products = {}
    for suffix, flag, bag in [('A', 0, 0), ('G', 1, 0), ('B', 1, 1)]:
        item = {'skuSn': 'GT' + stamp + suffix, 'goodsSn': 'GT' + stamp, 'goodsName': prefix + suffix,
            'categoryCode': category, 'colorCode': choices['color'][0]['id'], 'sizeCode': choices['size'][0]['id'],
            'marketPrice': 0, 'validity': 30, 'isGift': flag, 'isFd': bag, 'isPackage': 0}
        item['id'] = api('/goods/info', item)
        products[suffix] = item
    case['products'] = {key: {'id': item['id'], 'sku': item['skuSn']} for key, item in products.items()}
    sku = products['A']['skuSn']
    gift = products['G']['skuSn']
    search = {'field': 'goodsSn', 'keyword': 'GT' + stamp, 'pageNum': 1, 'pageSize': 20}
    candidates = api('/order/gifts/goods-search', dict(search, use='GIFT'))
    assert candidates['total'] == 1 and candidates['rows'][0]['skuSn'] == gift
    assert api('/order/gifts/goods-search', dict(search, use='PURCHASE'))['total'] == 3
    resolved = api('/order/gifts/goods-resolve', {'use': 'GIFT', 'skus': [sku, gift, products['B']['skuSn']]})
    assert not resolved[0]['valid'] and resolved[1]['valid'] and not resolved[2]['valid'], resolved
    assert '未勾选赠品' in resolved[0]['error'] and '实物' in resolved[2]['error']
    check('赠品搜索仅返回标签与实物资格同时有效的SKU，购买搜索不受赠品标签限制')
    body = {'name': prefix + ' 策略', 'enabled': 1, 'policy': {
        'start_time': '2026-10-01 00:00:00', 'end_time': '2026-11-01 00:00:00',
        'shipped_policy': 'RETURN', 'allow_waive': True, 'rules': [{
            'key': 'TAG', 'name': '标签准入', 'type': 'PRODUCT', 'amount': 0, 'minimum': 1, 'cap': 1,
            'max_count': 10, 'skus': [sku], 'gifts': [{'sku_sn': sku, 'quantity': 1}], 'tiers': [], 'stop': True}]}}
    assert '未勾选赠品' in api('/order/gifts/groups', body, ok=False)['msg']
    assert '未勾选赠品' in api('/order/gifts/simulate', {'name': body['name'], 'policy': body['policy'],
        'paid_time': '2026-10-20 12:00:00', 'lines': [{'sku_sn': sku, 'quantity': 1, 'payment': 100}]}, ok=False)['msg']
    body['policy']['rules'][0]['gifts'][0]['sku_sn'] = gift
    ident = api('/order/gifts/groups', body)['id']
    case['strategy_id'] = ident
    assert any(int(row['group_id']) == ident for row in api('/goods/info/' + str(products['G']['id']) + '/gift-references'))
    check('直接保存和试算均拒绝未标记赠品，启用策略在商品服务形成有效引用保护')
    try:
        blocked = api('/goods/info', dict(products['G'], isGift=0), method='PUT', ok=False)
        assert body['name'] in blocked['msg'], blocked
        assert body['name'] in api('/goods/info/' + str(products['G']['id']), method='DELETE', ok=False)['msg']
        assert int(api('/goods/info/' + str(products['G']['id']))['isGift']) == 1
        check('启用策略使用的商品不能取消赠品标签或删除，错误提示包含策略名称')
        with sync_playwright() as pw:
            browser = pw.chromium.launch(headless=True)
            context = browser.new_context(viewport={'width': 1500, 'height': 1100})
            context.add_cookies([{'name': 'Admin-Token', 'value': token, 'url': BASE}])
            page = context.new_page()
            page.set_default_timeout(20000)
            errors = []
            page.on('pageerror', lambda error: errors.append(str(error)))
            try:
                page.goto(BASE + '/oms-goods/info?skuSn=' + gift, wait_until='networkidle')
                page.locator('.app-container .el-table').get_by_role('button', name='修改', exact=True).first.click()
                dialog = page.locator('.el-dialog:visible')
                expect(dialog).to_contain_text('不改变该SKU正常销售时的订单身份')
                expect(dialog).to_contain_text(body['name'])
                name_field = dialog.locator('.el-form-item').filter(has=page.locator('label').filter(has_text='商品名称')).locator('input')
                name_field.fill('未保存的商品修改')
                with context.expect_page() as opened:
                    dialog.get_by_text('查看策略', exact=True).click()
                child = opened.value
                child.wait_for_url('**/oms-order/gift-editor/index?id=' + str(ident))
                child.close()
                expect(name_field).to_have_value('未保存的商品修改')
                page.screenshot(path=str(OUT / 'product-references.png'), full_page=True, animations='disabled')
                check('商品编辑展示标签用途和引用策略，新页面查看策略保留商品草稿')
                page.close()
                page = context.new_page()
                page.on('pageerror', lambda error: errors.append(str(error)))
                page.goto(BASE + '/oms-order/gift-editor/index', wait_until='networkidle')
                name_input = page.get_by_placeholder('例如：双十一满额赠礼', exact=True)
                name_input.fill('未保存的标签校验草稿')
                page.get_by_role('button', name='选择赠品商品', exact=True).click()
                dialog = page.locator('.el-dialog:visible')
                expect(dialog).to_contain_text('仅显示已勾选赠品')
                dialog.get_by_placeholder('输入商品名称或SKU').fill(prefix)
                with page.expect_response(lambda response: response.url.endswith('/gifts/goods-search')) as response:
                    dialog.get_by_role('button', name='查询', exact=True).click()
                assert response.value.json()['data']['total'] == 1
                expect(dialog.locator('.el-table__body-wrapper tbody tr')).to_have_count(1)
                expect(dialog.locator('.el-tag').filter(has_text='赠品')).to_be_visible()
                dialog.get_by_role('tab', name='批量粘贴SKU', exact=True).click()
                dialog.locator('textarea').fill(sku + '\n' + gift)
                dialog.get_by_role('button', name='校验SKU', exact=True).click()
                expect(dialog).to_contain_text('商品未勾选赠品，请先到商品资料设置')
                with context.expect_page() as opened:
                    dialog.get_by_role('button', name='设置赠品标签', exact=True).click()
                child = opened.value
                child.wait_for_url('**/oms-goods/info?skuSn=' + sku)
                child.close()
                expect(name_input).to_have_value('未保存的标签校验草稿')
                page.screenshot(path=str(OUT / 'picker.png'), full_page=True, animations='disabled')
                assert not errors, errors
                check('浏览器选品只显示已标记赠品，批量粘贴普通SKU有设置入口且保留策略草稿')
            except Exception:
                if not page.is_closed():
                    page.screenshot(path=str(OUT / 'failure.png'), full_page=True, animations='disabled')
                raise
            finally:
                context.close()
                browser.close()
    finally:
        current = next(row for row in api('/order/gifts/groups') if row['id'] == ident)
        current['enabled'] = 0
        api('/order/gifts/groups', current)
    assert not api('/goods/info/' + str(products['G']['id']) + '/gift-references')
    api('/goods/info', dict(products['G'], isGift=0), method='PUT')
    assert int(api('/goods/info/' + str(products['G']['id']))['isGift']) == 0
    current = next(row for row in api('/order/gifts/groups') if row['id'] == ident)
    current['enabled'] = 1
    assert '未勾选赠品' in api('/order/gifts/groups', current, ok=False)['msg']
    check('停用策略后可取消标签；取消后重新启用策略会被拒绝，未写订单或库存')


if __name__ == '__main__':
    main()
