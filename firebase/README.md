# Xfiles Firebase setup (feedback)

In-app feedback is stored in Cloud Firestore, collection `feedback`, one document per report. It is only
compiled into the prod build with `-Pxfiles.firebaseCrashReports=true` and `androidApp/google-services.json`.
Other builds open the share sheet instead.

The rules in `firestore.rules` let the app **create** feedback and nothing else. Clients cannot read,
change or delete reports. The team reads them in the console.

## One-time console setup

1. Open the Firebase project that matches `androidApp/google-services.json` (package `com.xfiles`).
2. Build > Firestore Database > **Create database**. Pick a location near your users (it cannot be changed
   later). Choose production mode; the rules file replaces the default rules.
3. Optional but recommended: Build > App Check. Register the Android app with Play Integrity, then turn on
   enforcement for Cloud Firestore. This stops scripts that are not the Xfiles app from creating feedback.
   Enabling it needs an App Check client in the app, which this change does not add; enable it only when
   you are ready to ship that step.
4. Reading feedback: Firestore > `feedback`. Consider a Firestore index only if you later query by
   `createdAt` or `category`; the console lists documents without one.

## Deploy the rules

```
npm install -g firebase-tools
firebase login
cd firebase
firebase use <your-project-id>
firebase deploy --only firestore:rules
```

Run the deploy again after any change to `firestore.rules`. Deploying rules does not change the data.

## Document shape

| Field | Type | Notes |
| --- | --- | --- |
| `category` | string | `bug`, `idea` or `other` |
| `message` | string | 1 to 2000 characters |
| `createdAt` | timestamp | Server time (`request.time`) |
| `replyTo` | string | Optional, validated email |
| `appVersion`, `versionCode`, `deviceModel`, `androidSdk`, `flavor`, `locale` | string / int | Optional; only when the user keeps "Include app version and device model" on |

No file names, paths, document text or assistant conversations are sent.

## Client limits (for reference)

- One send per 30 seconds per phone, stored on the phone.
- Offline reports wait in Firestore's on-device cache and send when the phone is online.
