package io.github.candyxi0.hidenest.database.adapter;

import io.github.candyxi0.hidenest.runtime.port.TransactionExecutor;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionTemplate;

public class SpringTransactionExecutor implements TransactionExecutor {

    private final TransactionTemplate transactionTemplate;

    public SpringTransactionExecutor(TransactionTemplate transactionTemplate) {
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public <T> T executeInTransaction(Supplier<T> work) {
        return transactionTemplate.execute(status -> work.get());
    }
}
