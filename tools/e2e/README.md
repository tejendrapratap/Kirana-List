# Emulator helper scripts (tools/e2e)

Small bash scripts for a **targeted on-device check** of Grocery Sync in a few minutes: boot or reuse the
emulator, run the mock web app, build an APK that can talk to it, install it, set it up through its
Settings screen, then tap, wait, look and check for crashes. They replace the ad-hoc `h.sh` helpers that
each release tester rewrote. `uitest.sh` runs the instrumented UI test suite (`android/app/src/androidTest`)
on the emulator, which covers the regular release checks; use the other scripts for what it doesn't (the
qa smoke test of the release code, an upgrade, a layout at an odd size, a bug report).

They run in **Git Bash on Windows** (also Linux / macOS) and need `node`, `curl`, the Android SDK and, for
builds, a JDK 17+ (`JAVA_HOME`). Every script prints its usage with `--help`.

## Safety rules (enforced in `lib.sh`)

- Every adb call passes `-s emulator-NNNN`. A serial that isn't `emulator-<digits>` (a phone on USB,
  `192.168.x.x:5555`) is refused before adb runs. Emulators are found by probing
  `adb -s emulator-5554 … emulator-5584 get-state`, never `adb devices`.
- No Gradle task that reaches devices: `./gradlew connectedDebugAndroidTest` (and every `connected*`,
  `install*` or `deviceCheck` task) installs and runs on **every** device adb knows, a phone included.
  `uitest.sh` builds with `assembleDebug assembleDebugAndroidTest`, then installs and runs the tests itself
  on the one emulator; `gs_gradle` in `lib.sh` refuses those tasks.
- `adb kill-server`, `start-server`, `connect`, `disconnect`, `reconnect`, `tcpip`, `usb`, `root`,
  `unroot`, `remount` and `pair` are refused: other testers may share the adb server and the emulator.
  To simulate "offline", use `ui.sh network off` (airplane mode) or `mock.sh stop`. Don't use
  `adb reverse --remove`: it doesn't break OkHttp's open keep-alive connection.
- Each script stops only what it started: `emu.sh stop` only stops an emulator it booted (pid file plus
  boot id), and `mock.sh stop` only stops a mock it started on that port. A port or emulator someone else
  uses is left alone.
- Tokens are never printed. The mock's test token is kept in the state folder, and `app.sh configure`
  types it into the app.
- All scripts are safe to re-run. `emu.sh` reuses a running emulator, `mock.sh start` keeps a mock that
  already runs with the same settings, and `ui.sh reset` puts back every display setting the tools changed.

## The scripts

| Script | What it does |
|---|---|
| `emu.sh` | `emu.sh` reuses a running emulator, or boots the `Pixel_9a` AVD headless (`-read-only -no-snapshot-save`, so the AVD itself never changes) on a free port. It waits for the boot, unlocks the screen and prints the serial: `S=$(./emu.sh)`. `emu.sh stop` stops only the emulator it booted. `emu.sh status` and `emu.sh serial` show what's running. |
| `mock.sh` | `mock.sh start [--script current\|v3\|v2\|<path>] [--fail-mode …]` runs `apps-script/test/mock-server.js` (default port 8787) with a fresh fake sheet and waits until it answers. `stop`, `status` and `url` manage the server. The fake sheet: `sheets [Items\|History\|_Tombstones\|--names\|--json]` dumps it, and `hand-edit Rice Need 3` edits it like a person. The other phone: `upsert [--device Home] Rice 2 kg`, `delete Rice`, `apply '<changes json>'`, `list` and `ping` go through the real 302 redirect, as the app does. The debug routes: `fail-mode`, `script`, `seed-v2`, `validation`. |
| `build-test-apk.sh` | `build-test-apk.sh` (or `qa`) builds `assembleQa` in the project itself: the release build (same R8 code and rules) with its own app id (`com.grocerysync.app.qa`), the name **Grocery QA**, the debug key and cleartext HTTP to the mock, so it installs next to the real app and can never replace it. `debug` builds `assembleDebug`, also in place. `release` (an option for when the real app id or the release signing is what you check) builds `assembleRelease` in a scratch copy of `android/` (`--work`, `--clean`) with the debug network security config added, so it can reach the mock over `http://10.0.2.2`; the project is never edited, and **that APK is for emulators only**. The APK goes to `out/apk/app-<variant>-e2e.apk` (path on stdout), and the script prints aapt2 badging (package, versionCode/Name, SDK levels, debuggable), whether cleartext to the mock is allowed, and the signer's SHA-256. |
| `app.sh` | `install qa\|debug\|release\|<apk> [--fresh] [--low-storage]` installs the app and explains signature, downgrade and full-`/data` failures. It remembers the APK's app id, so the other commands and `ui.sh crashes` act on the app installed last (override with `GS_PACKAGE=…`). Also `uninstall`, `launch`, `stop`, `restart`, `clear-data` and `info`. `configure [--name Shop] [--port P] [--no-test]` sets the app up through the Settings UI (needed for release builds, where `run-as` can't write settings): Your name, the mock's emulator URL and token, **Test connection**, **Save**, then waits for the `Live` status. `pull-db` copies the database (debug builds only). |
| `ui.sh` | Finding: `dump`, `texts`, `find`, `wait-text [-t secs] [--gone]`, `tap-text`, `tap-desc` and `scroll-to`. They match the text (or with `-d` the content description, `-a` either), exactly or with `-r regex`, `-p substring` or `-i N` (the Nth match). Acting: `tap X Y`, `type TEXT` (ASCII only), `set-field "Web app URL" VALUE` (tap, clear, type, read back), `key BACK ENTER`, `hide-keyboard` and `swipe left\|right\|up\|down`. Seeing: `shot NAME`. The device: `font-scale 1.3`, `screen-size 1080x1920 480`, `rotate landscape`, `dark on`, `animations off`, `network off`, and `reset` to undo them all. Logs: `logcat start\|stop\|mark\|grep`, and `crashes`, which exits 1 on a FATAL EXCEPTION or ANR of the app since the last install or mark. |
| `uitest.sh` | Runs the UI test suite on the emulator: builds `assembleDebug assembleDebugAndroidTest`, installs the app and the test APK with `adb -s emulator-NNNN install -r -t`, runs `am instrument -w -r --no-window-animation` (package and runner read from the test APK) and prints one line per test, a pass/fail summary and the wall time of the build, install and run. `--class SearchTest` (or `Class#method`, repeatable) runs fewer, `--no-build` reuses the last APKs, `--fresh` uninstalls first, `--uninstall` afterwards, and `uitest.sh uninstall` only removes both apps. It puts back the animation scales `am instrument` leaves changed. Exits 1 on any failure; the raw output and, after a failure, the device log of the run are in `out/logs/`. |
| `lib.sh` | Shared code (serial guard, adb wrapper, state files, UI helpers). It is sourced by the scripts above. |
| `uixml.js` | Parses a `uiautomator dump`: `list`, `find`, `count`, `field` (a Compose text field by its label) and `texts`. It also works on saved dumps: `node uixml.js find out/xml/last.xml any-re '^Live'`. |
| `mockjson.js` | JSON glue for `mock.sh` (apply bodies, sheet rows, cell lookup). |
| `selftest.sh` | Checks the scripts without an emulator: the parser runs against the saved dumps in `testdata/`, and `emu.sh`, `ui.sh` and `app.sh` run against `testdata/fake-adb.sh` and `fake-emulator.sh`, which fail any call that lacks `-s emulator-NNNN`. This part takes about 5 minutes on Windows. `--mock` also drives the real mock server on a spare port (`--no-fake --mock` runs only that, about 2 minutes), and `--build` also runs a real debug build. Run it after changing a script. |

Settings (environment, all optional): `EMU_SERIAL` (which emulator; also `-s` on `app.sh` / `ui.sh`),
`EMU_AVD` (default `Pixel_9a`), `EMU_ARGS`, `EMU_BOOT_TIMEOUT`, `MOCK_PORT`, `MOCK_TOKEN`, `ANDROID_SDK`,
`JAVA_HOME`, `GS_REPO` (the checkout; default `../..` from the scripts) and `GS_E2E_HOME` (state and
output; default `$TMPDIR/grocery-sync-e2e`). Output goes to `out/`: `xml/` (dumps; `last.xml` is the
newest), `shots/`, `logs/` (emulator, mock, Gradle, logcat) and `apk/`.

## The UI tests

```bash
cd tools/e2e                                     # in Git Bash
./emu.sh                                         # reuse or boot Pixel_9a headless; prints emulator-5554
./uitest.sh                                      # build, install on that emulator, run all; summary + wall time
./uitest.sh --no-build --class SearchTest        # one class again, without rebuilding
./uitest.sh uninstall                            # remove the app and the test app from the emulator
```

The tests bring their own fake web app (inside the test APK), so they need neither the mock nor a network.

## A targeted check in 10 lines

```bash
cd tools/e2e                                     # in Git Bash
./emu.sh                                         # reuse or boot Pixel_9a headless; prints emulator-5554
./mock.sh start                                  # mock web app on :8787, fresh sheet, test token
./build-test-apk.sh qa                           # assembleQa: R8 release code as com.grocerysync.app.qa -> out/apk/app-qa-e2e.apk
./app.sh install qa && ./app.sh configure --name Shop   # Settings UI: name, URL, token; waits for "Live"
./mock.sh upsert Rice 2 kg                       # the other phone ("Home") adds an item
./ui.sh wait-text Rice && ./ui.sh shot rice      # it appears within one poll (~5 s); screenshot in out/shots/
./ui.sh tap-desc "Got it: Rice" && ./mock.sh sheets | grep Rice   # tick it off; the sheet's Need goes to 0
./ui.sh crashes                                  # exit 1 on a FATAL EXCEPTION / ANR of the app
./app.sh uninstall; ./ui.sh reset; ./mock.sh stop; ./emu.sh stop   # stop only what these scripts started
```

## Good to know

- **Speed.** One `uiautomator dump` takes 1-2 s, so `wait-text` and `tap-text` react within about 2 s.
  A cold emulator boot takes 1-3 minutes, a reused one is instant. The first qa build takes 1-2 minutes,
  and later ones are incremental.
- **Labels to use.** Top bar: `tap-desc "Search items"`, `"Share list"`, `"More options"` (the ⋮ menu:
  then `tap-text Settings`, `"Add common items"`, `"Paste a list"` or `"Group by shop section"`), `"Add item"`,
  `"Close search"`, `"Back"`. Rows: `"Got it: <name>"` (To buy), `"Need <name>"` (a Yes/no switch),
  `"1 more <name>"` / `"0.5 kg less <name>"` (steppers), `"Quantity 2 kg"`. Item dialog:
  `set-field Item Tea`, `tap-desc Unit`, `tap-text Save`. Tabs: `tap-text -r '^To buy'`,
  `tap-text -r '^All items'` or `swipe left`. When in doubt, run `ui.sh dump`.
- **Typing** goes through `adb shell input text`, which can only type printable ASCII. For other
  characters, create the item with `mock.sh upsert 'Pañeer' 1` and check it in the app.
- **Debug vs release.** They are signed with different keys, so switching needs `app.sh install … --fresh`
  (which loses the app's data). The qa build has its own app id and installs next to either. A debug
  build can also be set up quickly with `run-as`, but `configure` works for all of them.
- **A full emulator.** `INSUFFICIENT_STORAGE` on install means the AVD's `/data` is nearly full.
  `app.sh install … --low-storage` lowers the free-space check for that one install and puts it back.
- **Changing the display.** `screen-size`, `font-scale`, `rotate`, `dark`, `animations` and `network`
  each remember the original value the first time, and `ui.sh reset` restores them all. Run it before
  you hand the emulator back.
- **Upgrade checks.** Install the old APK, `configure`, add items, then `app.sh install <new apk>`
  without `--fresh`, so the data is kept. Run the mock with `--script v3` (the 1.1 script) and switch it
  live with `mock.sh script current`.
