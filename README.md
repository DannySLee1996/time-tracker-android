# Time Tracker Android

Native phone app and Wear OS app for the time tracker. The phone stores timers in Room and syncs them to the Rails GraphQL API when the network returns. The watch stores its own timer and exchanges snapshots with the phone over the Wear data layer, so a start or stop survives a dropped Bluetooth link.

Galaxy Watch Ultra and Galaxy Watch 8 Ultra run Wear OS, so this module is a standard Wear OS app.

## Build

Use Android Studio or the Gradle wrapper from Windows. Point `local.properties` at your Android SDK.

```powershell
.\gradlew.bat :core:test :app:assembleDebug :wear:assembleDebug
```

The debug server address defaults to `http://10.0.2.2:3000/graphql`, which is the emulator's route to a Rails server on the host. Change it on the sign-in screen for a phone on the same network.

Both APKs use the application id `com.timetracker` so the data layer can see them. Install the phone module on the phone and the wear module on the watch.
