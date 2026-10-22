<div align="center">

<img src="shots/logo.png" width="96" alt="CarApp icon">

# CarApp

**One app for the phone and the dash tablet.** One tap on the phone starts car mode: hotspot, mobile data, location, and the tablet wakes, connects the car stereo and opens your saved app pair. The tablet gets the phone's GPS and turns into a reverse camera when the car is in reverse.

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
| GO / Shut down and the GO widget | Drive widget (opens CarApp) |
| Turns on hotspot, mobile data, location | Wi-Fi and Bluetooth on, connects the car stereo |
| Sends GPS over the hotspot | Receives GPS and feeds it to Maps as a mock location |
| | Opens the saved launcher app pair chosen in Settings |
| | Reverse camera from a USB webcam |

## GO and Shut down

One tap on the phone runs the full sequence, a second tap shuts down and restores. Every step checks first and skips what is already on; Shut down only undoes what GO turned on.

| GO does | Shut down does |
| --- | --- |
| Phone: hotspot, mobile data, location on | Turns off only those GO turned on |
| Tablet: wakes, Wi-Fi and Bluetooth on, connects the car stereo | Disconnects the stereo, Wi-Fi and Bluetooth back as before |
| Tablet: opens the saved app pair | Closes CarApp and the pair, screen off if it was |
| Shares GPS with the tablet | Stops GPS and the link |

## Features

| | |
| --- | --- |
| **GPS sharing** | The phone sends its location over the hotspot (UDP 47474), the tablet replays it as a mock location. For tablets without working GPS. |
| **Rear camera** | A USB webcam powered from the reverse light. Reverse on, the camera appears full screen, even over the lockscreen. Reverse off, it closes. |
| **Saved app pair** | On GO the tablet taps the launcher pair you chose (Settings, Drive, Open on GO), for example Maps/Spotify. CarApp never builds a split itself. |
| **Hotspot auto-join** | The tablet joins the phone's hotspot on its own. |
| **Boot autostart** | GPS and the receivers start on boot. |
| **Debug screen** | A check per part (helper, mock location, GPS, hotspot join, car stereo, rear camera, boot receiver) with Fix and test buttons. |

## Known limits

- Android 16 has no app API for hotspot, mobile data, location or Wi-Fi, and Bluetooth on or off is a system dialog. The optional CarApp helper (Accessibility) taps the Quick Settings tiles and dialog buttons; with it off, CarApp opens each system screen for you.
- ZUI keeps app pairs inside its launcher and starts them through hidden APIs, so the helper taps the pair icon. Keep the pair on the first home screen page.
- The helper matches English tile labels. A system update can move them and break the tapping until CarApp is updated.
- `am force-stop` unbinds the Accessibility service on OnePlus. Re-enable it in Settings > Accessibility.

## Setup

1. Install the APK on both the phone and the tablet.
2. Open CarApp on each and grant the one-time permissions it asks for (location, nearby devices on the tablet, notifications, camera and microphone for the webcam, battery optimisation exemption on the tablet).
3. Turn on CarApp helper in Settings > Accessibility on both devices.
4. On the tablet, set CarApp as the mock location app in Developer options > Select mock location app.
5. On the tablet, plug in the webcam once and pick "Always open CarApp" for that USB device.
6. On the tablet, save an app pair on the launcher (long press an app, Split screen), show the home screen once, then pick it in Settings, Drive, Open on GO. Pick the car stereo in Settings.
7. Add the GO widget on the phone.

Settings sections: Car stereo and Drive (tablet), GPS, Camera, Connection (hotspot), Advanced (device role, shut down, help, debug).

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

CarApp makes no internet requests and collects no data. GPS and settings only travel between the phone and the tablet on the phone's hotspot. The Accessibility service is used only to operate system panels: the Bluetooth prompts, the hotspot, mobile data, location and Wi-Fi Quick Settings tiles, and the launcher's app pair icon.

<details>
<summary><b>Testing notes</b></summary>

<br>

Watch live logs with `adb logcat -d | grep " E LazyCar"` (the app logs at error level).

</details>

## License

MIT. See [`LICENSE`](LICENSE).

---

<div align="center"><sub>Built with Kotlin, Material 3, and one Accessibility service.</sub></div>
