# `EntityAnno`

Utility tools for generating [`Mindustry`](https://github.com/Anuken/Mindustry) custom entity component classes. Offered
features:

- Nearly one-to-one integration of the entity component class generator in Mindustry, with an additional utility to
  properly register the entity class IDs for usage in UnitType`s and persistence across save files.
- Supports both release and bleeding-edge Mindustry version hashes.
- A [dedicated template](https://github.com/GlennFolker/MindustryModTemplate).

## Configuration

`:fetchComps` downloads the vanilla component sources from GitHub whenever the build runs. On networks where
`raw.githubusercontent.com` is unreachable, point it at a mirror:

```properties
# gradle.properties
downloadMirror = https://ghproxy.net
```

Alternatively, route Gradle through a proxy:

```properties
systemProp.https.proxyHost = 127.0.0.1
systemProp.https.proxyPort = 7890
```

Both are also settable on the `entityAnno` extension, if you prefer keeping them in the build script:

```kotlin
configure<ent.EntityAnnoExtension>{
    downloadMirror = "https://ghproxy.net"
}
```

| Property         | Default  | Description                                                                                       |
|------------------|----------|---------------------------------------------------------------------------------------------------|
| `downloadMirror` | *(unset)*| Proxy prefix for raw source downloads, prepended verbatim to the download URL.                     |
| `apiMirror`      | *(unset)*| Proxy prefix for GitHub API requests. Most mirrors only proxy raw downloads, not the API, so this is rarely needed. |
| `maxRetries`     | `3`      | Attempts per file, including the first one.                                                        |
| `threads`        | `4`      | Maximum number of concurrent downloads.                                                            |

## Contributing

This project is licensed under [GNU GPL v3](/LICENSE).

## Version Compatibility

| `Mindustry`/`Arc` | `EntityAnno`                 |
|-------------------|------------------------------|
| `v160.*`          | `v2.5.0+v160`, `v1.2.1+v160` |
| `v159.*`          | `v159.7.6`                   |
| `v158.*`          | `v158.0.0`                   |
| `v147.0`-`v157.4` | `v149.0.0`                   |
| `v146.*`          | `v146.0.11`                  |
| `v145.*`          | `1.1.2`                      |
| `v144.*`          | `1.0.0`                      |
