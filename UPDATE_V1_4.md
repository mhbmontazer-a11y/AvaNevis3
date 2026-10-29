# AvaNevis 1.4.0 — Visual refresh

## Included
- New colorful Material 3 palettes: Ocean, Violet, Mint, Sunset; persisted in DataStore.
- Modern study hero, library and session metrics, refined rounded cards.
- Scrollable and reorganized settings with dark theme, keep-screen-on, typography, recognition preferences.
- Horizontal scrolling quick export actions (Copy, TXT, PDF, Share).
- Existing Room database, session management, speech service, Persian text processing, and export code preserved.
- Version code incremented to 4, package id unchanged.
- Android SDK setup workflow now explicitly installs current Android 34 packages.

## Important release notes
- No Android SDK/Gradle build executed in this environment; compile and test in GitHub Actions.
- The GitHub-hosted debug signing key is typically recreated on each runner. A newly generated debug APK may not install over the existing app because of signature mismatch. Export all important notes before attempting an uninstall. Do not uninstall without backing up.
- Existing Android SpeechRecognizer is session-based and cannot guarantee gapless or 100% accurate recognition.
