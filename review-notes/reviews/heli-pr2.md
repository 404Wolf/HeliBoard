APPROVE — HeliBoard PR #2

Reviewed final head `4ccb70bfa9e46d293fb58d508ede432d93b68738` against `dictation-desktop` base `9e99d1d2897a3043a8b7204d8cb0fde2e6232dec`, including the shared PR #1 changes and QUALITY.md. No actionable findings remain. Shared opacity and contrast findings are resolved.

**Branch history and scope preserved.**

`git merge-base --is-ancestor 9e99d1d2 4ccb70bf` passes. Existing dictation-desktop commits remain intact. [Final range-diff](heli-pr2-final-range-diff.txt) maps all six PR #1 patches onto this branch; the only extra patch is `ef7ff5dc`, the terminal MIC fix. The shared opaque-fill patch differs only in context for the existing DESKTOP_OFFLINE state. Controller/recognizer, desktop transport, courier and formatter code are unchanged. The existing offline state, indication and label are retained.

**Terminal MIC behavior verified.**

- [InputAttributes.java:62](/home/wolf/herdr-android/src/HeliBoard-review-pr2/app/src/main/java/helium314/keyboard/latin/InputAttributes.java:62) records this IME's explicit noMicrophoneKey request before the non-text early return. TYPE_NULL can therefore honor the request without losing the terminal mic when the normal toolbar's voice flag is false. No Herdr package special case was introduced.
- [TerminalKeysView.kt:85](/home/wolf/herdr-android/src/HeliBoard-review-pr2/app/src/main/java/helium314/keyboard/latin/suggestions/TerminalKeysView.kt:85) applies it at creation. [SuggestionStripView.kt:286](/home/wolf/herdr-android/src/HeliBoard-review-pr2/app/src/main/java/helium314/keyboard/latin/suggestions/SuggestionStripView.kt:286) refreshes both strip row and hub terminal block with terminal mode. Normal hub/toolbar/pinned keys keep their existing field flag.
- Independent probes pass for TYPE_NULL, multiline/no-suggestions terminal text and Termux's visible-password/no-suggestions input. Both layouts hide MIC for this IME's request and retain it for no request or another keyboard's option.
- Repeated no-mic Herdr to no-request Termux transitions update both existing layouts through the suggestion-update path. At the test width, hiding MIC expands the hub's Shift+Enter from 300px to 600px; restoring MIC returns it to 300px. Hidden MIC leaves no occupied slot.
- MIC tap/hold dispatch and MicKeyState registration are unchanged. The terminal-specific fix does not alter dictation callbacks.

[Terminal probe source](HeliPr2TerminalMicProbeTest.kt), [passing results](heli-pr2-4ccb70bf-probes/test-results/TEST-helium314.keyboard.latin.HeliPr2TerminalMicProbeTest.xml). Both committed InputAttributesTest cases pass too.

**Shared fixes verified on this branch.**

The 12-case native opacity/corner matrix passes. Both custom-theme contrast regressions pass, including reading the view's actual paint: `#b0b0b0` gives 9.68:1 on black; `0x2000c000`, drawn opaque, gives 8.54:1. Migration probes preserve ordering/values, explicit unpinning and idempotence across old string prefs. Normal-app voice restoration and the existing IME hide mapping pass. See [PR #1 review](heli-pr1.md) for the shared source paths and rollout versionCode verification.

This branch intentionally leaves the strip usable while dictating and places the indicator only over an active split hub. That behavior predates this PR and is preserved. The ordinary strip renders therefore show the toolbar, not an indicator overlay. Actual hub renders compare [base light](heli-pr2-final-hub-renders/base-light-borders-false.png) with [head light](heli-pr2-final-hub-renders/head-light-borders-false.png), and [base dark](heli-pr2-final-hub-renders/base-dark-borders-false.png) with [head dark](heli-pr2-final-hub-renders/head-dark-borders-false.png). [Hub render probe](HeliPr2HubRenderProbeTest.kt).

**Gates.**

Ran `nix shell nixpkgs#jdk21 -c ./gradlew assembleDebug testDebugUnitTest` with both SDK variables set to the supplied Android SDK.

| Revision | assembleDebug | Unit tests |
| --- | --- | --- |
| Final head `4ccb70bf` | PASS | 201 run, 5 known failures |
| Base `9e99d1d2` | PASS | 193 run, the same 5 failing tests |

Failures are XLinkTest.layoutsLinks/readmeLinks (HTTP 404), randomized knownDictionaries (HTTP 404 head / HTTP 429 base), insertLetterIntoWordHangulFails and detectEmojisAtEndFails. The Hangul/emoji assertions match exactly. All eight added tests pass: InputAttributesTest ×2, ToolbarHideKeyTest ×2, DictationIndicatorFillTest ×4. All **15 DictationTextTest cases pass on head and base**. [Head log](heli-pr2-4ccb70bf-gate.log), [base log](heli-pr2-base-9e99d1d2-gate.log), [comparison](heli-pr1-pr2-final-gates.txt).

All **8 independent review probes pass**, covering shared rendering/opacity/contrast/voice/migration checks plus terminal creation/transitions/layout. [Probe log](heli-pr2-4ccb70bf-probes.log). The additional actual-hub render check passes on head and base: [head log](heli-pr2-final-hub-renders.log), [base log](heli-pr2-base-hub-renders.log).

QUALITY.md: the shared design reuses existing IME-option, action and drawable paths. PR #2 links the before/after renders in #1. The [assets README](https://github.com/404Wolf/HeliBoard/blob/wolf/pr-assets/README.md) names PM ownership and deletion after both merges. Source trees are clean; temporary test copies were removed. No phone/adb, push, GitHub comment or merge. Device/fold QA and an actual speech-recognition session were not exercised.
