# Compilation-only CI distributions

Generated from `versions.json` with `python tools/distributions.py table`.

Each row is one individually downloadable artifact from **Signed development release**. Pushes to main sign and publish successful consolidated builds; pull requests and manual runs compile only. No test servers start.

Range filenames show target endpoints, not verification of every intermediate patch or every Paper/Spigot pairing. Exact runtime status remains in [SUPPORT.md](SUPPORT.md) and each artifact includes `validation.json` with its current SHA-256 and matching prior evidence.

| JAR | Exact configured targets | Build / compiler Java |
| --- | --- | --- |
| `DropsOverhaul-paper-spigot-mc1.19-to-26.3-4.2.jar` | paper-1.21.11, spigot-1.19, spigot-1.19.4, spigot-1.19.1, spigot-1.19.2, spigot-1.19.3, spigot-1.20, spigot-1.20.5, spigot-1.20.6, spigot-1.21, spigot-1.21.11, spigot-26.1, spigot-26.1.2, spigot-26.2, spigot-26.3 | 21 / 17 |
| `DropsOverhaul-spigot-mc1.12.2-4.2.jar` | spigot-1.12.2 | 21 / 8 |
| `DropsOverhaul-forge-mc1.12.2-4.2.jar` | forge-1.12.2 | 8 / 8 |
| `DropsOverhaul-forge-mc1.21.11-4.2.jar` | forge-1.21.11 | 25 / 21 |
| `DropsOverhaul-forge-mc1.19-to-1.19.4-4.2.jar` | forge-1.19, forge-1.19.4, forge-1.19.1, forge-1.19.2, forge-1.19.3 | 17 / 17 |
| `DropsOverhaul-forge-mc1.20-4.2.jar` | forge-1.20 | 17 / 17 |
| `DropsOverhaul-forge-mc1.20.6-to-1.21-4.2.jar` | forge-1.20.6, forge-1.21 | 25 / 21 |
| `DropsOverhaul-forge-mc26.1-to-26.2-4.2.jar` | forge-26.1, forge-26.1.2, forge-26.2 | 25 / 25 |
| `DropsOverhaul-forge-mc26.3-4.2.jar` | forge-26.3 | 25 / 25 |

Recorded tests of the consolidated JARs are listed below. These results apply to the SHA-256 in each evidence file; a fresh CI build claims a prior runtime result only when its bytes match. Standalone JAR results in SUPPORT.md do not automatically validate a merged JAR.

| Target | Consolidated JAR validation | Evidence | Limitations / failure |
| --- | --- | --- | --- |
| paper-1.21.11 | gameplay-tested | [validation/paper-1.21.11-shared-bukkit.json](validation/paper-1.21.11-shared-bukkit.json) |  |
| forge-1.19 | gameplay-tested | [validation/forge-1.19-ci-merged.json](validation/forge-1.19-ci-merged.json) |  |
| forge-1.19.4 | gameplay-tested | [validation/forge-1.19.4-ci-merged.json](validation/forge-1.19.4-ci-merged.json) |  |
| forge-1.20.6 | failed | [validation/forge-1.20.6-ci-merged.json](validation/forge-1.20.6-ci-merged.json) | Gameplay test failed: Mineflayer play.declare_recipes / ArmorTrimMaterial decoder error. Original unmerged JAR fails the same control; no gameplay pass is claimed for this merged artifact. |
| forge-1.21 | gameplay-tested | [validation/forge-1.21-ci-merged.json](validation/forge-1.21-ci-merged.json) | Mineflayer recipe decoding; crafting is not tested. Drop and inventory assertions use separate packets. |
| forge-26.1 | gameplay-tested | [validation/forge-26.1-ci-merged.json](validation/forge-26.1-ci-merged.json) |  |
| forge-26.1.2 | gameplay-tested | [validation/forge-26.1.2-ci-merged.json](validation/forge-26.1.2-ci-merged.json) |  |
| forge-26.2 | startup-tested | [validation/forge-26.2-ci-merged.json](validation/forge-26.2-ci-merged.json) | Pinned Mineflayer 4.39.0 / minecraft-data 3.119.0 has no usable protocol data for Minecraft 26.2. Gameplay and vanilla-client connectivity remain unverified. |

The inventory artifact also lists advertised exact candidates with no implemented build recipe. Those remain unimplemented, including Forge versions for which no native distribution exists.

Shared Forge JARs are allowed only when every member recipe compiles and all non-loader-metadata payload entries match byte for byte. Their loader metadata declares exact Minecraft and Forge-build sets. A payload mismatch fails that group and requires separate JARs; other groups continue.

The standalone Paper build remains independently buildable. The default push-to-main release signs successful consolidated JARs, their validation reports, DISTRIBUTIONS.json, and SHA256SUMS. Missing groups are reported explicitly. Development publication does not promote runtime support or legacy releaseEligible flags.
