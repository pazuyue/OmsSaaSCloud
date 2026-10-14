package com.oms.common.task;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
/** Wakeups are best effort; durable database work is the source of truth. */
public final class CommittedWork {
    private CommittedWork(){}
    public static void afterCommit(Runnable work){Runnable safe=()->{try{work.run();}catch(RuntimeException e){org.slf4j.LoggerFactory.getLogger(CommittedWork.class).warn("Immediate wakeup unavailable; committed work awaits compensation");}};
        if(TransactionSynchronizationManager.isActualTransactionActive()&&TransactionSynchronizationManager.isSynchronizationActive())TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){safe.run();}});else safe.run();
    }
}
