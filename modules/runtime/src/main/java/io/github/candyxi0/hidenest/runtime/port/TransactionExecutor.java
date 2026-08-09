package io.github.candyxi0.hidenest.runtime.port;

import java.util.function.Supplier;

public interface TransactionExecutor {
    <T> T executeInTransaction(Supplier<T> work);
}
