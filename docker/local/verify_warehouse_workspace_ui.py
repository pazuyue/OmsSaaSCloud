"""Browser acceptance using temporary masters only; no inventory or business documents are created."""
import io
import json
import re
import time
import urllib.request
import urllib.error
import openpyxl
from playwright.sync_api import sync_playwright, expect
from verify_goods_workspace_ui import login, request, BASE, LOGS, opener


def main():
    token = login()
    prefix = 'WHUI' + str(int(time.time()))
    print('Temporary warehouse fixture scope:', prefix, flush=True)

    def create(kind, row):
        return request('/supplychain/' + kind, row, token)['data']

    def all_rows(kind):
        rows = []
        page = 1
        while True:
            response = request(f'/supplychain/{kind}/list?pageNum={page}&pageSize=100', token=token)
            rows.extend(response['rows'])
            if len(rows) >= response['total']:
                return rows
            page += 1

    def rejected(path, payload=None, method=None):
        req = urllib.request.Request(BASE + '/prod-api' + path, data=json.dumps(payload).encode() if payload is not None else None, headers={'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'}, method=method)
        try:
            response = opener.open(req, timeout=30)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            result = json.load(response)
        assert result['code'] != 200, path

    try:
        warehouses = [create('realStore', {'realStoreCode': prefix + '-W' + str(i), 'wmsName': prefix + ('广州仓' if i == 0 else '杭州仓'), 'status': 2, 'wmsType': 1, 'actualWarehouse': 1, 'director': '验收负责人', 'mobilePhone': '020-12345678', 'province': '广东省', 'city': '广州市', 'district': '天河区', 'address': '验收地址'}) for i in range(2)]
        owner = create('owner', {'ownerCode': prefix + '-O', 'ownerName': prefix + '多仓货主', 'isEnable': 2})
        relations = [create('ownerWarehouse', {'ownerId': owner, 'realStoreId': w, 'wmsOwnerCode': prefix + '-EXTERNAL', 'status': 2, 'isSync': 1}) for w in warehouses]
        virtual = create('simulationStore', {'wmsSimulationCode': prefix + '-V', 'wmsSimulationName': prefix + '固定虚仓', 'ownerWarehouseId': relations[0], 'status': 2})
        detail = request('/supplychain/simulationStore/' + str(virtual), token=token)['data']
        assert detail['realStoreCode'] == prefix + '-W0' and detail['ownerCode'] == prefix + '-O'
        rejected('/supplychain/simulationStore', dict(detail, ownerWarehouseId=relations[1]), 'PUT')
        rejected('/supplychain/ownerWarehouse/' + str(relations[0]), method='DELETE')
        rejected('/supplychain/owner/list?company_code=OTHER')
        assert request('/supplychain/realStore/list?status=-99', token=token)['total'] == 0
        assert request('/supplychain/realStore/list?actualWarehouse=-99', token=token)['total'] == 0
        assert request('/supplychain/realStore/list?director=NONEXISTENT_REVIEW', token=token)['total'] == 0
        page1 = request('/supplychain/realStore/list?pageNum=1&pageSize=1&ownerId=' + str(owner), token=token)
        page2 = request('/supplychain/realStore/list?pageNum=2&pageSize=1&ownerId=' + str(owner), token=token)
        assert page1['total'] == page2['total'] == 2 and page1['rows'][0]['id'] != page2['rows'][0]['id']
        print('PASS API ownership, filters, company scope and deletion guards', flush=True)
        with sync_playwright() as p:
            browser = p.chromium.launch(headless=True)
            context = browser.new_context(viewport={'width': 1536, 'height': 1000}, accept_downloads=True)
            context.add_cookies([{'name': 'Admin-Token', 'value': token, 'url': BASE}])
            page = context.new_page()
            page.set_default_timeout(20000)
            errors = []
            page.on('pageerror', lambda error: errors.append(str(error)))

            def goto(route, query=''):
                page.goto(BASE + '/oms-supplychain/' + route + query, wait_until='networkidle')
                expect(page.locator('.warehouse-workspace')).to_be_visible()

            def field(dialog, label):
                return dialog.locator('.el-form-item').filter(has=page.locator('.el-form-item__label').get_by_text(label, exact=True))

            def select(dialog, label, value):
                control = field(dialog, label).locator('input')
                control.click()
                control.fill(value)
                page.locator('.el-select-dropdown:visible .el-select-dropdown__item').filter(has_text=value).first.click()

            def mutation(kind):
                return page.expect_response(lambda r: r.request.method == 'POST' and r.url.split('?')[0].endswith('/supplychain/' + kind))

            goto('wmsRealStore', '?ownerId=' + str(owner))
            expect(page.locator('.warehouse-workspace .el-table__body-wrapper tr')).to_have_count(2)
            page.screenshot(path=str(LOGS / 'warehouse-physical-desktop.png'), full_page=True)
            page.locator('.warehouse-workspace .el-table').get_by_role('button', name='修改', exact=True).first.click()
            dialog = page.get_by_role('dialog', name='修改实体仓库', exact=True)
            expect(field(dialog, '实体仓库编码').locator('input')).to_be_disabled()
            expect(field(dialog, '启用状态').get_by_text('启用', exact=True)).to_be_visible()
            dialog.get_by_role('button', name='取消', exact=True).click()

            goto('owner', '?ownerCode=' + prefix + '-O')
            page.locator('.warehouse-workspace .el-table').get_by_role('button', name='关联仓库', exact=True).click()
            relation_dialog = page.get_by_role('dialog', name='关联实体仓库', exact=False)
            expect(relation_dialog.locator('.el-table__body-wrapper tr')).to_have_count(2)
            expect(relation_dialog.locator('.el-table__body-wrapper tr').filter(has_text=prefix + '-W0').get_by_role('button', name='解除', exact=True)).to_be_disabled()
            relation_dialog.screenshot(path=str(LOGS / 'warehouse-owner-relations.png'))
            relation_dialog.get_by_role('button', name='关闭', exact=True).click()
            page.get_by_role('button', name='新增货主').click()
            dialog = page.get_by_role('dialog', name='新增货主', exact=True)
            field(dialog, '货主编码').locator('input').fill(prefix + '-UI')
            field(dialog, '货主名称').locator('input').fill(prefix + '手工货主')
            with mutation('owner') as saved:
                dialog.get_by_role('button', name='保存货主', exact=True).click()
            ui_owner = saved.value.json()['data']
            relation_dialog = page.get_by_role('dialog', name='关联实体仓库', exact=False)
            ui_relations = []
            for i in range(2):
                relation_dialog.get_by_role('button', name='新增仓库关联').click()
                editor = page.get_by_role('dialog', name='新增仓库关联', exact=True)
                select(editor, '实体仓库', prefix + '-W' + str(i))
                with mutation('ownerWarehouse') as saved:
                    editor.get_by_role('button', name='保存关联', exact=True).click()
                ui_relations.append(saved.value.json()['data'])
                expect(relation_dialog.locator('.el-table__body-wrapper tr')).to_have_count(i + 1)
            relation_dialog.get_by_role('button', name='关闭', exact=True).click()
            print('PASS manual owner creation and two warehouse associations', flush=True)

            goto('simulationStore', '?ownerId=' + str(ui_owner))
            page.get_by_role('button', name='新增虚拟仓库').click()
            dialog = page.get_by_role('dialog', name='新增虚拟仓库', exact=True)
            field(dialog, '虚拟仓库编码').locator('input').fill(prefix + '-UIV')
            field(dialog, '虚拟仓库名称').locator('input').fill(prefix + '手工虚仓')
            control = field(dialog, '货主与实体仓库').locator('input')
            control.click()
            control.fill(prefix + '-UI')
            page.locator('.el-select-dropdown:visible .el-select-dropdown__item').filter(has_text=prefix + '-UI').filter(has_text=prefix + '-W0').click()
            expect(dialog.locator('.relation-summary')).to_contain_text(prefix + '-W0')
            with mutation('simulationStore') as saved:
                dialog.get_by_role('button', name='保存虚拟仓库', exact=True).click()
            assert saved.value.json()['code'] == 200
            expect(page.locator('.warehouse-workspace .el-table__body-wrapper tr')).to_have_count(1)
            expect(dialog).not_to_be_visible()
            page.screenshot(path=str(LOGS / 'warehouse-virtual-desktop.png'), full_page=True)
            page.locator('.warehouse-workspace .el-table').get_by_role('button', name='修改', exact=True).click()
            dialog = page.get_by_role('dialog', name='修改虚拟仓库', exact=True)
            expect(field(dialog, '货主与实体仓库').locator('input')).to_be_disabled()
            expect(field(dialog, '虚拟仓库编码').locator('input')).to_be_disabled()
            dialog.get_by_role('button', name='取消', exact=True).click()
            with page.expect_download() as downloaded:
                page.get_by_role('button', name='导出筛选结果').click()
            export = LOGS / 'warehouse-virtual-export.xlsx'
            downloaded.value.save_as(str(export))
            book = openpyxl.load_workbook(export)
            assert book.worksheets[0].max_row == 2
            assert prefix + '-W0' in [c.value for c in book.worksheets[0][2]]
            assert '启用' in [c.value for c in book.worksheets[0][2]]
            page.locator('.warehouse-workspace .el-table').get_by_role('button', name='库存', exact=True).click()
            page.wait_for_url(lambda url: '/oms-inventory/wmsInventory' in url and 'storeCode=' + prefix + '-UIV' in url)
            # Return through the SPA so the inventory page remains cached, then open another virtual warehouse.
            page.wait_for_load_state('networkidle')
            page.go_back(wait_until='networkidle')
            panel = page.locator('.warehouse-workspace .filter-panel')
            panel.locator('.filter-toggle').click()
            select(panel, '货主', prefix + '-O')
            panel.locator('.query-actions').get_by_role('button', name='搜索').click()
            expect(page.locator('.warehouse-workspace .el-table')).to_contain_text(prefix + '固定虚仓')
            expect(page.locator('.warehouse-workspace .el-table__body-wrapper tr')).to_have_count(1)
            with page.expect_response(lambda r: '/inventory/wmsInventory/list' in r.url and 'storeCode=' + prefix + '-V' in r.url) as stock_response:
                page.locator('.warehouse-workspace .el-table').get_by_role('button', name='库存', exact=True).click()
            assert stock_response.value.json()['code'] == 200
            print('PASS virtual ownership, immutable editor, export and inventory navigation', flush=True)

            goto('owner', '?ownerCode=' + prefix + '-UI')
            page.locator('.warehouse-workspace .el-table').get_by_role('button', name='关联仓库', exact=True).click()
            relation_dialog = page.get_by_role('dialog', name='关联实体仓库', exact=False)
            relation_dialog.locator('.el-table__body-wrapper tr').filter(has_text=prefix + '-W0').get_by_role('button', name='修改', exact=True).click()
            editor = page.get_by_role('dialog', name='修改仓库关联', exact=True)
            field(editor, '关联状态').get_by_text('停用', exact=True).click()
            editor.get_by_role('button', name='保存关联', exact=True).click()
            page.locator('.el-message-box').get_by_role('button', name=re.compile(r'确\s*定')).click()
            expect(editor).not_to_be_visible()
            relation_dialog.get_by_role('button', name='关闭', exact=True).click()
            goto('simulationStore', '?ownerId=' + str(ui_owner))
            expect(page.locator('.warehouse-workspace .el-table')).to_contain_text('关联停用')
            page.set_viewport_size({'width': 390, 'height': 844})
            page.locator('.warehouse-workspace .el-table').get_by_role('button', name=prefix + '手工虚仓', exact=True).click()
            drawer = page.locator('.el-drawer:visible')
            expect(drawer).to_contain_text('关联停用')
            drawer.screenshot(path=str(LOGS / 'warehouse-virtual-mobile-detail.png'))
            assert drawer.bounding_box()['width'] >= 389
            assert not errors, errors
            browser.close()
            print('PASS stop association, desktop/mobile detail and browser error checks', flush=True)
    finally:
        # Only records created under this run's unique prefix are removed.
        for row in all_rows('simulationStore'):
            if row['wmsSimulationCode'].startswith(prefix):
                request('/supplychain/simulationStore/' + str(row['id']), token=token, method='DELETE')
        owners = [r for r in all_rows('owner') if r['ownerCode'].startswith(prefix)]
        for owner in owners:
            for relation in request('/supplychain/ownerWarehouse/list?ownerId=' + str(owner['id']), token=token)['data']:
                request('/supplychain/ownerWarehouse/' + str(relation['id']), token=token, method='DELETE')
            request('/supplychain/owner/' + str(owner['id']), token=token, method='DELETE')
        for row in all_rows('realStore'):
            if row['realStoreCode'].startswith(prefix):
                request('/supplychain/realStore/' + str(row['id']), token=token, method='DELETE')
        request('/auth/logout', {}, token, method='DELETE')
        print('Temporary warehouse fixtures removed', flush=True)


if __name__ == '__main__':
    main()
