# Xfiles reporting through Firestore

Firebase-enabled Android builds create documents directly in two collections.

| Collection | Contents | When written |
| --- | --- | --- |
| `feedback` | Category, message, server timestamp, and optional app and device details | The user taps Send |
| `crash_reports` | App version, platform, server timestamp, and the previewed technical report | The user taps Send report, or reopens Xfiles after a crash with automatic reporting enabled |

Crash reports include app and OS versions, device model, crash time, and stack frames. Exception messages
are omitted from the upload. Automatic reporting is off until the user enables it in Settings → Diagnostics.
The local crash recorder captures uncaught Java and Kotlin exceptions. Native crashes and ANRs are outside
its coverage. If Firestore is unavailable, Xfiles keeps the crash report on the phone for retry.
Feedback can queue locally through Firestore.

The rules permit validated creates. Clients cannot read, update, or delete reports. Use the Firebase
console to inspect submissions. Email addresses are not requested by either reporting form.

## Configure Android and GitHub Actions

Register the Android package `com.xfiles` in your Firebase project and enable Cloud Firestore. Put the
project's `google-services.json` in `androidApp/` for local prod builds. This file is gitignored. The
`xfiles.firebaseCrashReports` Gradle property can explicitly enable or disable Firestore for prod.
Dev builds and unconfigured prod builds have no report sender.

For release builds, set the GitHub Actions secret `XFILES_GOOGLE_SERVICES_JSON_BASE64` to the base64
encoding of the matching Android configuration. The release workflow supplies it to Gradle when present.

## Deploy the rules

With the Firebase CLI authenticated to the same project, run from this directory:

```sh
firebase deploy --project <project-id> --only firestore:rules
```

Deploy the rules before distributing a release with Firestore reporting. The collection rules validate
payloads and block client reads. They do not authenticate submitters or rate-limit writes at the server.
Firebase App Check is a separate rollout and requires a matching app integration before enforcement.
