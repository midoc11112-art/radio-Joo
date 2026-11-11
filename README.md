# Radio-Joo

Arabic / Egyptian internet radio for Android with a self-updating cloud brain.

* `app/` – Android app (Kotlin, Media3). Failover between up to 5 servers per station, lowest bitrate first,
  audio-only, tiny buffers, pause = disconnect, learns which servers are fast and reliable.
* `worker/` – Cloudflare Worker. Discovers stations (radio-browser + curated seeds), verifies every stream,
  keeps ranked servers, heals dead stations, learns from phone reports. Runs by itself on a cron trigger.
* `.github/workflows/build.yml` – builds the debug APK on every push.
