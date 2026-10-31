package org.openpnp.mcp.tools;

import java.util.concurrent.Callable;

import org.openpnp.spi.Machine;

final class MachineTasks {
    private MachineTasks() {
    }

    /**
     * Runs a task on the machine thread, as feeds and alignments run, so camera settling and
     * lights don't race a jog or a job. A disabled machine runs no tasks and can't move, so the
     * task runs on the caller's thread instead, which lets saved frames be tuned offline.
     */
    static <T> T call(Machine machine, Callable<T> task) throws Exception {
        if (!machine.isEnabled()) {
            return task.call();
        }
        return machine.execute(task, false, 0);
    }
}
