# GW2 Tab Converter

Converts MuseScore scores (`.mscz` or `.mscx`) into Guild Wars 2 instrument tabs

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
3. Optionally change the look under **Edit Style**, or load a saved style from **Presets**.
4. Click **Convert & Copy**, then paste into a Google Doc.

## Building from source

Needs a JDK, version 17 or newer. Gradle downloads JavaFX and everything else it needs, so there's nothing else to install.

| Command | What it does |
| --- | --- |
| `./gradlew run` | Builds and starts the app |
| `./gradlew build` | Compiles everything and builds the jar |
| `./gradlew packageExe` | Builds a Windows installer in `build/jpackage`, with its own Java runtime so users don't need Java installed. Needs the [WiX Toolset v3](https://wixtoolset.org) |

On Windows, use `gradlew.bat` (or `.\gradlew` in PowerShell) in place of `./gradlew`.
