package com.oms.order.service;
/** A repairable carrier configuration issue. Invalid physical facts must still reject the whole submission. */
final class CarrierConfigurationBlocked extends IllegalArgumentException {
    CarrierConfigurationBlocked(String message){super(message);}
}
