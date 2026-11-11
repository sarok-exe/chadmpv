# <img alt="app icon" src=".github/assets/app_icon.svg" width="48" /> chadmpv

A media player for Android based on [mpv-android](https://github.com/mpv-android/mpv-android), built on libmpv.
chadmpv is a personal fork that combines the player UI of **mpvKt** with the extra playback features of **mpvEx**,
and adds its own library caching, fast native video scanning, and a queue-based prev/next player.

## Features

- Nicer player UI with centered previous/next controls
- Cached video library with instant startup and background rescanning
- Playback queue: skip to the previous/next video in the folder, with optional auto play next
- Auto play next video toggle in the player controls
- Better playback history implementation
- Easier customization
- Sleep timer, speed presets
- Smoother PiP

## Build

Requires JDK 21 and Gradle 8.14.x. The Rust scanner (`rust/`) is cross-compiled by the
`buildRustScanner` Gradle task:

```sh
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
./gradlew :app:assembleDebug --no-daemon --max-workers=1 \
  -Dorg.gradle.jvmargs="-Xmx1024m -XX:MaxMetaspaceSize=512m" \
  -Dkotlin.daemon.jvmargs="-Xmx768m"
```

## Credits

chadmpv would not exist without the projects it is built on. Full credit goes to their authors:

- **[mpvKt](https://github.com/abdallahmehiz/mpvKt)** by [abdallahmehiz](https://github.com/abdallahmehiz) —
  the upstream project this fork is based on. Most of the player UI, preferences, and architecture
  originate here.
- **[mpvEx / mpvExtended](https://github.com/marlboro-advance/mpvEx)** by
  [marlboro-advance](https://github.com/marlboro-advance) — source of the extended playback features
  (playback history, PiP behaviour, and more) that were merged into this fork.
- **[mpv-android](https://github.com/mpv-android/mpv-android)** — the libmpv-based Android player and JNI
  bindings that power playback in all of the projects above.

Modifications, additional features, and the chadmpv name are by **sarok**.

## License

Inherited from the upstream projects; see [LICENSE](LICENSE).
