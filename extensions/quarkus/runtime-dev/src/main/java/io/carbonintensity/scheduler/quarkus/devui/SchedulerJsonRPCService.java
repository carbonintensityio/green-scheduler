package io.carbonintensity.scheduler.quarkus.devui;

import java.time.Instant;
import java.time.LocalDateTime;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;

import org.jboss.logging.Logger;

import io.carbonintensity.scheduler.GreenScheduled;
import io.carbonintensity.scheduler.ScheduledExecution;
import io.carbonintensity.scheduler.Scheduler;
import io.carbonintensity.scheduler.Trigger;
import io.carbonintensity.scheduler.quarkus.common.runtime.ScheduledMethod;
import io.carbonintensity.scheduler.quarkus.common.runtime.SchedulerContext;
import io.carbonintensity.scheduler.quarkus.common.runtime.util.SchedulerUtils;
import io.carbonintensity.scheduler.runtime.ScheduledInvoker;
import io.quarkus.vertx.core.runtime.context.VertxContextSafetyToggle;
import io.smallrye.common.annotation.NonBlocking;
import io.smallrye.common.vertx.VertxContext;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

@ApplicationScoped
public class SchedulerJsonRPCService {

    private static final Logger LOG = Logger.getLogger(SchedulerJsonRPCService.class);

    private final BroadcastProcessor<JsonObject> runningStatus;
    private final BroadcastProcessor<JsonObject> log;
    private final Instance<SchedulerContext> context;
    private final Instance<Scheduler> scheduler;
    private final Instance<Vertx> vertx;

    public SchedulerJsonRPCService(Instance<SchedulerContext> context, Instance<Scheduler> scheduler, Instance<Vertx> vertx) {
        runningStatus = BroadcastProcessor.create();
        log = BroadcastProcessor.create();
        this.context = context;
        this.scheduler = scheduler;
        this.vertx = vertx;
    }

    public Multi<JsonObject> streamLog() {
        return log;
    }

    public Multi<JsonObject> streamRunningStatus() {
        return runningStatus;
    }

    @NonBlocking
    public JsonObject getData() {
        SchedulerContext c = context.get();

        JsonObject ret = new JsonObject();
        ret.put("schedulerRunning", scheduler.get().isRunning());

        JsonArray methodsJson = new JsonArray();
        ret.put("methods", methodsJson);
        for (ScheduledMethod metadata : c.getScheduledMethods()) {
            JsonObject methodJson = new JsonObject();
            methodJson.put("declaringClassName", metadata.getDeclaringClassName());
            methodJson.put("methodName", metadata.getMethodName());
            methodJson.put("methodDescription", metadata.getMethodDescription());
            JsonArray schedulesJson = new JsonArray();
            for (GreenScheduled schedule : metadata.getSchedules()) {
                JsonObject scheduleJson = new JsonObject();
                if (!schedule.identity().isBlank()) {
                    putConfigLookup("identity", schedule.identity(), scheduleJson);
                    scheduleJson.put("running", !scheduler.get().isPaused(schedule.identity()));

                }
                String cron = schedule.cron();
                if (!cron.isBlank()) {
                    putConfigLookup("cron", cron, scheduleJson);
                }
                if (!schedule.fixedWindow().isBlank()) {
                    putConfigLookup("fixedWindow", schedule.fixedWindow(), scheduleJson);
                }
                if (!schedule.successive().isBlank()) {
                    putConfigLookup("successive", schedule.successive(), scheduleJson);
                }

                schedulesJson.add(scheduleJson);
            }
            methodJson.put("schedules", schedulesJson);
            methodsJson.add(methodJson);
        }
        return ret;
    }

    @NonBlocking
    public JsonObject pauseScheduler() {
        Scheduler s = scheduler.get();
        if (!s.isRunning()) {
            return newFailure("Scheduler is already paused");
        }
        s.pause();
        LOG.info("Scheduler paused via Dev UI");
        return newSuccess("Scheduler was paused");
    }

    @NonBlocking
    public JsonObject resumeScheduler() {
        Scheduler s = scheduler.get();
        if (s.isRunning()) {
            return newFailure("Scheduler is already running");
        }
        s.resume();
        LOG.info("Scheduler resumed via Dev UI");
        return newSuccess("Scheduler was resumed");
    }

    @NonBlocking
    public JsonObject pauseJob(String identity) {
        Scheduler s = scheduler.get();
        if (s.isPaused(identity)) {
            return newFailure("Job with identity " + identity + " is already paused");
        }
        s.pause(identity);
        LOG.infof("Paused job with identity '%s' via Dev UI", identity);
        return newSuccess("Job with identity " + identity + " was paused");
    }

    @NonBlocking
    public JsonObject resumeJob(String identity) {
        Scheduler s = scheduler.get();
        if (!s.isPaused(identity)) {
            return newFailure("Job with identity " + identity + " is not paused");
        }
        s.resume(identity);
        LOG.infof("Resumed job with identity '%s' via Dev UI", identity);
        return newSuccess("Job with identity " + identity + " was resumed");
    }

    @NonBlocking
    public JsonObject executeJob(String methodDescription) {
        SchedulerContext c = context.get();
        for (ScheduledMethod metadata : c.getScheduledMethods()) {
            if (metadata.getMethodDescription().equals(methodDescription)) {
                Context vdc = VertxContext.getOrCreateDuplicatedContext(vertx.get());
                VertxContextSafetyToggle.setContextSafe(vdc, true);
                try {
                    ScheduledInvoker invoker = c.createInvoker(metadata.getInvokerClassName());
                    Runnable invocation = () -> {
                        try {
                            invoker.invoke(new DevUIScheduledExecution());
                        } catch (Exception ignored) {
                        }
                    };

                    if (invoker.isBlocking()) {
                        vdc.executeBlocking(() -> {
                            invocation.run();
                            return null;
                        }, false);
                    } else {
                        vdc.runOnContext(x -> invocation.run());
                    }

                    LOG.infof("Invoked scheduled method %s via Dev UI", methodDescription);
                } catch (Exception e) {
                    LOG.error(
                            "Unable to invoke a @Scheduled method: "
                                    + metadata.getMethodDescription(),
                            e);
                }
                return newSuccess("Invoked scheduled method " + methodDescription + " via Dev UI");
            }
        }
        return newFailure("Scheduled method not found " + methodDescription);
    }

    private JsonObject newSuccess(String message) {
        return new JsonObject()
                .put("success", true)
                .put("message", message);
    }

    private JsonObject newFailure(String message) {
        return new JsonObject()
                .put("success", false)
                .put("message", message);
    }

    private JsonObject newRunningStatus(String id, boolean running) {
        return new JsonObject()
                .put("id", id)
                .put("running", running);
    }

    private JsonObject newExecutionLog(Trigger trigger, boolean success, String message, boolean userDefinedIdentity) {
        JsonObject log = new JsonObject()
                .put("timestamp", LocalDateTime.now().toString())
                .put("success", success);
        String description = trigger.getMethodDescription();
        if (description != null) {
            log.put("triggerMethodDescription", description);
            if (userDefinedIdentity) {
                log.put("triggerIdentity", trigger.getId());
            }
        } else {
            // Always add identity if no method description is available
            log.put("triggerIdentity", trigger.getId());
        }
        if (message != null) {
            log.put("message", message);
        }
        return log;
    }

    private boolean isUserDefinedIdentity(String identity) {
        for (ScheduledMethod metadata : context.get().getScheduledMethods()) {
            for (GreenScheduled schedule : metadata.getSchedules()) {
                if (identity.equals(schedule.identity())) {
                    return true;
                }
            }
        }
        return false;
    }

    private void putConfigLookup(String key, String value, JsonObject scheduleJson) {
        scheduleJson.put(key, value);
        String configLookup = SchedulerUtils.lookUpPropertyValue(value);
        if (!value.equals(configLookup)) {
            scheduleJson.put(key + "Config", configLookup);
        }
    }

    /**
     * An instance of this class is passed during a manual invocation from DevUI
     * into a GreenScheduled method as its only parameter, if declared.
     * So the exact timing values specified here do not matter much,
     * most likely can only be used by the scheduled methods' bodies for logging.
     */
    private static class DevUIScheduledExecution implements ScheduledExecution {

        private final Instant now;

        DevUIScheduledExecution() {
            super();
            this.now = Instant.now();
        }

        @Override
        public Trigger getTrigger() {
            return new Trigger() {

                @Override
                public String getId() {
                    return "dev-console";
                }

                @Override
                public Instant getNextFireTime() {
                    return null;
                }

                @Override
                public Instant getPreviousFireTime() {
                    return now;
                }

                @Override
                public boolean isOverdue() {
                    return false;
                }

            };
        }

        @Override
        public Instant getFireTime() {
            return now;
        }

        @Override
        public Instant getScheduledFireTime() {
            return now;
        }

    }

}
