package io.carbonintensity.scheduler.test.helper;

import java.lang.annotation.Annotation;
import java.util.Objects;

import io.carbonintensity.scheduler.ConcurrentExecution;
import io.carbonintensity.scheduler.GreenScheduled;
import io.carbonintensity.scheduler.SkipPredicate;

public class AnnotationUtil {

    public static GreenScheduledBuilder newGreenScheduled() {
        return new GreenScheduledBuilder();
    }

    public static class GreenScheduledBuilder {

        private String identity = "";
        private String fixedWindow = "";
        private String timeZone = "";
        private String dayOfMonth = "";
        private String dayOfWeek = "";
        private String successive = "";
        private String cron = "";
        private String duration = "";
        private String zone = null;
        private ConcurrentExecution concurrentExecution = ConcurrentExecution.PROCEED;
        private Class<? extends SkipPredicate> skipExecutionIf = SkipPredicate.Never.class;
        private String overdueGracePeriod = "";

        public GreenScheduledBuilder identity(String identity) {
            if (identity != null) {
                this.identity = identity;
            }
            return this;
        }

        public GreenScheduledBuilder fixedWindow(String fixedWindow) {
            if (fixedWindow != null) {
                this.fixedWindow = fixedWindow;
            }
            return this;
        }

        public GreenScheduledBuilder timeZone(String timeZone) {
            if (timeZone != null) {
                this.timeZone = timeZone;
            }
            return this;
        }

        public GreenScheduledBuilder dayOfMonth(String dayOfMonth) {
            if (dayOfMonth != null) {
                this.dayOfMonth = dayOfMonth;
            }
            return this;
        }

        public GreenScheduledBuilder dayOfWeek(String dayOfWeek) {
            if (dayOfWeek != null) {
                this.dayOfWeek = dayOfWeek;
            }
            return this;
        }

        public GreenScheduledBuilder successive(String successive) {
            if (successive != null) {
                this.successive = successive;
            }
            return this;
        }

        public GreenScheduledBuilder cron(String cron) {
            if (cron != null) {
                this.cron = cron;
            }
            return this;
        }

        public GreenScheduledBuilder duration(String duration) {
            if (duration != null) {
                this.duration = duration;
            }
            return this;
        }

        public GreenScheduledBuilder zone(String zone) {
            this.zone = zone;
            return this;
        }

        public GreenScheduledBuilder concurrentExecution(ConcurrentExecution concurrentExecution) {
            if (concurrentExecution != null) {
                this.concurrentExecution = concurrentExecution;
            }
            return this;
        }

        public GreenScheduledBuilder skipExecutionIf(Class<? extends SkipPredicate> skipExecutionIf) {
            if (skipExecutionIf != null) {
                this.skipExecutionIf = skipExecutionIf;
            }
            return this;
        }

        public GreenScheduledBuilder overdueGracePeriod(String overdueGracePeriod) {
            if (overdueGracePeriod != null) {
                this.overdueGracePeriod = overdueGracePeriod;
            }
            return this;
        }

        public GreenScheduled build() {
            Objects.requireNonNull(zone, "Zone cannot be null");
            return new GreenScheduled() {
                @Override
                public Class<? extends Annotation> annotationType() {
                    return GreenScheduled.class;
                }

                @Override
                public String identity() {
                    return identity;
                }

                @Override
                public String fixedWindow() {
                    return fixedWindow;
                }

                @Override
                public String timeZone() {
                    return timeZone;
                }

                @Override
                public String dayOfMonth() {
                    return dayOfMonth;
                }

                @Override
                public String dayOfWeek() {
                    return dayOfWeek;
                }

                @Override
                public String successive() {
                    return successive;
                }

                @Override
                public String cron() {
                    return cron;
                }

                @Override
                public String duration() {
                    return duration;
                }

                @Override
                public String zone() {
                    return zone;
                }

                @Override
                public ConcurrentExecution concurrentExecution() {
                    return concurrentExecution;
                }

                @Override
                public Class<? extends SkipPredicate> skipExecutionIf() {
                    return skipExecutionIf;
                }

                @Override
                public String overdueGracePeriod() {
                    return overdueGracePeriod;
                }
            };
        }
    }
}
