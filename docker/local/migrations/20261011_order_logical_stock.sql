-- Phase one does not assign inventory owners or warehouses during conversion.
ALTER TABLE oms_order_stock_demand MODIFY owner_code VARCHAR(64) NULL,
  MODIFY store_codes TEXT NULL;
