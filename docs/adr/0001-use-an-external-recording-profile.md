# Use an external recording profile

The recorder repository builds one self-contained recording profile for Airicraft evaluation. Airicraft accepts this profile instead of building, fetching, or validating recorder components. The scenario runner enables integrated-server capture by default and supports an explicit opt-out.

Each recorded scenario writes one completed Recorder Play directly into its result directory. The runner stops the scenario client normally and stores only the relative `recorderPlayPath` after finalization. A missing path proves that no finalized Play was found. Capture failure preserves the scenario outcome and fails the harness outcome. Airicraft adds no special interruption or sensitivity handling. It also does not generate derived recorder artifacts or add tick-level event correlation.
