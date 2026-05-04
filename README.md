# Bluetooth Radar

This is a copy of [https://github.com/BLE-Research-Group/MetaRadar](https://github.com/BLE-Research-Group/MetaRadar) that has been stripped for parts and modded (Clauded ;)) to be the minimal functionality I need: enumerating all GATT devices and exporting the data in [BTIDES](https://github.com/BLE-Research-Group/MetaRadar) format (with GPS data for the devices as a nice-to-have).

# Install app via macOS

Prerequisites:
- JDK 21 (the project's `kotlinc` doesn't recognise newer JDK version strings; using 21 keeps the bundled compiler happy):
  ```sh
  brew install openjdk@21
  export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
  ```
- Android command-line tools + platform-tools (provides `adb`):
  ```sh
  brew install --cask android-commandlinetools
  ```
- A phone with USB debugging enabled (Settings → Developer options → USB debugging) and authorized for this computer.

Build + install:
```sh
git clone https://github.com/XenoKovah/MetaRadar.git XenoMetaRadar
cd XenoMetaRadar
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew :app:assembleGithubDebug
/opt/homebrew/share/android-commandlinetools/platform-tools/adb install -r app/build/outputs/apk/github/debug/app-github-debug.apk
```

# Install app via Linux

Prerequisites (Debian/Ubuntu syntax — adapt for your distro):
- JDK 21:
  ```sh
  sudo apt install -y openjdk-21-jdk
  export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
  ```
- Android platform-tools (provides `adb`):
  ```sh
  sudo apt install -y android-sdk-platform-tools
  ```
- A phone with USB debugging enabled and authorized. On Linux you may also need a udev rule for your phone vendor; if `adb devices` shows your phone as `???? no permissions`, see [https://developer.android.com/studio/run/device#setting-up](https://developer.android.com/studio/run/device#setting-up).

Build + install:
```sh
git clone https://github.com/XenoKovah/MetaRadar.git XenoMetaRadar
cd XenoMetaRadar
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :app:assembleGithubDebug
adb install -r app/build/outputs/apk/github/debug/app-github-debug.apk
```
