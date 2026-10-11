package com.oms.order.service;

/** Business validation failures have stable codes, independent of their display wording. */
public class ConversionException extends IllegalArgumentException {
    public final String code;
    public ConversionException(String code,String message){super(message);this.code=code;}
    public static void check(boolean valid,String code,String message){if(!valid)throw new ConversionException(code,message);}
}
