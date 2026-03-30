# Appzuku
![Logo](https://github.com/northmendo/Appzuku/blob/f58b2f820655d7ca59ee699159965b110e23f1d5/docs/images/logo.png)
<p align="center">
  <img src="https://img.shields.io/badge/License-GPLv3-blue.svg"/>
</p>


## What is Appzuku?
Appzuku is an Android application that stops background apps using either Shizuku or Root permissions. It supports one-tap manual kills, scheduled cleanup, and persistent background restriction for selected apps. The goal is straightforward control over apps that waste RAM, relaunch too often, or keep waking up in the background.

## Features
- **Flexible Permissions**: Works with either Shizuku or Root access.
- **Manual Kill Tools**: Kill selected apps from the main screen, the current foreground app from Quick Settings, or run your configured background kill from a second Quick Settings tile.
- **Automation**: Periodic auto-kill, kill on screen lock, and Smart RAM Threshold controls.
- **Targeting Modes**: Use Whitelist mode to protect apps from kills or Blacklist mode to target only selected apps.
- **Background Restriction (Android 11+)**: Keep selected apps asleep in the background with AppOps-based restriction.
- **Restriction Repair & Log**: Re-apply saved restrictions and review recent restriction results/errors from Settings.
- **Kill History & Top Offenders**: Review which apps were killed, which ones relaunch, and which apps recover the most RAM.
- **Backup & Restore**: Save and restore your configured app lists.
- **Search & Filter**: Quickly find apps in your running list by name or package ID.
- **Theme Customization**: Support for Light, Dark, and System Default themes.
- **Protected Apps**: System-critical apps and user-whitelisted apps are protected from being killed.
- **RAM Monitoring**: Real-time display of system RAM usage.

## Screenshots
<p align="center">
  <img src="https://github.com/northmendo/Appzuku/blob/main/docs/images/screenshot0.jpg" width="30%">
  <img src="https://github.com/northmendo/Appzuku/blob/main/docs/images/screenshot1.jpg" width="30%">
  <img src="https://github.com/northmendo/Appzuku/blob/main/docs/images/screenshot2.jpg" width="30%">
</p>

## Requirements
- **Android Version**: 6.0 (SDK 23) or higher.
- **Shizuku or Root**: Appzuku requires Root access or the Shizuku app to be running.
- **Android 11+ for Background Restriction**: The app installs on Android 6+, but the Background Restriction feature requires Android 11 or newer.

## How It Works
- **Main Screen**: Kill selected running apps manually.
- **Quick Settings Tiles**: One tile kills the current foreground app, and another runs your configured whitelist/blacklist background kill.
- **Automation**: Run cleanup periodically or when the screen turns off.
- **Background Restriction**: Keep chosen apps asleep more aggressively on Android 11+.

## License
Appzuku is licensed under the [GNU General Public License v3.0](LICENSE).

## Credits
This project was forked from [northmendo/Appzuku](https://github.com/northmendo/Appzuku).
