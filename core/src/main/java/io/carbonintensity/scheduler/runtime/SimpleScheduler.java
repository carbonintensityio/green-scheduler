package io.carbonintensity.scheduler.runtime;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.chrono.ChronoZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cronutils.model.Cron;
import com.cronutils.model.time.ExecutionTime;

import io.carbonintensity.executionplanner.planner.fixedwindow.DefaultFixedWindowPlanningConstraints;
import io.carbonintensity.executionplanner.planner.fixedwindow.FixedWindowPlanner;
import io.carbonintensity.executionplanner.planner.fixedwindow.FixedWindowPlanningConstraints;
import io.carbonintensity.executionplanner.planner.successive.DefaultSuccessivePlanningConstraints;
import io.carbonintensity.executionplanner.planner.successive.SuccessivePlanner;
import io.carbonintensity.executionplanner.planner.successive.SuccessivePlanningConstraints;
import io.carbonintensity.executionplanner.runtime.impl.CarbonIntensityDataFetcher;
import io.carbonintensity.executionplanner.runtime.impl.CarbonIntensityDataFetcherImpl;
import io.carbonintensity.executionplanner.runtime.impl.rest.CarbonIntensityApiType;
import io.carbonintensity.executionplanner.runtime.impl.rest.CarbonIntensityRestApi;
import io.carbonintensity.executionplanner.spi.CarbonIntensityPlanner;
import io.carbonintensity.executionplanner.spi.PlanningConstraints;
import io.carbonintensity.scheduler.ConcurrentExecution;
import io.carbonintensity.scheduler.GreenScheduled;
import io.carbonintensity.scheduler.ScheduledExecution;
import io.carbonintensity.scheduler.Scheduler;
import io.carbonintensity.scheduler.SkipPredicate;
import io.carbonintensity.scheduler.Trigger;
import io.carbonintensity.scheduler.runtime.SchedulerConfig.StartMode;
import io.carbonintensity.scheduler.runtime.impl.annotation.GreenScheduledAnnotationParser;
import io.carbonintensity.scheduler.runtime.impl.rest.CarbonIntensityFileApi;
import io.carbonintensity.scheduler.spi.JobInstrumenter;

/**
 * A simple scheduler implementation that manages scheduled tasks using a thread pool executor.
 *
 * <p>
 * The {@code SimpleScheduler} is responsible for registering, executing, and managing scheduled
 * tasks based on predefined triggers. It supports both annotation-based scheduling and
 * programmatic job scheduling.
 * </p>
 *
 * <p>
 * The scheduler can be enabled or disabled via {@link SchedulerConfig}. If enabled, it starts
 * automatically based on the configured {@link StartMode}. If no scheduled methods are detected,
 * it will start only when the first programmatic job is scheduled.
 * </p>
 *
 * <h3>Thread Management</h3>
 * <p>
 * The scheduler manages three executor services:
 * <ul>
 * <li>{@code scheduledExecutor} - Periodically checks for triggers.</li>
 * <li>{@code jobExecutor} - Executes scheduled jobs.</li>
 * <li>{@code renewExecutor} - Handles renewal-related tasks.</li>
 * </ul>
 * These executors ensure efficient and concurrent execution of jobs while maintaining scheduling
 * accuracy.
 * </p>
 *
 * <h3>Usage</h3>
 *
 * <pre>{@code
 * SimpleScheduler scheduler = new SimpleScheduler(config);
 * scheduler.start();
 * }</pre>
 *
 * <h3>Shutdown and Cleanup</h3>
 * <p>
 * The scheduler should be properly shut down to release resources:
 *
 * <pre>{@code
 * scheduler.close();
 * }</pre>
 * </p>
 *
 * @see Scheduler
 */
public class SimpleScheduler implements Scheduler, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SimpleScheduler.class);
    // milliseconds
    public static final long CHECK_PERIOD = 1000L;

    private CarbonIntensityDataFetcher dataFetcher;
    private final Clock clock;
    private ScheduledExecutorService scheduledExecutor;
    private ScheduledFuture<?> scheduledFuture;
    private ExecutorService jobExecutor;
    private volatile boolean running;
    private final ConcurrentMap<String, ScheduledTask> scheduledTasks;
    private final boolean enabled;
    private final SchedulerConfig schedulerConfig;
    private final JobInstrumenter jobInstrumenter;
    private final List<EventListener> eventListeners;
    private final Events events;

    public SimpleScheduler(SchedulerConfig schedulerConfig) {
        this.clock = schedulerConfig.getClock();
        this.events = new Events(this);
        this.running = false;
        this.enabled = schedulerConfig.isEnabled();
        this.scheduledTasks = new ConcurrentHashMap<>();
        this.schedulerConfig = schedulerConfig;
        this.jobInstrumenter = schedulerConfig.getJobInstrumenter();
        this.eventListeners = new ArrayList<>();

        if (!schedulerConfig.isEnabled()) {
            log.info("Simple scheduler is disabled by config property and will not be started.");
            return;
        }

        var carbonIntensityApi = Objects.requireNonNullElse(schedulerConfig.getCarbonIntensityApi(),
                new CarbonIntensityRestApi(schedulerConfig.getCarbonIntensityApiConfig(), CarbonIntensityApiType.PREDICTED));

        log.info("CarbonIntensity API: {}", carbonIntensityApi.getApiName());

        this.dataFetcher = new CarbonIntensityDataFetcherImpl(carbonIntensityApi, new CarbonIntensityFileApi());

        if (StartMode.FORCED == schedulerConfig.getStartMode()) {
            log.info("Simple scheduler will be started, force scheduler start is enabled.");
            start();
        }
    }

    public void scheduleMethod(ScheduledMethod method) {
        int nameSequence = 0;
        for (GreenScheduled scheduled : method.getSchedules()) {
            nameSequence++;
            String id = scheduled.identity();
            if (id.isEmpty()) {
                id = nameSequence + "_" + method.getMethodDescription();
            }
            final var constraints = GreenScheduledAnnotationParser.createConstraints(id, scheduled, clock);
            SimpleTrigger trigger = createTrigger(id, method.getMethodDescription(),
                    GreenScheduledAnnotationParser.parseOverdueGracePeriod(scheduled, schedulerConfig.getOverdueGracePeriod()),
                    constraints);
            ScheduledInvoker invoker = initInvoker(method.getInvoker(), events,
                    scheduled.concurrentExecution(), initSkipPredicate(scheduled.skipExecutionIf()), jobInstrumenter);
            registerTask(trigger.id, new ScheduledTask(trigger, invoker, false));
        }
    }

    @Override
    public JobDefinition newJob(String identity) {
        Objects.requireNonNull(identity);
        if (scheduledTasks.containsKey(identity)) {
            throw new IllegalStateException("A job with this identity is already scheduled: " + identity);
        }
        return new SimpleJobDefinition(identity);
    }

    @Override
    public Trigger unscheduleJob(String identity) {
        Objects.requireNonNull(identity);
        if (!identity.isEmpty()) {
            ScheduledTask task = scheduledTasks.get(identity);
            if (task != null && task.isProgrammatic) {
                if (scheduledTasks.remove(task.trigger.id) != null) {
                    return task.trigger;
                }
            }
        }
        return null;
    }

    void initExecutors() {
        if (scheduledExecutor == null) {
            ThreadFactory tf = new ThreadFactory() {

                private final AtomicInteger threadNumber = new AtomicInteger(1);

                @Override
                public Thread newThread(Runnable runnable) {
                    Thread t = new Thread(Thread.currentThread()
                            .getThreadGroup(), runnable, "green-scheduler-trigger-check-" + threadNumber.getAndIncrement(), 0);
                    if (t.isDaemon()) {
                        t.setDaemon(false);
                    }
                    if (t.getPriority() != Thread.NORM_PRIORITY) {
                        t.setPriority(Thread.NORM_PRIORITY);
                    }
                    return t;
                }
            };
            // This executor is used to check all registered triggers every second
            this.scheduledExecutor = new ScheduledThreadPoolExecutor(2, tf);
        }
        if (this.jobExecutor == null) {
            ThreadFactory jtf = new ThreadFactory() {

                private final AtomicInteger threadNumber = new AtomicInteger(1);

                @Override
                public Thread newThread(Runnable runnable) {
                    Thread t = new Thread(Thread.currentThread()
                            .getThreadGroup(), runnable, "green-scheduler-job-executor-" + threadNumber.getAndIncrement(), 0);
                    if (t.isDaemon()) {
                        t.setDaemon(false);
                    }
                    if (t.getPriority() != Thread.NORM_PRIORITY) {
                        t.setPriority(Thread.NORM_PRIORITY);
                    }
                    return t;
                }
            };
            // This executor is used to run all jobs
            this.jobExecutor = Executors.newFixedThreadPool(schedulerConfig.getJobExecutors(), jtf);
        }
    }

    public void start() {
        if (schedulerConfig.isEnabled() && (scheduledFuture == null || scheduledFuture.isDone())) {
            running = schedulerConfig.getStartMode() != StartMode.HALTED;

            // Init executors if needed.
            initExecutors();

            // Try to compute the initial delay to execute the checks near to the whole second
            // Note that this does not guarantee anything, it's just best effort
            LocalDateTime now = LocalDateTime.now(clock);
            LocalDateTime trunc = now.plusSeconds(1).truncatedTo(ChronoUnit.SECONDS);
            scheduledFuture = scheduledExecutor.scheduleAtFixedRate(this::checkTriggers, ChronoUnit.MILLIS.between(now, trunc),
                    CHECK_PERIOD,
                    TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Stops the scheduler gracefully by closing all resources and shutting down running tasks.
     * <p>
     * This method is marked as deprecated and is planned for removal in future versions.
     * It internally invokes the {@code close()} method to handle the scheduler's shutdown
     * process, ensuring proper cleanup of executors and other resources.
     *
     * @deprecated since version 0.8.3, as this functionality is now handled by the {@code close()} method.
     */
    @Deprecated(since = "0.8.3", forRemoval = true)
    public void stop() {
        close();
    }

    /**
     * Stops the scheduler gracefully by closing all resources and shutting down running tasks.
     */
    @Override
    public void close() {
        log.info("Shutting down simple scheduler gracefully.");
        if (scheduledFuture != null) {
            scheduledFuture.cancel(false);
            scheduledFuture = null;
        }
        try {
            if (scheduledExecutor != null) {
                scheduledExecutor.shutdownNow();
                scheduledExecutor = null;
            }
        } catch (Exception e) {
            log.warn("Unable to shutdown the scheduler executor", e);
        }
        try {
            if (jobExecutor != null) {
                jobExecutor.shutdown();
                try {
                    if (!jobExecutor.awaitTermination(schedulerConfig.getShutdownGracePeriod()
                            .getSeconds(), TimeUnit.SECONDS)) {
                        log.warn(
                                "Unable to gracefully shutdown job executor, running jobs did not finish within {}, shutting down now.",
                                schedulerConfig.getShutdownGracePeriod());
                    }
                } catch (InterruptedException ie) {
                    log.warn("Got interrupted during wait on graceful shutdown of job executor, shutting down now.");
                } finally {
                    jobExecutor = null;
                }
            }
        } catch (Exception e) {
            log.warn("Unable to shutdown the job executor", e);
        }
        log.info("Simple scheduler shutdown.");
        running = false;
    }

    @Override
    public void addJobListener(EventListener listener) {
        if (!this.eventListeners.contains(listener)) {
            this.eventListeners.add(listener);
        }
    }

    @Override
    public boolean removeJobListener(EventListener listener) {
        return this.eventListeners.remove(listener);
    }

    List<EventListener> getEventListeners() {
        return new ArrayList<>(this.eventListeners);
    }

    void checkTriggers() {
        if (!running) {
            log.trace("Skip all triggers - scheduler paused");
            return;
        }
        ZonedDateTime now = ZonedDateTime.now(clock);
        log.trace("Check triggers at {}", now);
        for (ScheduledTask task : scheduledTasks.values()) {
            try {
                task.execute(now, jobExecutor);
            } catch (Exception e) {
                log.warn("Unexpected exception while executing trigger for {}", task.trigger.getMethodDescription(), e);
            }
        }
    }

    @Override
    public void pause() {
        if (!enabled) {
            log.warn("Scheduler is disabled and cannot be paused");
        } else {
            running = false;
            events.fireSchedulerPaused();
        }
    }

    @Override
    public void pause(String identity) {
        Objects.requireNonNull(identity, "Cannot pause - identity is null");
        if (identity.isEmpty()) {
            log.warn("Cannot pause - identity is empty");
            return;
        }
        ScheduledTask task = scheduledTasks.get(identity);
        if (task != null) {
            task.trigger.setRunning(false);
            events.fireJobPaused(task.trigger);
        }
    }

    @Override
    public boolean isPaused(String identity) {
        Objects.requireNonNull(identity);
        if (identity.isEmpty()) {
            return false;
        }
        ScheduledTask task = scheduledTasks.get(identity);
        if (task != null) {
            return task.trigger.isPaused();
        }
        return false;
    }

    @Override
    public void resume() {
        if (!enabled) {
            log.warn("Scheduler is disabled and cannot be resumed");
        } else {
            running = true;
            events.fireSchedulerResumed();
        }
    }

    @Override
    public void resume(String identity) {
        Objects.requireNonNull(identity, "Cannot resume - identity is null");
        if (identity.isEmpty()) {
            log.warn("Cannot resume - identity is empty");
            return;
        }
        ScheduledTask task = scheduledTasks.get(identity);
        if (task != null) {
            task.trigger.setRunning(true);
            events.fireJobResumed(task.trigger);
        }
    }

    @Override
    public boolean isRunning() {
        return enabled && running;
    }

    @Override
    public List<Trigger> getScheduledJobs() {
        return scheduledTasks.values().stream().map(task -> task.trigger).collect(Collectors.toUnmodifiableList());
    }

    @Override
    public Trigger getScheduledJob(String identity) {
        Objects.requireNonNull(identity);
        if (identity.isEmpty()) {
            return null;
        }
        ScheduledTask task = scheduledTasks.get(identity);
        if (task != null) {
            return task.trigger;
        }
        return null;
    }

    SimpleTrigger createTrigger(String id, String methodDescription, Duration overdueGracePeriod,
            PlanningConstraints constraints) {

        if (constraints instanceof FixedWindowPlanningConstraints) {
            var fixedWindowConstraints = (FixedWindowPlanningConstraints) constraints;
            CarbonIntensityPlanner<FixedWindowPlanningConstraints> fixedWindowPlanner = new FixedWindowPlanner(dataFetcher);
            return new FixedWindowTrigger(id, methodDescription, overdueGracePeriod, fixedWindowPlanner,
                    fixedWindowConstraints, clock);
        } else if (constraints instanceof SuccessivePlanningConstraints) {
            var successiveConstraints = (SuccessivePlanningConstraints) constraints;
            CarbonIntensityPlanner<SuccessivePlanningConstraints> successivePlanner = new SuccessivePlanner(dataFetcher);
            final var start = ZonedDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
            return new SuccessiveTrigger(id, clock, start, methodDescription, overdueGracePeriod, successivePlanner,
                    successiveConstraints);
        }
        throw new IllegalArgumentException("Constraints type not implemented: " + constraints.getClass());
    }

    ScheduledTask registerTask(String id, ScheduledTask scheduledTask) {
        start();
        return scheduledTasks.putIfAbsent(id, scheduledTask);
    }

    public static ScheduledInvoker initInvoker(ScheduledInvoker invoker, Events events, ConcurrentExecution concurrentExecution,
            SkipPredicate skipPredicate, JobInstrumenter instrumenter) {
        invoker = new StatusEmitterInvoker(invoker, events);
        if (concurrentExecution == ConcurrentExecution.SKIP) {
            invoker = new SkipConcurrentExecutionInvoker(invoker, events);
        }
        if (skipPredicate != null) {
            invoker = new SkipPredicateInvoker(invoker, skipPredicate, events);
        }
        if (instrumenter != null) {
            invoker = new InstrumentedInvoker(invoker, instrumenter);
        }
        return invoker;
    }

    public static SkipPredicate initSkipPredicate(Class<? extends SkipPredicate> predicateClass) {
        if (predicateClass.equals(SkipPredicate.Never.class)) {
            return null;
        }
        return instantiateClass(predicateClass);
    }

    static <T> T instantiateClass(Class<T> type) {
        try {
            return type.getConstructor().newInstance();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to instantiate the class: " + type);
        }
    }

    static class ScheduledTask {

        final boolean isProgrammatic;
        final SimpleTrigger trigger;
        final ScheduledInvoker invoker;

        ScheduledTask(SimpleTrigger trigger, ScheduledInvoker invoker, boolean isProgrammatic) {
            this.trigger = trigger;
            this.invoker = invoker;
            this.isProgrammatic = isProgrammatic;
        }

        void execute(ZonedDateTime now, ExecutorService executorService) {
            if (trigger.isPaused()) {
                return;
            }

            // evaluate if we need to fire
            ZonedDateTime scheduledFireTime = trigger.evaluate(now);
            if (scheduledFireTime != null) {
                executorService.execute(() -> doInvoke(now, scheduledFireTime));
            }
        }

        void doInvoke(ZonedDateTime now, ZonedDateTime scheduledFireTime) {
            try {
                invoker.invoke(new SimpleScheduledExecution(now, scheduledFireTime, trigger));
            } catch (Exception t) {
                // already logged by the StatusEmitterInvoker
            }
        }
    }

    /**
     * A trigger implementation that schedules tasks based on carbon intensity constraints
     * and dynamically determined execution times.
     *
     * <p>
     * The {@code SuccessiveTrigger} extends {@link IntervalTrigger} but allows for dynamic
     * scheduling based on the availability of lower-carbon execution windows. It integrates
     * with a {@link CarbonIntensityPlanner} to determine the best execution times while
     * ensuring tasks still fire within a reasonable interval when optimal scheduling is not
     * possible. If it is not possible to plan using the {@link CarbonIntensityPlanner}, as
     * a fallback the {@link IntervalTrigger} is used.
     * </p>
     *
     * @see CarbonIntensityPlanner
     */
    static class SuccessiveTrigger extends IntervalTrigger {
        private final CarbonIntensityPlanner<SuccessivePlanningConstraints> successivePlanner;
        private final SuccessivePlanningConstraints constraints;
        private final Duration gracePeriod;

        public SuccessiveTrigger(String id, Clock clock, ZonedDateTime start, String description, Duration gracePeriod,
                CarbonIntensityPlanner<SuccessivePlanningConstraints> successivePlanner,
                SuccessivePlanningConstraints constraints) {
            super(id, start, calculateFallbackInterval(constraints), gracePeriod, description, clock);
            this.successivePlanner = successivePlanner;
            this.constraints = constraints;
            this.gracePeriod = gracePeriod;
        }

        private static long calculateFallbackInterval(SuccessivePlanningConstraints constraints) {
            // we take the average between minGap and maxGap as a millisecond interval
            return constraints.getMinimumGap().plus(constraints.getMaximumGap()).dividedBy(2).toMillis();
        }

        @Override
        public Instant getNextFireTime() {
            if (successivePlanner.canSchedule(constraints)) {
                return successivePlanner.getNextExecutionTime(constraints).toInstant();
            }
            // fallback to interval trigger
            return super.getNextFireTime();
        }

        @Override
        ZonedDateTime evaluate(ZonedDateTime now) {
            if (successivePlanner.canSchedule(constraints)) {
                if (now.isBefore(start)) {
                    return null;
                }

                ZonedDateTime nextExecutionTime = null;

                // first invocation
                if (lastFireTime == null) {
                    nextExecutionTime = successivePlanner.getNextExecutionTime(constraints);
                }

                // sequential invocations
                if (lastFireTime != null && now.plusSeconds(1).isAfter(lastFireTime.plus(constraints.getMinimumGap()))) {
                    nextExecutionTime = successivePlanner
                            .getNextExecutionTime(DefaultSuccessivePlanningConstraints.from(constraints)
                                    .withLastExecutionTime(lastFireTime)
                                    .build());
                }

                if (nextExecutionTime != null) {
                    ZonedDateTime nextTruncated = nextExecutionTime.truncatedTo(ChronoUnit.SECONDS);
                    if (now.isAfter(nextTruncated) && (lastFireTime == null || lastFireTime.isBefore(nextTruncated))) {
                        log.trace("{} fired, trigger={}", this, nextTruncated);
                        lastFireTime = now;
                        return nextTruncated;
                    }
                }
                return null;
            }
            // fallback to interval trigger
            return super.evaluate(now);
        }

        @Override
        public boolean isOverdue() {
            if (successivePlanner.canSchedule(constraints)) {
                ZonedDateTime now = ZonedDateTime.now(clock);
                if (now.isBefore(start)) {
                    return false;
                }
                Instant nextFireTime = getNextFireTime();
                return nextFireTime == null || nextFireTime.plus(gracePeriod).isBefore(now.toInstant());
            }
            // fallback to interval trigger
            return super.isOverdue();
        }
    }

    /**
     * A base abstract class for scheduling triggers that determine execution times dynamically.
     *
     * <p>
     * The {@code SimpleTrigger} defines the fundamental structure for triggers, providing
     * essential scheduling attributes and methods for evaluating execution times.
     * Concrete implementations must define the {@link #evaluate(ZonedDateTime)} method
     * to determine when the trigger should fire.
     * </p>
     * <h3>Usage</h3>
     * <p>
     * This class is intended to be extended by specific trigger implementations, such as
     * interval-based or dynamically scheduled triggers.
     * </p>
     *
     * @see Trigger
     */
    abstract static class SimpleTrigger implements Trigger {

        protected final String id;
        protected final Clock clock;
        protected final String methodDescription;
        private volatile boolean running;
        protected final ZonedDateTime start;
        protected volatile ZonedDateTime lastFireTime;

        SimpleTrigger(String id, Clock clock, ZonedDateTime start, String description) {
            this.id = id;
            this.clock = clock;
            this.start = start;
            this.running = true;
            this.methodDescription = description;
        }

        /**
         * @param now The current date-time in the default time carbonIntensityZone
         * @return the scheduled time if fired, {@code null} otherwise
         */
        abstract ZonedDateTime evaluate(ZonedDateTime now);

        @Override
        public Instant getPreviousFireTime() {
            ZonedDateTime last = lastFireTime;
            return last != null ? last.toInstant() : null;
        }

        public String getId() {
            return id;
        }

        synchronized boolean isPaused() {
            return !running;
        }

        synchronized void setRunning(boolean running) {
            this.running = running;
        }

        @Override
        public String getMethodDescription() {
            return methodDescription;
        }
    }

    /**
     * A trigger implementation that schedules tasks based on a cron expression.
     *
     * @see SimpleTrigger
     * @see Cron
     * @see ExecutionTime
     */
    static class CronTrigger extends SimpleTrigger {
        private final Cron cron;
        private final ExecutionTime executionTime;
        private final Duration gracePeriod;
        private final ZoneId timeZone;

        CronTrigger(String id, ZonedDateTime start, Cron cron, Duration gracePeriod, String description,
                Clock clock) {
            super(id, clock, start, description);
            this.cron = cron;
            this.executionTime = ExecutionTime.forCron(cron);
            this.lastFireTime = start;
            this.gracePeriod = gracePeriod;
            this.timeZone = start.getZone();
        }

        public Instant getNextFireTime() {
            return this.executionTime.nextExecution(this.lastFireTime).map(ChronoZonedDateTime::toInstant)
                    .orElse(null);
        }

        ZonedDateTime evaluate(ZonedDateTime now) {
            if (now.isBefore(this.start)) {
                return null;
            } else {
                now = this.zoned(now);
                Optional<ZonedDateTime> lastExecution = this.executionTime.lastExecution(now);
                if (lastExecution.isPresent()) {
                    ZonedDateTime lastTruncated = lastExecution.get().truncatedTo(ChronoUnit.SECONDS);
                    if (now.isAfter(lastTruncated) && (lastFireTime == null || lastFireTime.isBefore(lastTruncated))) {
                        log.trace("{} fired, last={}", this, lastTruncated);
                        this.lastFireTime = now;
                        return lastTruncated;
                    }
                }

                return null;
            }
        }

        public boolean isOverdue() {
            ZonedDateTime now = ZonedDateTime.now();
            if (now.isBefore(this.start)) {
                return false;
            } else {
                now = this.zoned(now);
                Optional<ZonedDateTime> nextFireTime = this.executionTime.nextExecution(this.lastFireTime);
                return nextFireTime.isEmpty() || nextFireTime.get().plus(this.gracePeriod).isBefore(now);
            }
        }

        public String toString() {
            return "CronTrigger [id=" + this.id + ", cron=" + this.cron.asString() + ", gracePeriod=" + this.gracePeriod
                    + ", timeZone=" + this.timeZone + "]";
        }

        private ZonedDateTime zoned(ZonedDateTime time) {
            return this.timeZone == null ? time : time.withZoneSameInstant(this.timeZone);
        }
    }

    /**
     * A trigger implementation that schedules tasks within a fixed time window based on
     * carbon intensity constraints.
     *
     * <p>
     * The {@code FixedWindowTrigger} extends {@link CronTrigger} but allows for dynamic
     * scheduling within a defined start and end time. It integrates with a
     * {@link CarbonIntensityPlanner} to determine the most optimal execution time within
     * the window while falling back to a cron-based schedule if necessary.
     * </p>
     *
     *
     * @see CronTrigger
     * @see CarbonIntensityPlanner
     * @see FixedWindowPlanningConstraints
     */
    static class FixedWindowTrigger extends CronTrigger {

        private final CarbonIntensityPlanner<FixedWindowPlanningConstraints> planner;
        private final Duration overdueGracePeriod;
        private FixedWindowPlanningConstraints constraints;

        FixedWindowTrigger(String id, String description, Duration overdueGracePeriod,
                CarbonIntensityPlanner<FixedWindowPlanningConstraints> planner,
                FixedWindowPlanningConstraints constraints,
                Clock clock) {
            super(id, constraints.getStart(), constraints.getFallbackCronExpression(),
                    overdueGracePeriod, description, clock);
            this.planner = planner;
            this.constraints = constraints;
            this.lastFireTime = start.minusSeconds(1); // Minus 1 second so that it will run if deployed during the window (and greenest window is at the start)
            this.overdueGracePeriod = overdueGracePeriod;
        }

        @Override
        public Instant getNextFireTime() {
            return planner.getNextExecutionTime(constraints).toInstant();
        }

        @Override
        ZonedDateTime evaluate(ZonedDateTime now) {
            if (!planner.canSchedule(constraints)) {
                // fallback to cron trigger
                return super.evaluate(now);
            }

            if (!(now.isAfter(constraints.getStart())
                    && now.isBefore(constraints.getEnd().plus(overdueGracePeriod)))) {
                return null;
            }

            // first invocation
            if (lastFireTime == null || now.isAfter(lastFireTime)) {
                ZonedDateTime nextExecutionTime = planner.getNextExecutionTime(constraints);
                if (nextExecutionTime != null) {
                    ZonedDateTime nextTruncated = nextExecutionTime.truncatedTo(ChronoUnit.SECONDS);
                    if (now.isAfter(nextTruncated) && (lastFireTime == null || lastFireTime.isBefore(nextTruncated))) {
                        log.trace("{} fired, trigger={}, updating constraints for next run", this, nextTruncated);
                        lastFireTime = now;
                        constraints = DefaultFixedWindowPlanningConstraints.from(constraints)
                                .withStartAndEnd(constraints.getStart().plusDays(1), constraints.getEnd().plusDays(1))
                                .build();
                        return nextExecutionTime;
                    }
                }
            }

            return null;
        }

        @Override
        public boolean isOverdue() {
            return false;
        }
    }

    /**
     * A trigger implementation that schedules tasks at fixed intervals.
     *
     * <p>
     * The {@code IntervalTrigger} extends {@link SimpleTrigger} and executes tasks
     * periodically based on a fixed time interval. It ensures that tasks run at
     * regular intervals, with a grace period to allow minor execution delays.
     * </p>
     *
     * @see SimpleTrigger
     */
    static class IntervalTrigger extends SimpleTrigger {

        // milliseconds
        private final long interval;
        private final Duration gracePeriod;

        private IntervalTrigger(String id, ZonedDateTime start, long interval, Duration gracePeriod, String description,
                Clock clock) {
            super(id, clock, start, description);
            this.interval = interval;
            this.gracePeriod = gracePeriod;
            if (interval < CHECK_PERIOD) {
                log.warn(
                        "An every() value less than {} ms is not supported - the scheduled job will be executed with a delay: {}",
                        CHECK_PERIOD, description);
            }
        }

        @Override
        ZonedDateTime evaluate(ZonedDateTime now) {
            if (now.isBefore(start)) {
                return null;
            }
            if (lastFireTime == null) {
                // First execution
                lastFireTime = now.truncatedTo(ChronoUnit.SECONDS);
                return now;
            }
            long diff = ChronoUnit.MILLIS.between(lastFireTime, now);
            if (diff >= interval) {
                ZonedDateTime scheduledFireTime = lastFireTime.plus(Duration.ofMillis(interval));
                lastFireTime = now.truncatedTo(ChronoUnit.SECONDS);
                log.trace("{} fired, diff={} ms", this, diff);
                return scheduledFireTime;
            }
            return null;
        }

        @Override
        public Instant getNextFireTime() {
            ZonedDateTime last = lastFireTime;
            if (last == null) {
                last = start;
            }
            return last.plus(Duration.ofMillis(interval)).toInstant();
        }

        @Override
        public boolean isOverdue() {
            ZonedDateTime now = ZonedDateTime.now(clock);
            if (now.isBefore(start)) {
                return false;
            }
            return lastFireTime == null || lastFireTime.plus(Duration.ofMillis(interval))
                    .plus(gracePeriod)
                    .isBefore(now);
        }

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();
            builder.append("IntervalTrigger [id=").append(getId()).append(", interval=").append(interval).append("]");
            return builder.toString();
        }

    }

    /**
     * A simple implementation of the {@link ScheduledExecution} interface that holds details
     * about a scheduled execution, including the actual fire time, the scheduled fire time,
     * and the associated trigger.
     *
     * @see ScheduledExecution
     * @see Trigger
     * @see SimpleTrigger
     */
    static class SimpleScheduledExecution implements ScheduledExecution {

        private final ZonedDateTime fireTime;
        private final ZonedDateTime scheduledFireTime;
        private final Trigger trigger;

        public SimpleScheduledExecution(ZonedDateTime fireTime, ZonedDateTime scheduledFireTime, SimpleTrigger trigger) {
            this.fireTime = fireTime;
            this.scheduledFireTime = scheduledFireTime;
            this.trigger = trigger;
        }

        @Override
        public Trigger getTrigger() {
            return trigger;
        }

        @Override
        public Instant getFireTime() {
            return fireTime.toInstant();
        }

        @Override
        public Instant getScheduledFireTime() {
            return scheduledFireTime.toInstant();
        }
    }

    /**
     * A job definition that schedules a task with configurable execution constraints.
     *
     * <p>
     * The {@code SimpleJobDefinition} extends {@link AbstractJobDefinition} and allows for
     * scheduling a job based on configurable time constraints such as the minimum and maximum
     * gap between executions, as well as the overall duration. It ensures that tasks are
     * executed based on these constraints and handles task invocations and failures.
     * </p>
     *
     * <p>
     * The {@code schedule} method is responsible for configuring the task to be executed
     * and ensuring that the constraints are met. If any validation fails (e.g., the task is not set
     * or gaps are not correctly defined), an {@link IllegalStateException} is thrown.
     * </p>
     *
     * @see AbstractJobDefinition
     */
    class SimpleJobDefinition extends AbstractJobDefinition {

        SimpleJobDefinition(String id) {
            super(id);
        }

        @Override
        public Trigger schedule() {
            checkScheduled();
            if (task == null) {
                throw new IllegalStateException("Task must be set");
            }
            if (minimumGap.compareTo(maximumGap) >= 1) {
                throw new IllegalStateException("Min gap must be less than max gap");
            }
            scheduled = true;
            ScheduledInvoker invoker = execution -> {
                try {
                    task.accept(execution);
                    return CompletableFuture.completedStage(null);
                } catch (Exception e) {
                    return CompletableFuture.failedStage(e);
                }
            };

            SimpleTrigger trigger = createTrigger(identity, null, overdueGracePeriod,
                    DefaultSuccessivePlanningConstraints.builder()
                            .withInitialStartTime(ZonedDateTime.now(clock))
                            .withInitialMaximumDelay(initialMaximumDelay)
                            .withMinimumGap(minimumGap)
                            .withMaximumGap(maximumGap)
                            .withDuration(duration)
                            .withCarbonIntensityZone(zone)
                            .build());
            invoker = initInvoker(invoker, events, concurrentExecution, skipPredicate, jobInstrumenter);
            ScheduledTask scheduledTask = new ScheduledTask(trigger, invoker, true);
            ScheduledTask existing = registerTask(trigger.id, scheduledTask);
            if (existing != null) {
                throw new IllegalStateException("A job with this identity is already scheduled: " + identity);
            }
            return trigger;
        }
    }
}
