package com.dianping.xpro.utils;

public interface ILock {
    boolean lock(long timeoutSec);
    void unlock();
}
