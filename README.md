# PR assets (not code)

Screenshots for the PRs that change the keyboard's look, rendered with Robolectric
(native graphics, 412 dp wide, xhdpi) by `tools/DictationIndicatorShots.kt`: copy it
into `app/src/test/java/helium314/keyboard/latin/` of the commit to render and run

    HELIBOARD_SHOTS=/some/dir ./gradlew :app:testDebugUnitTest --tests '*DictationIndicatorShots'

with JDK 21. Each image is the dictation box over a strip with words and toolbar-like
icons under it, on a grey stand-in for what lies behind a transparent keyboard.

- `dictation-box/before`: `design` at 4fa14d14 (flat, square, strip colour)
- `dictation-box/after`: `wolf/hub-no-mic` at 16f8cc58 (PR #1)
- `dictation-box/pairs`: before over after, per style

Owner: the herdr-android PM. Removal: delete this branch once PRs #1 and #2 are merged.
