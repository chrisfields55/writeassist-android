# WriteAssist Android

Android AI writing assistant designed to work alongside Gboard using Android's text-selection and sharing actions.

## Build

GitHub Actions builds an unsigned-for-release, debug-signed installable APK using Gradle and Android SDK. Download it from the **Actions** tab under the latest **Build Android APK** run, in the `writeassist-debug-apk` artifact.

## Security

Enter your AI API key inside the app. Do not commit keys to this repository. Text is sent to the configured HTTPS AI endpoint only when you tap **Improve with AI**.

## Status

Prototype. Android device testing has not yet been completed. Text replacement is only available in apps that support Android's PROCESS_TEXT result contract.
