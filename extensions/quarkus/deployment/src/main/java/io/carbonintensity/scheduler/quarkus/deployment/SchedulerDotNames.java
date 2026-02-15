package io.carbonintensity.scheduler.quarkus.deployment;

import java.util.concurrent.CompletionStage;

import org.jboss.jandex.DotName;

import io.carbonintensity.scheduler.GreenScheduled;
import io.carbonintensity.scheduler.SkipPredicate;
import io.smallrye.common.annotation.NonBlocking;

class SchedulerDotNames {
    static final DotName SCHEDULED_NAME = DotName.createSimple(GreenScheduled.class.getName());
    static final DotName SCHEDULES_NAME = DotName.createSimple(GreenScheduled.GreenSchedules.class.getName());
    static final DotName SKIP_NEVER_NAME = DotName.createSimple(SkipPredicate.Never.class.getName());
    static final DotName SKIP_PREDICATE = DotName.createSimple(SkipPredicate.class.getName());
    static final DotName NON_BLOCKING = DotName.createSimple(NonBlocking.class.getName());
    static final DotName UNI = DotName.createSimple("io.smallrye.mutiny.Uni");
    static final DotName COMPLETION_STAGE = DotName.createSimple(CompletionStage.class.getName());
    static final DotName VOID = DotName.createSimple(Void.class.getName());
}
