package com.oms.order.service;
public final class OrderShipmentChanged {
    final String company,route;final long shipment;
    OrderShipmentChanged(String company,String route,long shipment){this.company=company;this.route=route;this.shipment=shipment;}
}
