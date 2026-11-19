\# AGENTS.md — DropsOverhaul



\## Project Purpose



This repository is a fork of `FrankTCA/DropsOverhaul`, a Minecraft server plugin designed to reduce item-entity creation and associated server lag by handling selected block drops directly.



The primary development objective is to port DropsOverhaul to Minecraft Forge while preserving the existing Spigot/Paper plugin as a fully functional, independently buildable distribution.



Both platforms must share a common codebase and maintain behavioral and Minecraft-version parity.



\*\*Minecraft 1.12.2 support is a mandatory development target for both platforms.\*\*



This repository is not intended to abandon, replace, or supersede the original plugin implementation.



\## Core Requirements



1\. Preserve the existing Spigot/Paper implementation.

2\. Add a native Forge server implementation.

3\. Share platform-independent gameplay logic.

4\. Avoid maintaining duplicate implementations of the same gameplay rules.

5\. Maintain equivalent functionality across supported platforms.

6\. Maintain Minecraft-version parity wherever technically feasible.

7\. Support Minecraft 1.12.2 on both platforms.

8\. Produce independently installable plugin and mod JARs.

9\. Preserve compatibility with existing configuration files wherever practical.

10\. Keep both implementations suitable for dedicated Minecraft servers.



\## Architecture



Prefer a modular architecture separating shared logic from platform-specific integrations.



Suggested conceptual layout:



```text

common/

&#x20; Shared drop-handling logic

&#x20; Block classification

&#x20; Configuration models

&#x20; Version-independent rules



paper/

&#x20; Bukkit/Paper event adapters

&#x20; Plugin initialization

&#x20; Permission and command registration

&#x20; Paper-specific configuration integration



forge/

&#x20; Forge event adapters

&#x20; Mod initialization

&#x20; Forge-specific configuration integration

&#x20; Server lifecycle integration



build.gradle

settings.gradle

```



This is a suggested structure, not a mandatory directory layout.



Inspect the existing code before restructuring it. Prefer incremental refactoring over unnecessary rewrites.



\### Shared Code



Shared code must not directly depend on Bukkit, Paper, Spigot, Forge, or version-specific Minecraft classes.



Use small interfaces or adapters to abstract platform behavior.



Do not create a large abstraction framework where simple interfaces are sufficient.



\### Platform Implementations



Paper and Forge implementations should translate native events and game objects into shared operations.



Use native platform APIs.



Do not require Bukkit, Spigot, Paper, Sponge, or a compatibility bridge to run the Forge edition.



Do not embed a Forge runtime in the Paper edition.



Each output must be a genuine native artifact for its target platform.



\## Gameplay Behavior



Preserve the original drop-handling philosophy:



\- Prevent unnecessary item entities from being created.

\- Handle the intended block-drop categories.

\- Preserve legitimate vanilla behavior for blocks outside the intended scope.

\- Avoid item duplication.

\- Avoid item loss.

\- Preserve relevant inventory and item-drop behavior.

\- Respect applicable game rules, tool mechanics, and player interactions.

\- Avoid unnecessary performance overhead.



Do not simply cancel all block drops and recreate approximate substitutes.



Use the appropriate event and loot mechanisms for each Minecraft version.



Pay particular attention to:



\- Silk Touch and Fortune.

\- Creative mode.

\- Full inventories.

\- Block-breaking cancellation by other mods or plugins.

\- Custom block drops.

\- Tool durability.

\- Server-side event ordering.

\- Version-specific block IDs and material names.

\- Compatibility with other server modifications.



For Minecraft 1.12.2, account for legacy block metadata and pre-flattening material representations.



Create compatibility mappings when newer Minecraft block identifiers do not exist in older versions.



Never silently substitute unrelated blocks.



\## Minecraft Version Support



Maintain an explicit support matrix documenting:



\- Minecraft version.

\- Paper/Spigot implementation status.

\- Forge implementation status.

\- Required Java version.

\- Build task.

\- Known limitations.



Minecraft 1.12.2 must be included as a target for both platforms.



For every version claimed as fully supported, both distributions must compile and pass the available compatibility tests.



When one platform cannot support a feature identically, document the difference and provide the closest faithful implementation.



Do not silently drop support for older versions when adding newer ones.



Do not confuse Forge with NeoForge. NeoForge is not a substitute for the required Forge support.



Do not assume a single JAR can run across all Minecraft versions.



\## Build System



Preserve the existing Gradle wrapper and original Paper build path where possible.



The existing plugin must remain buildable without requiring a Forge development environment.



Introduce additional source sets, modules, or independent Gradle builds as appropriate.



Requirements:



\- Paper and Forge artifacts must build independently.

\- Shared code must compile against the correct target.

\- Legacy Minecraft builds must use compatible Java and ForgeGradle versions.

\- Modern toolchains must not force upgrades on legacy targets.

\- Version-specific build configurations are acceptable.

\- All required build commands must be documented.

\- Do not package server APIs into distributable artifacts unnecessarily.



Avoid upgrading Gradle, Java, or project dependencies unless required for a specific supported target.



If a multi-project Gradle architecture cannot reasonably accommodate all legacy toolchains, use isolated builds with shared source dependencies rather than forcing incompatible versions together.



\## CI/CD



Retain existing Forgejo workflows unless changes are necessary.



Use the registered Linux runner infrastructure.



CI must eventually validate each supported platform/version combination, using a single runner to execute jobs sequentially where appropriate.



Do not require multiple physical build servers.



Expected artifacts must clearly identify:



\- Project.

\- Platform.

\- Minecraft version.

\- Project version or Git commit.



Preserve the repository's existing release-signing conventions, including SHA-256 manifests and detached GPG signatures.



Never expose signing secrets to untrusted pull-request builds.



Do not publish an artifact for a target that failed compilation.



\## Testing and Validation



Before declaring platform parity, verify:



1\. Both distributions compile.

2\. Both load on their intended dedicated servers.

3\. Configurations are parsed correctly.

4\. Block-drop handling matches intended behavior.

5\. Inventory-full behavior is correct.

6\. Silk Touch and Fortune behavior is correct.

7\. No item duplication or item loss occurs.

8\. Unsupported blocks retain vanilla behavior.

9\. Server startup and shutdown are clean.

10\. No client-side mod installation is required for the Forge edition.



Compilation alone is not proof of runtime compatibility.



Distinguish automated validation from manual server testing.



\## Agent Working Rules



\- Inspect the existing implementation before changing architecture.

\- Preserve original behavior unless explicitly instructed otherwise.

\- Favor shared logic over duplicated platform implementations.

\- Preserve the original plugin build.

\- Implement changes incrementally.

\- Avoid unnecessary dependency upgrades.

\- Do not remove existing features to simplify the Forge port.

\- Do not introduce a mandatory client-side dependency.

\- Do not replace legacy support with modern-only APIs.

\- Do not claim compatibility without evidence.

\- Do not alter release automation without a demonstrated need.

\- Review existing licensing terms before redistributing modified artifacts.



After substantial changes, report which platforms and versions were tested, which builds passed, and which remain incomplete.



\## Development Priority



1\. Establish and document the original Paper build baseline.

2\. Identify reusable gameplay logic.

3\. Separate shared logic from Bukkit/Paper dependencies.

4\. Preserve and validate the original plugin.

5\. Implement native Forge support for Minecraft 1.12.2.

6\. Implement Paper/Spigot compatibility for Minecraft 1.12.2.

7\. Expand Forge support to the remaining supported Minecraft versions.

8\. Establish and validate the platform/version support matrix.

9\. Integrate both distributions with Forgejo CI and releases.



Do not mark the port complete until both platform builds are operational and the required Minecraft 1.12.2 targets have been validated.



\*\*Long-term objective:\*\* DropsOverhaul remains one maintained project producing native Paper/Spigot and Forge server distributions with shared gameplay behavior and aligned Minecraft-version support.



