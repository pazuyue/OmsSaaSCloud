package com.ruoyi.system.company;
/** Fixed service catalog only; implementations must never accept an operator-provided URL. */
public interface CompanyConfigTransport {
    String read(String service);
    boolean compareWrite(String service,String original,String updated);
}
