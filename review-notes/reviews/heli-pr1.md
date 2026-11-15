APPROVE — HeliBoard PR #1

Reviewed final head `8c715cff1778b106dde59efae2cd93c1f184ea7a` against pinned `design` base `4fa14d14c08508ff9c9013a27f829e4caa426735` for items 21/22b/23 and QUALITY.md. No actionable findings remain. The opacity and contrast findings from earlier rounds are resolved.

**Drawing and contrast verified.**

[DictationIndicatorView.kt:58](/home/wolf/herdr-android/src/HeliBoard-review/app/src/main/java/helium314/keyboard/latin/dictation/DictationIndicatorView.kt:58) keeps the keyboard's KeyShapeDrawable and Rounded radius, with square corners for Material/Holo. The background now uses an opaque functional-key colour with borders, or keyboard background without them. The native rendering probe passes all **12** style × light/dark × border combinations: every body has alpha 255, Rounded corners have alpha 0, and Material/Holo corners have alpha 255. Actual SuggestionStripView renders show no toolbar/status overlap: [light](heli-pr1-8c715cff-probes/strip-renders/head-light-borders-false.png), [dark](heli-pr1-8c715cff-probes/strip-renders/head-dark-borders-false.png). [Alpha matrix](heli-pr1-8c715cff-probes/backgrounds/matrix.txt).

[DictationIndicatorView.kt:199](/home/wolf/herdr-android/src/HeliBoard-review/app/src/main/java/helium314/keyboard/latin/dictation/DictationIndicatorView.kt:199) compares the two final opaque composites using the same opaque text the view draws. Both independent contrast regressions now pass: gray `#b0b0b0` selects black at **9.68:1**, and theme text `0x2000c000` selects black for the actual opaque green paint at **8.54:1**. [View/paint probe](HeliIndicatorContrastProbeTest.kt), [output](heli-pr1-8c715cff-probes/contrast.txt). All four committed DictationIndicatorFillTest cases pass, including transparent/custom colours.

**Other requested checks pass.**

- Mic suppression/restoration: [SplitHubView.kt:88](/home/wolf/herdr-android/src/HeliBoard-review/app/src/main/java/helium314/keyboard/latin/suggestions/SplitHubView.kt:88) initializes from the field flag; [SuggestionStripView.kt:623](/home/wolf/herdr-android/src/HeliBoard-review/app/src/main/java/helium314/keyboard/latin/suggestions/SuggestionStripView.kt:623) updates hub, toolbar and pinned mic together. Repeated normal/no-mic field transitions pass. Layout keys already receive that flag at KeyboardSwitcher.java:164; this branch has no TerminalKeysView MIC. Voice tap/hold still dispatch VOICE_INPUT/DICTATION_ENGINE.
- Preferences: [ToolbarUtils.kt:161](/home/wolf/herdr-android/src/HeliBoard-review/app/src/main/java/helium314/keyboard/latin/utils/ToolbarUtils.kt:161) preserves existing enabled/disabled entries and ordering, appends missing keys and respects an explicit HIDE_KEYBOARD opt-out. Probes pass for complete reversed and sparse sets, all three preferences, repeated migration, and empty/malformed legacy strings. CLOSE_HISTORY survives. The two committed ToolbarHideKeyTest cases pass.
- Hide action: ToolbarUtils.kt:98 maps HIDE_KEYBOARD to IME_HIDE_UI; [InputLogic.java:881](/home/wolf/herdr-android/src/HeliBoard-review/app/src/main/java/helium314/keyboard/latin/inputlogic/InputLogic.java:881) calls `requestHideSelf(0)`. All icon styles and the accessible label are wired. As documented in the PR, the pinned strip control is unavailable when the split hub replaces the strip.
- Dictation: recognition/controller/formatting code and gesture callbacks are unchanged. All **15 DictationTextTest cases pass on head and base**. No live speech session was exercised.
- Rollout: independently read phone-config's [default.nix](https://github.com/404Wolf/phone-config/blob/main/apps/heliboard/default.nix) and [release-overrides.gradle](https://github.com/404Wolf/phone-config/blob/main/apps/heliboard/release-overrides.gradle). Each pin bump changes versionCode and triggers the existing toolbar migration; no fork versionCode change is needed.

**Gates.**

Ran `nix shell nixpkgs#jdk21 -c ./gradlew assembleDebug testDebugUnitTest` with ANDROID_HOME and ANDROID_SDK_ROOT both set to `/nix/store/d9989bz70bhb9w80nfp26n9y5x64x6j5-androidsdk/libexec/android-sdk`.

| Revision | assembleDebug | Unit tests |
| --- | --- | --- |
| Final head `8c715cff` | PASS | 199 run, 5 known failures |
| Pinned base `4fa14d14` | PASS | 193 run, the same 5 failures |

All five final-head failures match the saved base assertions: XLinkTest.layoutsLinks/readmeLinks/knownDictionaries return HTTP 404, insertLetterIntoWordHangulFails returns the same empty result, and detectEmojisAtEndFails returns the same empty result. No new unit-test failure. All six added tests pass. [Final gate log](heli-pr1-8c715cff-gate.log), [base log](heli-pr1-base-gate.log), [comparison](heli-pr1-pr2-final-gates.txt).

All **6 independent review probes pass**: opacity/corners, real strip rendering, voice restoration, migration preservation and both contrast cases. [Probe log](heli-pr1-8c715cff-probes.log), [results](heli-pr1-8c715cff-probes/test-results/TEST-helium314.keyboard.latin.HeliPr1ReviewProbeTest.xml).

QUALITY.md: the PR explains its design and embeds before/after renders. The [assets README](https://github.com/404Wolf/HeliBoard/blob/wolf/pr-assets/README.md) names the PM as owner and deletion after both merges as removal; assets/tooling remain outside the production diff. Source tree is clean and temporary test copies were removed. No phone/adb, push, GitHub comment or merge. Device/fold QA and actual recognition remain outside this review. [Initial review archive](heli-pr1-9e6dbb1c-review.md).
