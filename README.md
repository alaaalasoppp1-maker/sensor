# SOPIX T1 Android v3

Diagnostic Android USB Host build for ACTEON SOPIX T1 (VID 1CE6 / PID 0001).

## v3
- Cleaner Arabic interface.
- Automatic connect.
- One-button initialization and RAW capture.
- Replays the EP 0x06 command sequence extracted from the successful Windows capture `78.pcapng`.
- Reads status from EP 0x81 and image data from EP 0x82.
- Saves RAW files under the app external files directory.

GitHub Actions builds a debug APK on every push to `main`.
