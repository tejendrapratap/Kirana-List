# Docs

Planning notes for Kirana List (formerly Grocery Sync), kept with the code.

| Doc | What it covers |
|---|---|
| [Feature ideas](feature-ideas.md) | 14 ranked feature ideas with evidence, effort and status, what the app already does, and what was left out and why |
| [Common Indian grocery list](indian-grocery-list.md) | 90 items graded core / common / regional from national survey data, with Hindi names and usual units; the source for the starter list and name matching |
| [Removing the setup](zero-setup.md) | How to drop the Google Sheet + Apps Script setup (recommended: Firebase with an invite code), the migration plan, and how to get the app onto phones (sideloading, Play internal testing, public release), with a fact check of every price and policy |

## Decisions so far (2026-11-08)

1. Ship the five quick wins first, as 1.4. They change only the app, so the sheet's script stays as it is.
2. Then move off Google Sheets to Firebase (Cloud Firestore in Mumbai, anonymous sign-in, invite code) as its own release. The Cloudflare Worker + D1 option is the fallback.
3. Keep sideloading the signed APK for the family for now; move to a Play Console internal testing track when it saves effort.

## Look and name

The app is **Kirana List**, formerly Grocery Sync (a *kirana* is the neighbourhood grocery shop). Only the visible name changed: the app id `com.grocerysync.app`, the Kotlin packages, the signing key and `Code.gs` are the same, so it installs over Grocery Sync as an update. The name is `app_name` in `android/app/src/main/res/values/strings.xml`, which the list's app bar shows too; the qa build is called **Kirana QA**.

<img src="images/app-icon.svg" alt="The app icon: a cream cloth bag with a turmeric check mark on green" width="108">

The icon is a cream *jhola* (cloth shopping bag) with a turmeric check mark on the brand green: an adaptive icon in vector drawables, with a monochrome layer for Android 13+ themed icons. The Android 12+ splash screen shows the same bag on the same green.

![The palette: main colour roles in the light and dark themes, with their hex values](images/palette.svg)

The "fresh market" palette is in `android/app/src/main/java/com/grocerysync/app/ui/theme/Theme.kt`: three seed colours turned into Material HCT tonal palettes, mapped to complete Material 3 light and dark schemes.

| Colour | Seed | Light theme | Dark theme | Where it shows |
|---|---|---|---|---|
| Curry-leaf green (primary) | `#3E7D2A` | `#376A26`, container `#B7F39D` | `#9BD684`, container `#1E510F` | App bars, ticks, switches, the selected To buy / All items segment, links, "Live" |
| Turmeric (secondary) | `#F4B400` | `#7B5800`, container `#FFCD61` | `#FEBB04`, container `#5D4200` | Quantity chips, the dialog's − / + buttons, the cart in the app bar, the setup banner |
| Marigold (tertiary) | `#F28C00` | `#8E4F00`, container `#FFA442` | `#FFB772`, container `#FFA442` | The + button, notices (the script is out of date, a web app URL warning) |
| Surfaces | | Warm cream `#FFF8EE`; dialogs `#F3E7D5` | Green charcoal `#13190F`; dialogs `#293025` | Screens, menus, dialogs |
| Error | | `#BA1A1A` | `#FFB4A9` | Errors stay red |

- The app bars are deep green in both themes (light `#376A26` with white text, dark `#1E510F` with `#B7F39D`), so the status bar icons over them are always light; the navigation bar follows the theme.
- Dynamic (wallpaper) colour is off, so the brand palette always shows. Dark mode follows the phone's setting.
- `ThemeContrastTest` (a JVM unit test) checks WCAG contrast in both themes: 4.5:1 for text and icons on their colour (every `on*` role, `onSurfaceVariant` on every surface container), 3:1 for outlines.
