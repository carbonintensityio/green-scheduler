package io.carbonintensity.scheduler.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.junit.jupiter.api.Test;

import io.carbonintensity.scheduler.ScheduledExecution;

class DelegateInvokerTest {

    @Test
    void delegatesBlockingFlag() throws Exception {
        ScheduledInvoker nonBlocking = new TestInvoker(false);
        ScheduledInvoker blocking = new TestInvoker(true);

        DelegateInvoker proxyNonBlocking = new TestDelegateInvoker(nonBlocking);
        DelegateInvoker proxyBlocking = new TestDelegateInvoker(blocking);

        assertThat(proxyNonBlocking.isBlocking()).isFalse();
        assertThat(proxyBlocking.isBlocking()).isTrue();
    }

    private static class TestInvoker implements ScheduledInvoker {

        private final boolean blocking;

        private TestInvoker(boolean blocking) {
            this.blocking = blocking;
        }

        @Override
        public CompletionStage<Void> invoke(ScheduledExecution execution) {
            return CompletableFuture.completedStage(null);
        }

        @Override
        public boolean isBlocking() {
            return blocking;
        }
    }

    private static class TestDelegateInvoker extends DelegateInvoker {

        private TestDelegateInvoker(ScheduledInvoker delegate) {
            super(delegate);
        }

        @Override
        public CompletionStage<Void> invoke(ScheduledExecution execution) throws Exception {
            return invokeDelegate(execution);
        }
    }
}
