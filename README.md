[![Issues][issues-shield]][issues-url]

<br />
<div align="center">
  <a href="https://github.com/FrankTCA/DropsOverhaul">
    <img src="logo.png" alt="Logo" width="291" height="100">
  </a>

<h3 align="center">Drops Overhaul</h3>

  <p align="center">
    A concept lag plugin that prevents drops before they happen
    <br />
    <a href="https://www.spigotmc.org/resources/dropsoverhaul.91000/"><strong>Download plugin »</strong></a>
    <br />
    <br />
    <a href="https://infotoast.org/">See our website</a>
    ·
    <a href="https://github.com/FrankTCA/DropsOverhaul/issues">Report Bugs</a>
  </p>
</div>

DropsOverhaul provides a Bukkit plugin and a native Forge server mod. Exact
runtime coverage and remaining limitations are recorded in [SUPPORT.md](SUPPORT.md).

Rather than having the drops be on the ground, DropsOverhaul simply *puts them in your inventory*

To prevent bugs, DropsOverhaul only handles junk blocks, and lets vanilla handle the rest.

## List of Junk Blocks
- Dirt
- Stone
- Sand
- Sandstone
- Diorite
- Granite
- Andesite
- Gravel
- Netherrack
- Blackstone
- Basalt
- Grass
- Tuff
- Calcite
- (Cobbled) Deepslate
- Smooth Basalt


## How to Use
1. Download a jar from <a href="https://www.spigotmc.org/resources/dropsoverhaul.91000/">SpigotMC</a>
2. Place in `plugins` folder.
3. Restart server.

## Installation/Building
This fork produces independent Bukkit plugins and native, server-only Forge mods.
See [SUPPORT.md](SUPPORT.md) for the exact validation matrix, build commands,
artifact names, Java requirements, and planned targets. `versions.json` is the
source of truth; advertised upstream version groups are not automatically verified
by this fork. Runtime evidence is recorded under [validation](validation).

The original Paper build remains at the repository root: set `JAVA_HOME` to JDK 21
and run `./gradlew build` (Windows: `gradlew.bat build`). It does not configure or
download Forge. Shared sources are also checked against Java 8.

Spigot 1.12.2 uses `./gradlew -p legacy-spigot build` with build JDK 21 and compiler
JDK 8 (`JAVA_HOME_8_X64`). Modern Spigot uses `./gradlew -p modern-spigot build`
with build JDK 21 and compiler JDK 17 (`JAVA_HOME_17_X64`). Each plugin belongs in
the matching server's `plugins` directory.
Modern Spigot targets share the JAR compiled against API 1.19; its filename names
that baseline. Only the exact runtime-tested patches in the matrix are verified,
including when several rows reference the same binary.

Forge 1.12.2 has an isolated Gradle 4.9 / ForgeGradle 3 build:
`bash forge-1.12.2/gradlew -p forge-1.12.2 build`, using JDK 8. Forge 1.20.6, 1.21,
and 1.21.11 share an isolated Gradle 9.5 / ForgeGradle 7 build:
`bash forge-1.21.11/gradlew -p forge-1.21.11 build`, using build JDK 25 and compiler
JDK 21 (`JAVA_HOME_21_X64`). Windows users can use each corresponding `gradlew.bat`.
Select another exact target with `-PtargetId=forge-1.20.6` or
`-PtargetId=forge-1.21`; the default stays 1.21.11. Minecraft 1.21 is pinned to
Forge 51.0.5. Its loader metadata rejects other Forge builds; the observed
51.0.33 configuration-watcher shutdown failure is preserved in the matrix's
known limitations and failure evidence.
The same build selects `-PtargetId=forge-26.1` with compiler and server JDK 25.
That manifest target selects ForgeGradle 7.0.17 and the unobfuscated Minecraft
dependency layout; earlier targets retain ForgeGradle 7.0.3. Its current validation
state is recorded in the matrix, independently of compilation.
Minecraft 26.1.2 uses the same Java 25 build with its own manifest target.
The 26.2 and 26.3 targets are limited to startup-only validation: the pinned
Mineflayer client has no usable protocol definitions for them. The manifest records
this as `validationMode: startup-only` with an explicit gameplay blocker. See
[client capability evidence](validation/client-capabilities.json). Startup-only
results verify the exact server, successful DropsOverhaul initialization, a working
configuration command, and clean shutdown; they do not establish gameplay parity
or vanilla-client connectivity on those versions.
Forge 1.19, 1.19.4, and 1.20 share an isolated Gradle 7.4.2 / ForgeGradle 5 build:
`bash forge-1.19/gradlew -p forge-1.19 build -PtargetId=forge-1.19.4`, using JDK 17.
Always select the exact manifest target; its Minecraft and Forge pins determine
the compiled code and loader metadata.
Only the manifest-named distribution is installable; `unshaded-dev` and fixture
JARs are development outputs. Put the Forge distribution in `mods`, without a
Bukkit bridge or a client installation of DropsOverhaul.

Forge reads `config/DropsOverhaul/config.yml`, with the existing schema-2 Bukkit
keys and defaults. Existing schema-2 configuration can be copied there. Invalid
Forge configurations fail visibly instead of overwriting the file. Administrative
saves retain unknown keys but rewrite YAML formatting/comments. Vanilla names use
uppercase Bukkit-style identifiers; modded Forge blocks use `namespace:block`.
Forge retains configured help, list, success, and cleanup messages. Command syntax
and permission errors use the native Forge/Minecraft command system's messages.
Unavailable legacy block names remain inactive; they are not replaced by unrelated
blocks. Legacy stone variants use explicit metadata mappings.

`Full-Inventory-Delete: true` intentionally deletes selected overflow, preserving
the original default. Set it to `false` to drop overflow at the player. Unselected
blocks retain native behavior. Both editions consume native calculated loot;
Spigot 1.12.2 intercepts actual item-spawn events only inside the pinned native
harvest call path and never disables block drops in advance.

### Repeatable runtime validation

The manual **Minecraft compatibility test** workflow preserves explicit platform,
exact Minecraft version, build task, artifact, Forge build, separate build/server
JDK, expected ID, and startup timeout inputs. Manifest targets run gameplay tests;
explicit exploratory targets run startup-only probes. Loading is verified in
addition to server readiness. The **Manual exact Minecraft matrix** workflow runs
Tier 1 boundaries or exhaustive Tier 2 candidates sequentially on one Linux runner,
independently from development builds. Missing implementations/distributions are
reported as blocked; installer and runtime failures are failed. A failed target
does not prevent the remaining matrix targets from running.

For local tests, build the target and its `runtimeTestJar` task, copy
`tests/client/package*.json` to `build/test-client`, and run
`npm ci --prefix build/test-client`. Then run:

```text
python tests/install_server.py --target <manifest-id> --java <server-java-executable>
python tests/run_paper.py --target <manifest-id> --java <server-java-executable>
```

The runner's historical filename also covers Spigot and Forge. Generated servers,
worlds, console/client logs, installer receipts, and structured results stay under
`build/runtime/<manifest-id>`. Tests explicitly accept the Minecraft EULA in that
isolated directory. They use an offline test player on loopback only; these settings
must not be copied to a production server. BuildTools uses process-local Git signing
configuration solely for its generated upstream checkouts.
Test servers select Java NIO (`use-native-transport=false`) for a consistent
cross-platform transport. Native epoll/kqueue transport is outside this validation.

`python tools/record_validation.py --target <id> --result <result.json>` explicitly
records evidence only when its SHA-256 matches the current distribution. It never
enables release eligibility. Regenerate the matrix with
`python tools/targets.py table --output SUPPORT.md`.

### Release and licensing

The existing Paper development release, SHA-256 manifest, and detached GPG signing
flow remain in place. Only targets explicitly marked `releaseEligible` enter
`stageReleaseArtifacts`; compiling another target does not publish it. New targets
remain ineligible. Signing credentials are confined to the existing trusted push
release job, never the manual test or pull-request jobs.

The repository's restrictive [LICENSE](LICENSE.md) is unchanged. Local implementation
and test outputs do not establish permission to publicly redistribute modified
artifacts; confirm the required rights before publication.

[issues-shield]: https://img.shields.io/github/issues/FrankTCA/LoraxCustomTrees.svg?style=for-the-badge
[issues-url]: https://github.com/FrankTCA/LoraxCustomTrees/issues
[license-shield]: https://img.shields.io/github/license/FrankTCA/LoraxCustomTrees.svg?style=for-the-badge
[license-url]: https://github.com/PG85/OpenTerrainGenerator/blob/master/LICENSE.md
