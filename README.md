# Tavisdor: The Shifting Passage

Turn-based dungeon crawler for Android. Portrait orientation. Built in Android Studio with Kotlin + custom `View` Canvas rendering. No third-party game engine.

## Concept

- Control a party of 4 heroes (Mage / Thief / Fighter / Archer). The party moves as a single grid token ("chess piece") through a procedurally-assembled dungeon.
- Each floor is stitched together at runtime from pre-made room and hallway templates. Floors are discarded once descended — memory stays small.
- Entering a populated room triggers turn-based combat. Heroes never leave the party token; turn order is decided by dexterity across all 4 heroes and all monsters in the encounter.
- Goal per floor: find the staircase tile and descend. Repeat forever.

## Tech

- Kotlin
- Android Gradle Plugin 8.13.2 / Kotlin 1.9.20 / Gradle 8.13
- minSdk 24, targetSdk 35, compileSdk 35
- Single `MainActivity` hosts three overlays: title screen, class-select screen, in-game `GameView`.
- Canvas-based rendering driven by `Choreographer.FrameCallback` (even turn-based games need a render loop for tween animations).
- Save state via `SharedPreferences`; auto-save at the start of each floor + manual "Save & Quit".

## Project layout

```
Tavisdor/
├── app/
│   ├── build.gradle              # app module: applicationId com.tavisdor.app, deps
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml   # portrait, single MainActivity
│       ├── assets/
│       │   ├── dungeon/
│       │   │   ├── rooms/        # room template JSONs (TODO)
│       │   │   └── hallways/     # hallway template JSONs (TODO)
│       │   ├── sprites/          # PNG/WebP art (drop in to replace placeholders)
│       │   └── sfx/              # MP3/WAV audio
│       ├── java/com/tavisdor/app/
│       │   ├── MainActivity.kt
│       │   ├── GameView.kt              # Canvas + Choreographer loop for dungeon
│       │   ├── HeroPanelView.kt         # bottom 2x2 hero panel
│       │   ├── game/                    # top-level Game state + Scene abstraction
│       │   ├── ui/                      # TitleScreen / ClassSelectScreen controllers
│       │   ├── dungeon/                 # Cell, Floor, templates, generator, pathfinder
│       │   ├── party/                   # Hero, HeroClass, Party
│       │   ├── combat/                  # Combat, Monster, Initiative
│       │   ├── render/                  # DungeonRenderer, HeroPanelRenderer, Camera
│       │   ├── input/                   # InputHandler (tap routing)
│       │   └── save/                    # SaveData, SaveStore
│       └── res/
│           ├── drawable/ic_launcher_foreground.xml   # placeholder app icon
│           ├── layout/activity_main.xml
│           └── values/{themes,colors,strings,dimens}.xml
├── build.gradle                  # root: pins AGP + Kotlin
├── settings.gradle               # rootProject.name = "Tavisdor", repos
├── gradle.properties
└── gradle/wrapper/               # Gradle 8.13 wrapper
```

## Running

1. Open `Tavisdor/` in Android Studio.
2. Let Gradle sync (first time downloads AGP + dependencies).
3. Run on emulator or device — locked to portrait, opens to the title screen.

## Replacing placeholders

All initial art is drawn programmatically as placeholder shapes. Drop matching files into `app/src/main/assets/sprites/` to override:

| Placeholder                  | Drop-in filename                                |
| ---------------------------- | ----------------------------------------------- |
| Party token on dungeon grid  | `party_token.png`                               |
| Hero portraits (panel)       | `mage.png`, `thief.png`, `fighter.png`, `archer.png` |
| Title background             | `title_bg.png`                                  |

The asset loader checks for these files at runtime; placeholders disappear automatically when the file is present.
