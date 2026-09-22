# Contributing to Castla

## Before opening a change

- Use GitHub Issues for reproducible bugs and feature proposals.
- Include the Android version, device model, Shizuku version, Tesla browser behavior, reproduction steps, and sanitized logs when relevant.
- Keep unrelated changes in separate pull requests.

## Development setup

Requirements:

- Android Studio with the Android SDK used by the project
- JDK 17
- Node.js and pnpm for the Svelte frontend
- An Android device running Shizuku for end-to-end testing

```bash
git clone https://github.com/fbezita/castla.git
cd castla
pnpm --dir frontend install --frozen-lockfile
./gradlew assembleDebug
```

Gradle builds the frontend and copies `frontend/dist` into the APK. When frontend inputs have not changed, those tasks are skipped as `UP-TO-DATE`.

## Tests

Write tests before implementation when adding policy or state-transition logic. Put testable decisions in pure Kotlin or TypeScript modules instead of Android components or Svelte views.

```bash
./gradlew :app:testDebugUnitTest
pnpm --dir frontend test
pnpm --dir frontend run check
```

For display, touch, audio, screen-off, or Shizuku changes, also verify the behavior on a physical device.

## Pull requests

1. Create a focused branch.
2. Update tests and current documentation with the code.
3. Run the relevant Android and frontend checks.
4. Open the pull request against `master` and describe user-visible behavior and manual verification.

The repository requires one release label: `major`, `minor`, `patch`, or `chore`.

## Translations

Android strings are stored in `app/src/main/res/values*`. Frontend strings are in `frontend/src/lib/i18n.ts`. Update both surfaces when a user-facing term exists in both.

## License

Contributions are licensed under the [Apache License 2.0](LICENSE).
