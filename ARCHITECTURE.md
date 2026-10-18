# Architecture notes

## Threading rules

OpenPnP runs on three kinds of thread:

- **EDT**: all Swing work, wizards, table models, configuration load and save.
- **Machine thread**: everything submitted with `Machine.submit`/`execute` or
  `UiUtils.submitUiMachineTask`, including the job processor, feeding, picking, placing and
  vision.
- **Camera threads**: frame capture and camera listeners.

Model objects are shared between them. Each piece of shared state follows exactly one of the
rules below; the comment on the field names which. Never catch `ConcurrentModificationException`
to work around a race. Fix the state instead.

| Rule | Use it for | Examples |
|---|---|---|
| **Immutable snapshot replaced atomically** | Small sets read often on another thread | `CompatibleNozzleTips`, `ReferenceFeeder` job fault window, `JobUsageService` snapshot |
| **Copy-on-write** | Lists edited on the EDT and iterated on the machine thread | every `IdentifiableList` (machine heads, cameras, actuators, feeders, nozzle tips; head nozzles; board placements; Photon slots), machine listeners |
| **Concurrent map** | Maps written per operation on the machine thread | `Job` placed/enabled/check-fiducials/error-handling maps, machine properties |
| **Guarded by the collection's own monitor, copies out** | Configuration registries | `Configuration` packages, parts and vision settings |
| **`volatile`** | Single fields written on one thread, read on another | `AbstractFeeder.enabled`, `AbstractNozzle.part`, `ReferenceNozzle.nozzleTip`, `PhotonFeeder.slotAddress` |
| **Confined to the EDT** | State only the GUI changes | `Configuration` boards and panels, `Solutions` issues |

### Getters

- `IdentifiableList` getters (e.g. `getHeads()`, `getNozzles()`) return live unmodifiable views of
  a copy-on-write list. Iterating them is always safe. Indexing across calls is not, because the
  list can change in between.
- `Machine.getFeeders()` and the `Configuration` getters return a snapshot.

### Events and Swing

Property change events fire on the thread that made the change, which is often the machine thread.

- A listener that touches Swing must hand the work to the EDT (`SwingUtilities.invokeLater`).
- `JBindings` wrappers do this for wizard bindings.
- `Configuration` delivers its packages, parts and vision settings events on the EDT, with a copy of
  the map.
- `ModelChangeService` delivers on the EDT.

### Saving

- `Configuration.save()` runs on the EDT, because it may ask whether to save modified boards and
  panels.
- Everything it serialises follows one of the rules above, so it is safe while the machine thread
  keeps working.
- The saved files are a view of each collection, not one atomic snapshot of the whole
  configuration.
