# Fixed virtual sales kits

This release supports a fixed, single-level sales SKU composed of ordinary physical SKUs. A kit has no physical stock of its own. Stocked kits additionally support warehouse assembly/disassembly, described in `oms-modules/oms-supplychain/PROCESSING.md`. Nested kits, random bags and kit-specific exchange workflows are outside this release.

## Composition and order identity

- Publish composition from the goods workspace's kit composition drawer. Publishing appends a revision; edits require the current revision.
- The order freezes the composition before registering inventory demand. Later composition edits affect future orders only.
- `oms_order_bundle` holds the original sales SKU, platform OID, purchased sets and sales payment once. `oms_order_line` holds component pieces and the per-set coefficient.
- Platform OID remains unchanged. Inventory and order demand identity includes the physical SKU, so A and B can share one sales OID without collision.
- Catalog writes prevent self references, duplicate children, nested kits, deleting referenced children and converting a referenced ordinary child into a kit.

## Stock, allocation and shipment

Logical reservations use physical components. Kit components may reserve available pieces and resume after replenishment; ordinary SKU reservation behavior is unchanged. Allocation applies the whole-set constraint independently of reservation completeness.

The default is one complete set in one virtual warehouse. Different complete sets may use different warehouses when multiple warehouses and partial order shipment are allowed. Shop allocation rules explicitly control `allow_bundle_split` and `allow_bundle_partial_components`; both default off and require their corresponding multiple-warehouse/partial-shipment parent options.

WMS documents, stock ledgers, shipment quantities and actual packages use physical pieces and original source/batch IDs. Sales quantity remains sets. Existing local platform shipping simulation projects cumulative complete component sets into parent sales units, avoiding an A2+B1 shipment being reported as three sales sets. This release does not add a real platform shipping transport.

## Returns

Refund return creation explicitly chooses components or complete sets. Complete-set creation freezes the physical source scope before preparing individual source plans. `oms_refund_bundle_claim` holds capacity until its corresponding return plan exists, preventing another refund or exchange from consuming the same shipped source during preparation.

A complete-set return of S=A2+B1 expects A2 and B1. Receiving A2 posts only A2 through the existing original-source stock accounting path; B1 remains pending and goods completion is blocked. Retry resumes the same frozen scope. Existing return parcels and receipt allocation rules apply to physical components.

## Setup and verification

Run `python3 docker/local/configure_bundles.py` in the local WSL environment. It installs kit master/version/reference tables, order snapshots and return scope/claim tables, and component-aware inventory demand columns/indexes. It performs no business history backfill.

- Backend regressions: `python3 docker/local/test_bundles.py`
- Frontend template/command checks: `node ruoyi-ui/tests/bundles.cjs`
- Retained TEST acceptance: `python docker/local/verify_fixed_bundles.py`

The retained case manifest is `outputs/exchange-ownership/fixedbundle-case.json`; results are in `outputs/fixed-bundles/result.json`. It buys two S sets, ships A4+B2 as two complete-set warehouse documents, changes and restores the master composition while preserving order revision 1, returns one set with A2 received and B1 pending, and replays the same receipt to verify inventory idempotency.
