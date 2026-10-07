# Third-party components

WifiScreen's independent application code is licensed under MIT. Dependencies retain their own licenses and notices.

| Component | License | Source |
| --- | --- | --- |
| AndroidX Core / AppCompat | Apache-2.0 | https://android.googlesource.com/platform/frameworks/support/ |
| AndroidX Media3 (experimental WFD playback path) | Apache-2.0 | https://github.com/androidx/media |
| JmDNS | Apache-2.0 | https://github.com/jmdns/jmdns |
| Kotlin standard library / compiler | Apache-2.0 | https://github.com/JetBrains/kotlin |
| Gradle / Gradle Wrapper | Apache-2.0 | https://github.com/gradle/gradle |
| JUnit 4 (tests only) | EPL-1.0 | https://github.com/junit-team/junit4 |

Dependency versions are specified in the Gradle build files. The Android runtime's codecs and platform components are supplied by the receiving device.
