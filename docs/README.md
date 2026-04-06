**English** | [Русский](https://github.com/gree1d/ReAppzuku/blob/main/docs/README_RU.md)

---

![Logo](https://github.com/gree1d/ReAppzuku/blob/main/docs/images/logo.png)
<p align="center">
<img src="https://img.shields.io/github/v/release/gree1d/ReAppzuku?label=Release&logo=github" alt="Latest Release">
<img src="https://img.shields.io/badge/License-GPLv3-brown.svg"/>
<img src="https://img.shields.io/badge/Android-6.0%2B-yellow.svg"/>
<img src="https://img.shields.io/badge/Root-Supported-brightgreen.svg"/>
<img src="https://img.shields.io/badge/Shizuku-Supported-brightgreen.svg"/>
</p>

ReAppzuku is a fork of Appzuku (Shappky) that offers enhanced control over background activity of Android applications.

The tool allows users to monitor and control apps that consume RAM, run in the background for extended periods, draining battery and loading the CPU.\
One-tap manual force-stop, periodic Kill via a timer, and flexible background restrictions for selected apps.\
\
Root or Shizuku privileges are required to run the app.


## ⚙️ Key Features
 * Smart automation:
   * Periodic Auto-Kill: intervals from 10 seconds to 5 minutes.
   * Kill on screen lock: force-stop background processes immediately after the screen turns off.
   * RAM threshold: Kill triggers only when RAM usage reaches a set limit (75%–100%).
 * Deep restrictions (Android 11+):
   * Soft mode: blocks auto-start without your knowledge.
   * Hard mode: immediately terminates a process when minimized, prevents it from being kept in memory.
 * Sleep Mode: Full freeze (`pm disable`) of selected apps after a set inactivity timer (5–60 min), with automatic unfreeze on screen unlock.
 * Analytics & Logs:
   * Detailed Auto-Kill log for the last 12 hours.
   * "Offenders" ranking by RAM consumption and restart frequency.
   * Tracking of background restriction status (applied, error, not applied).
 * Flexible lists: Support for a Whitelist (Auto-Kill exclusions) and a Blacklist (Auto-Kill targets).

## 🛠 Requirements
| Component | Requirement |
|---|---|
| Android OS | 6.0+ (Background restrictions require 11+) |
| Access | Root or Shizuku |

## 🚀 Quick Start
 * Set up the environment: Install and activate [Shizuku](https://github.com/thedjchi/Shizuku).
 * Background operation: It is critical to disable battery optimization for ReAppzuku and pin it in the Recents menu so the system does not kill the management service itself.
 * Choose your strategy:
   * Maximum savings: Whitelist + periodic Kill + Background restrictions.
   * Targeted control: Blacklist only for heavy messengers or games.

## 🛡 Safety
ReAppzuku automatically protects critical system processes (Google Play Services, System UI, the current keyboard, and the launcher), preventing the risk of a boot loop.

## 🎨 Customization
 * Support for system, light, dark, and AMOLED themes.
 * Configurable color accents (Indigo, Crimson, Amber, and more).


## License
ReAppzuku is licensed under the [GNU General Public License v3.0](LICENSE).

## Credits
This project was forked from [northmendo/Appzuku](https://github.com/northmendo/Appzuku).
