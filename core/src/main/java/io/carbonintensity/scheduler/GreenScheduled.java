package io.carbonintensity.scheduler;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.time.Duration;
import java.time.ZoneId;

import io.carbonintensity.executionplanner.spi.CarbonIntensityPlanner;

/**
 * Identifies a method of a bean class that is automatically scheduled and invoked by the container.
 * <p>
 * A scheduled method is a non-abstract non-private method of a bean class. It may be either static or non-static.
 *
 * <pre>
 * &#64;ApplicationScoped
 * class MyService {
 *
 *     &#64;GreenScheduled(minGap = "12H", maxGap = "24H")
 *     void check() {
 *         // do something important once every 12-24 hours.
 *     }
 * }
 * </pre>
 * <p>
 * The annotated method must return {@code void} and either declare no parameters or one parameter of type
 * {@link ScheduledExecution}.
 *
 * <h2>Inheritance of metadata</h2>
 * A subclass never inherits the metadata of a {@link GreenScheduled} method declared on a superclass. For example, suppose the
 * class
 * {@code org.amce.Foo} is extended by the class {@code org.amce.Bar}. If {@code Foo} declares a non-static method annotated
 * with {@link GreenScheduled} then {@code Bar} does not inherit the metadata of the scheduled method.
 *
 * @see ScheduledExecution
 */
@Target(METHOD)
@Retention(RUNTIME)
@Repeatable(GreenScheduled.GreenSchedules.class)
public @interface GreenScheduled {

    /**
     * Optionally defines a unique identifier for this job.
     * <p>
     * If the value is not provided then a unique id is generated.
     *
     * @return the unique identity of the schedule
     */
    String identity() default "";

    /**
     * A custom expression to define the Fixed Window Scheduling constraints.
     * <p>
     * The format is as follows:
     *
     * <pre>
     * &lt;Time: start time&gt; &lt;Time: end time&gt;
     * </pre>
     *
     * For example: 9:30 11:45
     *
     * Requires duration to be configured
     */
    String fixedWindow() default "";

    /**
     * Timezone of the fixedWindow and cron timestamps
     * Default: systemDefault timezone
     * Format see {@link ZoneId}
     *
     */
    String timeZone() default "";

    /**
     * In cron quartz notation Day-of-month, leave empty if every day or Day-of-week must be used
     *
     *
     */
    String dayOfMonth() default "";

    /**
     * In cron quartz notation Day-of-week, leave empty if every day or Day-of-month must be used
     *
     *
     */
    String dayOfWeek() default "";

    /**
     * A custom expression to define the Successive Scheduling constraints.
     * <p>
     * The format is as follows:
     *
     * <pre>
     * &lt;Duration: Maximum initial gap&gt; &lt;Duration: Minimum gap&gt; &lt;Duration: Maximum gap&gt;
     * </pre>
     *
     * For example:
     *
     * <pre>
     * 0 2h 6h
     * </pre>
     *
     * Initial maximum delay: Defines the maximum amount of time before the first invocation
     * Minimum gap: Defines the minimum amount of time between the invocations
     * Maximum gap: Defines the maximum amount of time between the invocations
     *
     * <p>
     * The 'Duration' values are parsed with {@link Duration#parse(CharSequence)}. However, if an expression starts
     * with a digit and ends with 'd', "P" prefix will be added automatically. If the expression only starts with a
     * digit, "PT" prefix is added automatically, so for example, {@code 15m} can be used instead of {@code PT15M}
     * and is parsed as "15 minutes".
     * <p>
     * Note that the absolute value of the value is always used.
     * <p>
     *
     * When unable to determine a time window, an interval will be calculated as fallback ((min gap + max gap) / 2)
     */
    String successive() default "";

    /**
     * Cron expression quartz notation, just like @Scheduled. This is the fallback in case fixedWindow failed to determine a
     * time window.
     * When not set while using a fixedWindow, a daily cron is calculated to run in the middle of the fixed window.
     * i.e.: fixedWindow: "5:15 8:15" will generate the following cron: "0 45 6 * * ?"
     */
    String cron() default "";

    /**
     * Defines expected duration of the invocation.
     * <p>
     * The value is parsed with {@link Duration#parse(CharSequence)}. However, if an expression starts with a digit and ends
     * with 'd', "P" prefix will be added automatically. If the expression only starts with a digit, "PT" prefix
     * is added automatically, so for example, {@code 15m} can be used instead of {@code PT15M} and is parsed as "15 minutes".
     * Note that the absolute value of the value is always used.
     *
     * @return the period expression based on the ISO-8601 duration format {@code PnDTnHnMn.nS}
     */
    String duration() default "";

    /**
     * Defines the carbonIntensityZone for fetching carbon intensity data to use when scheduling.
     * <p>
     * The value are case-insensitive and format depends on the
     * {@link CarbonIntensityPlanner}.
     * <p>
     * The default scheduler supports the following options:
     * <ul>
     * ZoneId from <a href="https://carbonintensity.io">cabonintensity.io</a>; e.g. NL
     *
     * @return the carbonIntensityZone to use.
     */
    String carbonIntensityZone();

    /**
     * Specify the strategy to handle concurrent execution of a scheduled method. By default, a scheduled method can be executed
     * concurrently.
     *
     * @return the concurrent execution strategy
     */
    ConcurrentExecution concurrentExecution() default ConcurrentExecution.PROCEED;

    /**
     * Specify the predicate that can be used to skip an execution of a scheduled method.
     * <p>
     * The class must declare a public no-args constructor.
     *
     * @return the class
     */
    Class<? extends SkipPredicate> skipExecutionIf() default SkipPredicate.Never.class;

    /**
     * Defines a period after which the job is considered overdue.
     * <p>
     * The value is parsed with {@link Duration#parse(CharSequence)}. However, if an expression starts with a digit and ends
     * with 'd', "P" prefix will be added automatically. If the expression only starts with a digit, "PT" prefix
     * is added automatically, so for example, {@code 15m} can be used instead of {@code PT15M} and is parsed as "15 minutes".
     * Note that the absolute value of the value is always used.
     *
     * @return the period expression based on the ISO-8601 duration format {@code PnDTnHnMn.nS}
     */
    String overdueGracePeriod() default "";

    @Retention(RUNTIME)
    @Target(METHOD)
    @interface GreenSchedules {

        GreenScheduled[] value();

    }
}
