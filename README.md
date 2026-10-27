# Applied Metalworks V1.0.0

AE2 processing automation for Productive Metalworks on Minecraft 1.21.1 NeoForge.

- **ME Foundry Interface:** connects AE2 Pattern Providers to a formed foundry, with nine cast slots.
- **Extended ME Foundry Interface:** the same integration with 36 cast slots.
- **ME Foundry Automation Core:** a standalone foundry with melting, alloying and four parallel casting stations.
- AE2-style menus, acceleration cards, colored brick variants and optional AppliedFlux/Jade integration.

Machines process requests from attached AE2 Pattern Providers. Patterns remain in the provider.

## Requirements

- Minecraft 1.21.1
- NeoForge 21.1.217 or later
- Applied Energistics 2 19.2.17 or later for Minecraft 1.21.1
- Productive Metalworks 1.21.1-1.14.0 or later
- Java 21

Install the mod and its required dependencies on both the client and server. AppliedFlux and Jade are optional.

## Build

Windows:

```powershell
.\gradlew.bat build
```

Linux or macOS:

```sh
chmod +x gradlew
./gradlew build
```

The release JAR is written to `build/libs/AppliedMetalworks-1.0.0.jar`.
For development, use `runClient` or `runServer` with the Gradle wrapper.

## License

LGPL-3.0-or-later. See [LICENSE](LICENSE), [LICENSE-GPL](LICENSE-GPL) and [NOTICE.md](NOTICE.md).
