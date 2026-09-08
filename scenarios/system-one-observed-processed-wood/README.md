# Observed processed wood

The evaluator supplies an exposed stripped oak log on the isolated platform and no inventory items. The agent must observe, harvest, collect, and craft it into four oak planks. The recorded Minecraft knowledge must declare this log `OBSERVED_ONLY`; natural oak logs retain `LOCAL_SURVEY` and leaf indicators.

This is a positive regression for using processed blocks when actually observed. Unit tests separately cover refusing absent processed-resource surveys and excluding them from discoverable recipe costs. A fixture pass alone does not prove the absence of all unsupported searches.
