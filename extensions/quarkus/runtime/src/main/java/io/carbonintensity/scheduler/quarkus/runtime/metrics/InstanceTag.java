package io.carbonintensity.scheduler.quarkus.runtime.metrics;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;

/**
 * A lightweight, process-lifetime-stable identifier for the {@code instance} tag on every
 * {@code green.scheduler.job.*} meter - just enough to tell two application instances of the same job apart on a
 * dashboard. Deciding which instance is the "leader" for a given job is a separate, unsolved problem and out of
 * scope here.
 */
final class InstanceTag {

    private InstanceTag() {
    }

    /**
     * Prefers the {@code HOSTNAME} environment variable (set by convention in most container runtimes), then the
     * resolved local hostname, then falls back to a random id generated once per process - resolution never fails,
     * so every started instance gets a usable tag value.
     */
    static String resolve() {
        String hostnameEnv = System.getenv("HOSTNAME");
        if (hostnameEnv != null && !hostnameEnv.isBlank()) {
            return hostnameEnv;
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown-" + UUID.randomUUID();
        }
    }
}
