# AdaptiveRangeMobSpawner - Paper 26.2 / Java 25

This is a Paper-26.2-specific refresh of Block4Block/AdaptiveRangeMobSpawner.

## Changes in this build

- Targets Paper API 26.2 and Java 25.
- Uses Paper's direct `Bukkit.getTPS()` API; removed legacy reflection/NMS TPS fallbacks.
- Removed obsolete Paper detection and async-chunk reflection code.
- Never intentionally loads an unloaded chunk just to scan spawners.
- Fixes `affect-naturally-generated` so enabling it actually applies the adaptive range to natural spawners.
- Removes the one-minute processed-chunk cache that could prevent a newly changed range from being applied immediately.
- Reloading now restarts the TPS task, so a changed `update-interval-ticks` takes effect.
- Uses `api-version: '26.2'`.

## Build

Requires JDK 25 and Maven:

```bash
mvn clean package
```

Output:

`target/AdaptiveRangeMobSpawner-1.2.0-26.2.jar`
