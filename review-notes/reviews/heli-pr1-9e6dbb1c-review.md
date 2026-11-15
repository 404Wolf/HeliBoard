CHANGES — HeliBoard PR #1

Reviewed `9e6dbb1c1a9612b6854b5e5e4c6c8d39cb97af44` against pinned base `4fa14d14c08508ff9c9013a27f829e4caa426735` for items 21/22b/23 and `~/herdr-android/QUALITY.md`.

**P2 — Keep the dictation overlay opaque with key borders disabled.**

[DictationIndicatorView.kt:53](/home/wolf/herdr-android/src/HeliBoard-review/app/src/main/java/helium314/keyboard/latin/dictation/DictationIndicatorView.kt:53) tints the background with the functional key's state list. With key borders off, that list's idle colour is transparent: [Colors.kt:472](/home/wolf/herdr-android/src/HeliBoard-review/app/src/main/java/helium314/keyboard/latin/common/Colors.kt:472), with the same policy in DynamicColors. [SuggestionStripView.kt:378](/home/wolf/herdr-android/src/HeliBoard-review/app/src/main/java/helium314/keyboard/latin/suggestions/SuggestionStripView.kt:378) overlays the indicator without hiding the existing strip contents. Starting dictation over an expanded toolbar therefore paints the toolbar icons through “Listening”, the engine label and the meter. Suggestions can overlap for the same reason. The base's opaque fill covered those contents.

Native Robolectric rendering reproduces this in Rounded, Material and Holo, in both light and dark themes: all six borderless cases have background body alpha **0**. With borders enabled, body alpha is **255**, Rounded has transparent rounded corners, and Material/Holo have square corners. Real SuggestionStripView renders confirm the overlap:

| Theme, borders off | Base | PR head |
| --- | --- | --- |
| Light | [before](heli-pr1-strip-renders/base-light-borders-false.png) | [after: icons overlap status](heli-pr1-strip-renders/head-light-borders-false.png) |
| Dark | [before](heli-pr1-strip-renders/base-dark-borders-false.png) | [after: icons overlap status](heli-pr1-strip-renders/head-dark-borders-false.png) |

Preserve an opaque, themed overlay body independently of the borderless keys' transparent idle state, or explicitly hide/restore the covered contents. Keep the shared key shape/radius. Add a regression test for the borderless overlay; the existing PR tests only cover toolbar defaults/migration.

**Other requested checks passed.**

- Voice visibility: [SplitHubView.kt:88](/home/wolf/herdr-android/src/HeliBoard-review/app/src/main/java/helium314/keyboard/latin/suggestions/SplitHubView.kt:88) initializes from the field flag; [SuggestionStripView.kt:623](/home/wolf/herdr-android/src/HeliBoard-review/app/src/main/java/helium314/keyboard/latin/suggestions/SuggestionStripView.kt:623) updates toolbar, pinned mic and hub together. A local probe alternated normal and no-mic editors repeatedly and verified all three surfaces hide and restore. Keyboard layouts already receive the same flag at KeyboardSwitcher.java:164. This branch's TerminalKeysView has no mic. Voice tap/long-press retain VOICE_INPUT/DICTATION_ENGINE.
- Preferences: [ToolbarUtils.kt:161](/home/wolf/herdr-android/src/HeliBoard-review/app/src/main/java/helium314/keyboard/latin/utils/ToolbarUtils.kt:161) preserves existing entries and order and appends missing keys. Probes passed for reversed complete sets, sparse legacy sets, enabled/disabled entries, all three toolbar prefs, repeated upgrades, explicit HIDE_KEYBOARD unpinning, empty strings and malformed/obsolete string entries. The enum is appended; CLOSE_HISTORY survives. No new migration crash or loss of existing keys found.
- Hide action: ToolbarUtils.kt:98 maps HIDE_KEYBOARD to IME_HIDE_UI; the existing [InputLogic.java:881](/home/wolf/herdr-android/src/HeliBoard-review/app/src/main/java/helium314/keyboard/latin/inputlogic/InputLogic.java:881) path calls `requestHideSelf(0)`. Icons exist for all three icon styles and the accessibility label resolves to “Hide keyboard”. As documented in the PR, the pinned strip control is unavailable when the split hub replaces that strip.
- Dictation: recognizer/controller, text formatting, tap-to-stop and long-press callbacks are unchanged. All **15 DictationTextTest tests pass on both revisions**. No speech recognition session was exercised.
- Rollout migration: resolved. Although the fork retains versionCode 4101, phone-config passes the pin's commit timestamp as `nixVersionCode` in [default.nix](https://github.com/404Wolf/phone-config/blob/main/apps/heliboard/default.nix), and [release-overrides.gradle](https://github.com/404Wolf/phone-config/blob/main/apps/heliboard/release-overrides.gradle) applies it. A pin update triggers AppUpgrade's toolbar migration. Both files were read independently.

**Gates and evidence.**

Ran `nix shell nixpkgs#jdk21 -c ./gradlew assembleDebug testDebugUnitTest` on both revisions with ANDROID_HOME and ANDROID_SDK_ROOT set to `/nix/store/d9989bz70bhb9w80nfp26n9y5x64x6j5-androidsdk/libexec/android-sdk`.

| Revision | assembleDebug | Unit tests |
| --- | --- | --- |
| PR head | PASS | 195 run, 4 known failures |
| Pinned base | PASS | 193 run, 5 known failures |

All four head failures have identical assertions on the base: XLinkTest.layoutsLinks/readmeLinks return HTTP 404, insertLetterIntoWordHangulFails produces the same empty result, and detectEmojisAtEndFails produces the same empty result. The base additionally failed XLinkTest.knownDictionaries with HTTP 404. That test randomly selects a dictionary category (XLinkTest.kt:33), so the counts are not identical; it passed in the head run. The two added ToolbarHideKeyTest cases pass. See [comparison](heli-pr1-gate-comparison.txt), [head log](heli-pr1-head-gate.log), and [base log](heli-pr1-base-gate.log).

Final temporary review probes: migration and voice checks pass; the opacity check fails on precisely the six borderless cases described above. [Probe source](HeliPr1ReviewProbeTest.kt), [results](heli-pr1-probe-test-results/TEST-helium314.keyboard.latin.HeliPr1ReviewProbeTest.xml), [log](heli-pr1-probes2.log). Real-strip render tests pass on head and base; [render source](HeliPr1IndicatorRenderTest.kt).

QUALITY.md: the implementation reuses existing flag, action and drawable paths. The PR body describes its design, but contains no before/after screenshots and defers visual QA; the local renders above supply review evidence and should accompany the corrected change. No phone/adb, push, GitHub comment or merge was performed. Temporary probe files were removed from both source trees; evidence remains in this review directory.
