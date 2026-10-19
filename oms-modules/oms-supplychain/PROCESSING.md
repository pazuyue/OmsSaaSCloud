# Warehouse kit processing

Business entry: Inventory > Kit processing (`/oms-inventory/kitProcess`). Supports fixed single-level stocked kits, assembly and disassembly. Virtual kits continue to reserve and ship components.

## Identity and configuration

Publish `inventory_mode=STOCKED` from the goods composition drawer. The mode is immutable after publication; stocked composition is immutable too, avoiding different recipes under the same finished SKU. New recipes use new SKUs. Orders freeze a stocked composition snapshot but keep the parent as the actual stock/shipment line and record payment once.

Explicitly enable `storeprocess_enabled` on a verified Qimen 2.0 warehouse connection. `storeprocess_cancel` independently enables verified remaining-processing cancellation. No generic inbound/outbound API fallback is used.

## Warehouse contract

- Create: `taobao.qimen.storeprocess.create`, `orderType=CNJG`, `serviceType=1` assembly or `2` disassembly. Material and product lists follow the standard `<materialitems><item>...` / `<productitems><item>...` XML format.
- Confirm: `taobao.qimen.storeprocess.confirm` via the existing signed tenant/connection callback route. Each `outBizCode` identifies one incremental actual result; this contract must be validated with each WMS before enabling. Include original process code, warehouse process ID, warehouse, owner, actual finished units, actual material/product quantities, inventory types and batches.
- Requests retain the original process code and immutable XML on retry. Creation acknowledgement only means accepted; it does not post stock.
- Stop: verified connections may use `taobao.qimen.order.cancel` with `CNJG`; confirmed work remains, only unused held stock is released.

Official contracts: https://developer.alibaba.com/docs/doc.htm?articleId=118403&docType=1&treeId=285 and API IDs 27269 / 27270.

## Accounting and recovery

The inventory service serializes stock writes with the existing sorted company/SKU mutex, validates batch/warehouse balances and protects channel locks and public order claims. Approval holds specific good-stock input batches without changing physical totals. Each confirmed event consumes held inputs and receives outputs in one local transaction, retaining physical quantities, source costs, batch lineage and original composition. Damaged output increases frozen stock, never sellable stock.

Assembly stock costs come from actual input consumption. Disassembly requires a batch produced by this system and uses its stored component costs/recipe; disassembly of directly purchased finished kits is explicitly unavailable until a cost split basis is provided. Output batch costs use the current inventory table's two-decimal precision; process event results expose any rounding difference.

Supplychain retains receipt events before inventory calls. A stock commit followed by a failed local acknowledgement is recovered by retrying the original message. Duplicate identities with changed payload are rejected. Ratio differences require explicit human evidence; the original receipt is retained. Late receipts after stopping remain pending review. Explicit confirmation with evidence may recover only the original released input batches if still free; it cannot take stock held by channel allocations or other orders. It posts the original actual result once and keeps any remaining unprocessed quantity stopped.

Confirmed events generate paired `ASSEMBLY_OUT/IN` or `DISASSEMBLY_OUT/IN` inventory vouchers in the existing document center, with detailed SKU/batch quantities. Those vouchers do not dispatch or post stock again. Warehouse logs classify process creates, confirmations, cancellations and reviews separately from purchase/sales traffic.

## Local setup and verification

- `python3 docker/local/configure_process.py`: install schema and menu, without business history backfill.
- `python3 docker/local/test_process.py`: disposable database regressions.
- `node ruoyi-ui/tests/kit-process.cjs`: affected frontend templates.
- `python docker/local/verify_process.py`: retained TEST assembly, partial stop, source-batch disassembly, duplicate callbacks, vouchers, unified logs and UI.

No automatic company inference or datasource fallback is added. Public UI writes use login company and dedicated permissions; internal inventory writes carry company and require internal authentication. Processing is immediate, with durable event state and explicit retry; no new message queue is required.
