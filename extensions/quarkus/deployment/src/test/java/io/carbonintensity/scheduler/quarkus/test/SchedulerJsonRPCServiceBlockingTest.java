package io.carbonintensity.scheduler.quarkus.test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.TypeLiteral;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.carbonintensity.scheduler.ConcurrentExecution;
import io.carbonintensity.scheduler.GreenScheduled;
import io.carbonintensity.scheduler.ScheduledExecution;
import io.carbonintensity.scheduler.Scheduler;
import io.carbonintensity.scheduler.SkipPredicate;
import io.carbonintensity.scheduler.Trigger;
import io.carbonintensity.scheduler.quarkus.common.runtime.ScheduledMethod;
import io.carbonintensity.scheduler.quarkus.common.runtime.SchedulerContext;
import io.carbonintensity.scheduler.quarkus.devui.SchedulerJsonRPCService;
import io.carbonintensity.scheduler.runtime.ScheduledInvoker;
import io.vertx.core.Context;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.impl.ContextInternal;
import io.vertx.core.json.JsonObject;

class SchedulerJsonRPCServiceBlockingTest {

    private SchedulerJsonRPCService service;
    private BlockingInvoker blockingInvoker;
    private NonBlockingInvoker nonBlockingInvoker;
    private ContextInvocationHandler contextHandler;

    @BeforeEach
    void setUp() {
        blockingInvoker = new BlockingInvoker();
        nonBlockingInvoker = new NonBlockingInvoker();
        contextHandler = new ContextInvocationHandler();
        Context contextProxy = (ContextInternal) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { ContextInternal.class }, contextHandler);
        Vertx vertxProxy = (Vertx) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { Vertx.class },
                new VertxInvocationHandler(contextProxy));

        SchedulerContext schedulerContext = new TestSchedulerContext(blockingInvoker, nonBlockingInvoker);
        Scheduler scheduler = new RecordingScheduler();
        service = new SchedulerJsonRPCService(
                new FixedInstance<>(schedulerContext),
                new FixedInstance<>(scheduler),
                new FixedInstance<>(vertxProxy));
    }

    @Test
    void blockingInvokerUsesExecuteBlocking() {
        JsonResult result = invoke("TestJobs#blocking");
        System.out.println("blocking -> runOnContext=" + contextHandler.runOnContextCalled.get()
                + ", executeBlocking=" + contextHandler.executeBlockingCalled.get());
        assertTrue(result.success);
        assertTrue(contextHandler.executeBlockingCalled.get(),
                "Blocking invokers must be dispatched via executeBlocking");
        assertFalse(contextHandler.runOnContextCalled.get(),
                "Blocking invokers should not stay on the event loop");
    }

    @Test
    void nonBlockingInvokerRunsOnEventLoop() {
        JsonResult result = invoke("TestJobs#nonBlocking");
        System.out.println("nonBlocking -> runOnContext=" + contextHandler.runOnContextCalled.get()
                + ", executeBlocking=" + contextHandler.executeBlockingCalled.get());
        assertTrue(result.success);
        assertTrue(contextHandler.runOnContextCalled.get(),
                "Non-blocking invokers should run directly on the event loop");
        assertFalse(contextHandler.executeBlockingCalled.get(),
                "Non-blocking invokers must not use executeBlocking");
    }

    private JsonResult invoke(String methodDescription) {
        JsonObject json = service.executeJob(methodDescription);
        return new JsonResult(json.getBoolean("success", false));
    }

    static final class JsonResult {
        final boolean success;

        JsonResult(boolean success) {
            this.success = success;
        }
    }

    static class ContextInvocationHandler implements InvocationHandler {

        private final AtomicBoolean runOnContextCalled = new AtomicBoolean(false);
        private final AtomicBoolean executeBlockingCalled = new AtomicBoolean(false);
        private boolean duplicated = true;

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            switch (name) {
                case "duplicate":
                    duplicated = true;
                    return proxy;
                case "isDuplicate":
                    return duplicated;
                case "runOnContext":
                    runOnContextCalled.set(true);
                    @SuppressWarnings("unchecked")
                    Handler<Void> handler = (Handler<Void>) args[0];
                    handler.handle(null);
                    return null;
                case "executeBlocking":
                    executeBlockingCalled.set(true);
                    if (args != null && args.length > 0 && args[0] instanceof Callable) {
                        Callable<?> callable = (Callable<?>) args[0];
                        callable.call();
                    }
                    return CompletableFuture.completedFuture(null);
                default:
                    return defaultValue(method.getReturnType());
            }
        }

        private Object defaultValue(Class<?> returnType) {
            if (returnType.equals(boolean.class)) {
                return false;
            }
            if (returnType.equals(int.class) || returnType.equals(short.class) || returnType.equals(byte.class)) {
                return 0;
            }
            if (returnType.equals(long.class)) {
                return 0L;
            }
            if (returnType.equals(float.class) || returnType.equals(double.class)) {
                return 0d;
            }
            return null;
        }
    }

    static class VertxInvocationHandler implements InvocationHandler {

        private final Context context;

        VertxInvocationHandler(Context context) {
            this.context = context;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("getOrCreateContext".equals(method.getName())) {
                return context;
            }
            if ("close".equals(method.getName())) {
                return CompletableFuture.completedFuture(null);
            }
            return defaultValue(method.getReturnType());
        }

        private Object defaultValue(Class<?> type) {
            if (type.equals(boolean.class)) {
                return false;
            }
            if (type.equals(int.class) || type.equals(short.class) || type.equals(byte.class)) {
                return 0;
            }
            if (type.equals(long.class)) {
                return 0L;
            }
            if (type.equals(float.class) || type.equals(double.class)) {
                return 0d;
            }
            return null;
        }
    }

    static class TestSchedulerContext implements SchedulerContext {

        private final List<ScheduledMethod> methods;
        private final BlockingInvoker blockingInvoker;
        private final NonBlockingInvoker nonBlockingInvoker;

        TestSchedulerContext(BlockingInvoker blockingInvoker, NonBlockingInvoker nonBlockingInvoker) {
            this.blockingInvoker = blockingInvoker;
            this.nonBlockingInvoker = nonBlockingInvoker;
            this.methods = List.of(
                    new TestScheduledMethod(BlockingInvoker.class.getName(), "TestJobs", "blocking"),
                    new TestScheduledMethod(NonBlockingInvoker.class.getName(), "TestJobs", "nonBlocking"));
        }

        @Override
        public List<ScheduledMethod> getScheduledMethods() {
            return methods;
        }

        @Override
        public ScheduledInvoker createInvoker(String invokerClassName) {
            if (BlockingInvoker.class.getName().equals(invokerClassName)) {
                return blockingInvoker;
            }
            if (NonBlockingInvoker.class.getName().equals(invokerClassName)) {
                return nonBlockingInvoker;
            }
            throw new IllegalStateException("Unknown invoker: " + invokerClassName);
        }
    }

    static class BlockingInvoker implements ScheduledInvoker {

        @Override
        public CompletionStage<Void> invoke(ScheduledExecution execution) {
            return CompletableFuture.completedStage(null);
        }

        @Override
        public boolean isBlocking() {
            return true;
        }
    }

    static class NonBlockingInvoker implements ScheduledInvoker {

        @Override
        public CompletionStage<Void> invoke(ScheduledExecution execution) {
            return CompletableFuture.completedStage(null);
        }

        @Override
        public boolean isBlocking() {
            return false;
        }
    }

    static class RecordingScheduler implements Scheduler {

        @Override
        public void pause() {
        }

        @Override
        public void pause(String identity) {
        }

        @Override
        public void resume() {
        }

        @Override
        public void resume(String identity) {
        }

        @Override
        public boolean isPaused(String identity) {
            return false;
        }

        @Override
        public boolean isRunning() {
            return true;
        }

        @Override
        public List<Trigger> getScheduledJobs() {
            return List.of();
        }

        @Override
        public Trigger getScheduledJob(String identity) {
            return null;
        }

        @Override
        public JobDefinition newJob(String identity) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Trigger unscheduleJob(String identity) {
            return null;
        }

        @Override
        public void addJobListener(EventListener listener) {
        }

        @Override
        public boolean removeJobListener(EventListener listener) {
            return false;
        }
    }

    static class TestScheduledMethod implements ScheduledMethod {

        private final String invokerClassName;
        private final String declaringClassName;
        private final String methodName;
        private final List<GreenScheduled> schedules;

        TestScheduledMethod(String invokerClassName, String declaringClassName, String methodName) {
            this.invokerClassName = invokerClassName;
            this.declaringClassName = declaringClassName;
            this.methodName = methodName;
            this.schedules = List.of(new TestGreenScheduled());
        }

        @Override
        public String getInvokerClassName() {
            return invokerClassName;
        }

        @Override
        public String getDeclaringClassName() {
            return declaringClassName;
        }

        @Override
        public String getMethodName() {
            return methodName;
        }

        @Override
        public List<GreenScheduled> getSchedules() {
            return schedules;
        }
    }

    static class TestGreenScheduled implements GreenScheduled {

        @Override
        public Class<? extends Annotation> annotationType() {
            return GreenScheduled.class;
        }

        @Override
        public String identity() {
            return "";
        }

        @Override
        public String fixedWindow() {
            return "";
        }

        @Override
        public String timeZone() {
            return "";
        }

        @Override
        public String dayOfMonth() {
            return "";
        }

        @Override
        public String dayOfWeek() {
            return "";
        }

        @Override
        public String successive() {
            return "";
        }

        @Override
        public String cron() {
            return "";
        }

        @Override
        public String duration() {
            return Duration.ZERO.toString();
        }

        @Override
        public String carbonIntensityZone() {
            return "";
        }

        @Override
        public ConcurrentExecution concurrentExecution() {
            return ConcurrentExecution.PROCEED;
        }

        @Override
        public Class<? extends SkipPredicate> skipExecutionIf() {
            return SkipPredicate.Never.class;
        }

        @Override
        public String overdueGracePeriod() {
            return "";
        }
    }

    static class FixedInstance<T> implements Instance<T> {

        private final T value;

        FixedInstance(T value) {
            this.value = value;
        }

        @Override
        public T get() {
            return value;
        }

        @Override
        public Instance<T> select(Annotation... qualifiers) {
            return this;
        }

        @Override
        public <U extends T> Instance<U> select(Class<U> subtype, Annotation... qualifiers) {
            if (!subtype.isInstance(value)) {
                throw new IllegalStateException("Instance does not match subtype");
            }
            return new FixedInstance<>(subtype.cast(value));
        }

        @Override
        public <U extends T> Instance<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
            Class<?> rawType = subtype.getRawType();
            if (!rawType.isInstance(value)) {
                throw new IllegalStateException("Instance does not match subtype");
            }
            @SuppressWarnings("unchecked")
            U cast = (U) rawType.cast(value);
            return new FixedInstance<>(cast);
        }

        @Override
        public Stream<T> stream() {
            return value == null ? Stream.of() : Stream.of(value);
        }

        @Override
        public boolean isUnsatisfied() {
            return value == null;
        }

        @Override
        public boolean isAmbiguous() {
            return false;
        }

        @Override
        public boolean isResolvable() {
            return !isUnsatisfied();
        }

        @Override
        public void destroy(T instance) {
        }

        @Override
        public Handle<T> getHandle() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterable<? extends Handle<T>> handles() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Stream<? extends Handle<T>> handlesStream() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterator<T> iterator() {
            return value == null ? List.<T> of().iterator() : List.of(value).iterator();
        }
    }
}
