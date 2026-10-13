<div align="center">

<img src="shots/logo.png" width="96" alt="CarApp icon">

# CarApp

**One app for the phone and the dash tablet.** One tap on the phone starts car mode: Bluetooth speaker, tablet speaker over Wi-Fi, hotspot, music. The tablet gets the phone's GPS and turns into a reverse camera when the car is in reverse.

[![Release](https://img.shields.io/github/v/release/nathanrodrigues2111/lazycar?sort=semver&color=3DDC84)](https://github.com/nathanrodrigues2111/lazycar/releases/latest)
![Platform](https://img.shields.io/badge/platform-Android%2012%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?logo=kotlin&logoColor=white)
![License](https://img.shields.io/badge/license-MIT-blue)
![Tested](https://img.shields.io/badge/tested%20on-OnePlus%2015%20%2B%20Lenovo%20Tab%20Plus-EB0028?logo=android&logoColor=white)

[![Download APK](https://img.shields.io/badge/Download%20APK-15171C?style=for-the-badge&logo=android&logoColor=3DDC84)](https://github.com/nathanrodrigues2111/lazycar/releases/latest)

<img src="shots/main.png" width="200" alt="Main screen">
<img src="shots/settings.png" width="200" alt="Settings">
<img src="shots/help.png" width="200" alt="Help">

<sub>GO screen &nbsp;&bull;&nbsp; Settings &nbsp;&bull;&nbsp; Help</sub>

</div>

## Device roles

The same APK goes on both devices. The role is detected from the device model (the Lenovo Tab Plus TB351FU is the tablet, anything else is the phone) and can be overridden in Settings.

| Phone | Tablet |
| --- | --- |
| GO / STOP car mode and the GO widget | Drive widget (split screen launch) |
| Plays to one Bluetooth speaker | Plays the phone's audio over Wi-Fi |
| Sends GPS over the hotspot | Receives GPS and feeds it to Maps as a mock location |
| Runs the hotspot | Auto-joins the phone's hotspot |
| | Reverse camera from a USB webcam |

## GO and STOP

One tap on the phone runs the full sequence, a second tap stops and restores. STOP only undoes what GO turned on.

| GO does | STOP does |
| --- | --- |
| Turns Bluetooth on and connects the chosen speaker | Restores the Bluetooth state from before GO |
| Optionally turns on hotspot and mobile data | Reverts hotspot and mobile data it changed |
| Streams audio to the tablet over Wi-Fi | Stops the stream |
| Sets an optional start volume | Leaves already-on features untouched |
| Launches your music player and presses play | |

## Features

| | |
| --- | --- |
| **GPS sharing** | The phone sends its location over the hotspot (UDP 47474), the tablet replays it as a mock location. For tablets without working GPS. |
| **Rear camera** | A USB webcam powered from the reverse light. Reverse on, the camera appears full screen, even over the lockscreen. Reverse off, it closes. |
| **Tablet speaker** | The phone's audio plays on the tablet over Wi-Fi, auto synced to the Bluetooth speaker using the measured Bluetooth latency and clock offset. |
| **Bluetooth speaker** | One Bluetooth speaker on the phone. |
| **Split screen** | The tablet's Drive widget opens Maps and the music app side by side. |
| **Hotspot auto-join** | The tablet joins the phone's hotspot on its own. |
| **Boot autostart** | GPS and the receivers start on boot. |
| **Debug screen** | A check per part (permissions, accessibility, mock location, GPS, hotspot join, rear camera, boot receiver) with Fix and test buttons. |

## Known limits

- Spotify blocks audio capture on its tracks, so it cannot play on the tablet speaker. The default player is YouTube Music.
- OxygenOS 16.0.10.601 removed classic dual audio (the old two speaker Audio sharing), so the phone drives one Bluetooth speaker and the tablet is the second one over Wi-Fi.
- ZUI on the Lenovo tablet ignores the standard split screen launch. Split screen works only by tapping a saved Maps + music app pair on the launcher, so save that pair first.
- The auto-tap depends on SystemUI element ids. A system update can move them and break the tapping until CarApp is updated.
- Reinstalling via `adb install -r` (or `am force-stop`) unbinds the Accessibility service on OnePlus. Re-enable it in Settings > Accessibility.

## Setup

1. Install the APK on both the phone and the tablet.
2. Open CarApp on each and grant the one-time permissions it asks for (location including background, nearby devices, notifications, camera, microphone and screen capture for the audio stream, battery optimisation exemption).
3. Enable CarApp in Settings > Accessibility on both devices.
4. On the tablet, set CarApp as the mock location app in Developer options > Select mock location app.
5. On the tablet, plug in the webcam once and pick "Always open CarApp" for that USB device.
6. On the tablet, save a Maps + music app pair on the launcher (for split screen).
7. Add the GO widget on the phone and the Drive widget on the tablet. Pick the speaker, player, and start volume. Settings save themselves.

## Rear camera hardware

The app does not detect reverse gear. The webcam's USB power is cut by a relay driven by the reverse lamp, so reverse plugs the camera in and Android auto-launches CarApp on `USB_DEVICE_ATTACHED`.

Parts: a UVC USB webcam, a USB 2.0 extension (5 m passive max, use an active one for longer), a 12 V relay or relay module, a USB-C OTG adapter, an add-a-fuse tap.

```
 Reverse lamp +12V ──────────┐
 Reverse lamp GND ────────┐  │
                          │  │  (coil)
                       ┌──┴──┴──┐
                       │ RELAY  │
                       └──┬───┬─┘
                          │   │  (contacts, normally open)
 USB extension red (+5V) ─┘   └─ USB extension red (+5V) continues
        the other 3 USB wires (D+, D-, GND) pass through uncut

 TABLET ──[USB-C OTG]──[USB extension, +5V through relay]──[WEBCAM]
```

- Cut only the red +5 V wire of the extension and route it through the relay's normally open contacts.
- A bare relay needs a 1N4007 across the coil. Relay modules have it built in.
- Use a Swipe lockscreen on the tablet. Showing over the lockscreen does not work over PIN or pattern.
- Most tablets cannot charge while hosting USB. Use a powered USB-C hub with PD if the tablet supports host plus charge.

## Install

Download the APK from the [Releases page](https://github.com/nathanrodrigues2111/lazycar/releases/latest) and install it on both devices, or build from source:

- Install JDK 17 and the Android SDK.
- Run `./gradlew assembleDebug`.
- The APK lands at `app/build/outputs/apk/debug/CarApp.apk`.

## Privacy

CarApp makes no internet requests and collects no data. GPS and audio only travel between the phone and the tablet on the phone's hotspot. The Accessibility service is used only to operate system panels: the Bluetooth prompts, the hotspot and mobile data Quick Settings tiles, the screen capture consent, and the launcher's app pair icon.

<details>
<summary><b>Testing notes</b></summary>

<br>

Watch live logs with `adb logcat -d | grep " E LazyCar"` (the app logs at error level).

</details>

## License

MIT. See [`LICENSE`](LICENSE).

---

<div align="center"><sub>Built with Kotlin, Material 3, and one Accessibility service.</sub></div>
