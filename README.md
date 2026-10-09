# GW2 Tab Converter

Converts MuseScore scores (`.mscz` or `.mscx`) into Guild Wars 2 instrument tabs.

## Download

Get the Windows installer (`GW2TabConverter-<version>.exe`) from the [latest release](https://github.com/PotatoRhythm/GW2-Tab-Converter/releases/latest) and run it. 

Windows may show "Windows protected your PC" the first time, because the installer isn't code-signed. Click **More info**, then **Run anyway**.

## Features

- Reads MuseScore 3 and 4 files, including ties, tuplets, pickup measures, time signature changes and 8va/8vb lines
- Merges all voices on a staff, and can merge multi-staff instruments such as piano (grand staff) into one part
- Notes that can't be placed exactly while merging can be added at the nearest timing or left out.
- Copies the finished tab to the clipboard as a table that pastes straight into Google Docs
- Adjustable tab style: border layout and colours, beat highlights, text colours and closing text

## Using it

1. Choose a MuseScore file, or drag one onto the window.
2. Pick the sharp key style and how many measures go on each row.
3. Optionally change the look with the paintbrush button beside the **Options** dropdowns.
4. Click **Convert & Copy**, then paste into a Google Doc.

## Notation key

Based on notation by [Merrow](https://docs.google.com/document/d/1Bgoqi7LWJiqkF4ahNDNX4oVmHDqBJv6aaxr8mTcAya4/edit?usp=sharing).

### Note lengths

| Length | Notes | Rest |
| --- | --- | --- |
| Half note / minim | `1~ ~ 1~ ~` | `~ ~` |
| Quarter note / crotchet | `1~ 1~ 1~ 1~` | `~` |
| Eighth note / quaver | `1 1 1 1` | `- -` |
| 16th note / semiquaver | `1111` | `-` |
| Dotted Eighth note / quaver | `1.` | `-.` |
| Dotted Quarter note / crotchet | `1~.` | `~.` |

### Other symbols

| Symbol | Meaning |
| --- | --- |
| **Bold** | The start of a measure (bar) |
| `( )` | Notes one octave higher, e.g. `(123)` |
| `[ ]` | Notes one octave lower, e.g. `[567]` |
| <code>⸨&nbsp;⸩</code>&nbsp;<code>⸨(&nbsp;)⸩</code>&nbsp;<code>⸨⸨&nbsp;⸩⸩</code> | Notes 2, 3 or 4 octaves higher, outside what GW2 instruments can reach |
| <code>⟦&nbsp;⟧</code>&nbsp;<code>⟦[&nbsp;]⟧</code>&nbsp;<code>⟦⟦&nbsp;⟧⟧</code> | Notes 2, 3 or 4 octaves lower, outside what GW2 instruments can reach |
| `/` | Notes played together as a chord, e.g. `1/3/5` |
| `{ }` | Grace notes, played very fast |
| `` ` `` | Between numbers, just a visual spacer for readability, not a rest. `` 11`1111`11 `` is played the same as `11111111` |
| <code>,<ins>136</ins>,</code> | A triplet: a group of three notes played in the time of two |

## Building from source

Needs a JDK, version 17 or newer. Gradle downloads JavaFX and everything else it needs, so there's nothing else to install.

| Command | What it does |
| --- | --- |
| `./gradlew run` | Builds and starts the app |
| `./gradlew build` | Compiles everything and builds the jar |
| `./gradlew packageExe` | Builds a Windows installer in `build/jpackage`, with its own Java runtime so users don't need Java installed. Needs the [WiX Toolset v3](https://wixtoolset.org) |

On Windows, use `gradlew.bat` (or `.\gradlew` in PowerShell) in place of `./gradlew`.

## Licence

[GPL-3.0](LICENSE)
