# tcn-client-android
Android client reference implementation of the TCN protocol

## Installation
1. Add the JitPack repository to your build file. Add it in your root build.gradle at the end of repositories:
```
allprojects {
  repositories {
    ...
    maven { url 'https://jitpack.io' }
  }
}
```
2. Add the dependency
```
dependencies {
        implementation 'com.github.TCNCoalition:tcn-client-android:Tag'
}
```
## Storage

This library focuses on generating and observing CENs. It also defines a visitor interface with callbacks for the CENs. In order to process the callbacks further, you should:
- start the service, and
- listen to the CENs and store them according to your use case.

### Examples of this pattern:

- [Storing the data into a `RoomDatabase` for later dissemination](https://github.com/covid19risk/covidwatch-android/blob/82d26d726c083cebcd0d7951f5c1b0febc4fd999/app/src/main/java/org/covidwatch/android/ble/BluetoothManager.kt#L146)
