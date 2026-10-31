package org.openpnp.mcp.tools;

import org.openpnp.spi.Camera;
import org.openpnp.spi.Head;
import org.openpnp.spi.Machine;

final class CameraLookup {
    private CameraLookup() {
    }

    /** The named camera, or the head's top camera when no name is given. */
    static Camera find(Machine machine, String name) throws Exception {
        if (name == null) {
            return machine.getDefaultHead().getDefaultCamera();
        }
        for (Camera camera : machine.getCameras()) {
            if (camera.getName().equals(name)) {
                return camera;
            }
        }
        for (Head head : machine.getHeads()) {
            for (Camera camera : head.getCameras()) {
                if (camera.getName().equals(name)) {
                    return camera;
                }
            }
        }
        throw new IllegalArgumentException("No camera " + name);
    }
}
