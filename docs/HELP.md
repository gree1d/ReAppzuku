# 📖 ReAppzuku — FAQ

> Complete guide to configuring and using the app

---

## Table of Contents

- [What is ReAppzuku?](#what-is-reappzuku)
- [Requirements](#requirements)
- [Background operation setup](#background-operation-setup)
- [Quick start](#quick-start)
- [Manual control](#manual-control)
- [Settings](#settings)
  - [Information](#-information)
  - [Appearance](#-appearance)
  - [Automation](#-automation)
  - [Auto-Kill settings](#-auto-kill-settings)
  - [Additional tools](#-additional-tools)
  - [Statistics and logs](#-statistics-and-logs)
  - [About](#-about)
- [Protected apps](#protected-apps)
- [Configuration examples](#configuration-examples)
- [FAQ](#faq)

---

## What is ReAppzuku?

**ReAppzuku** is an app for managing background processes on Android. It force-stops unnecessary background apps, freeing up RAM and extending battery life.

---

## Requirements

| Requirement | Description |
|---|---|
| **Android** | 6.0 and above. Background restrictions are only available on Android 11+ |
| **Root** or **Shizuku** | One of the two is required for features to work |

### Root vs Shizuku

- **Root** — preferred mode, used automatically if available
- **Shizuku** — a root-free alternative. Installed from the Play Store, requires initial setup via ADB or MIUI/HyperOS developer mode

> The current operating mode is always shown in **Settings → Information → Operating mode**

---

## Background operation setup

For ReAppzuku to run stably and not be killed by the system, you need to configure permissions correctly. The required steps depend on your firmware.

---

### Battery optimization (all firmwares)

The most important step. If not disabled, the system will periodically kill ReAppzuku.

**Settings → Apps → ReAppzuku → Battery → Unrestricted**

Or via the system dialog:
**Settings → Battery → Battery optimization → All apps → ReAppzuku → Don't optimize**

---

### Pinning in Recents (all firmwares)

Open the Recents screen (square button or swipe from the bottom), find the ReAppzuku card, and tap the **lock icon** 🔒. This prevents the app from being unloaded when Recents is cleared.

---

### MIUI / HyperOS (Xiaomi, Redmi, POCO)

<details>
<summary>Expand instructions</summary>

**Autostart:**
Settings → Apps → Manage apps → ReAppzuku → Autostart → Enable

**Background activity:**
Settings → Apps → Manage apps → ReAppzuku → Battery saver → No restrictions

**Lock in Recents:**
Recents screen → long-press the ReAppzuku card → tap the lock icon

**Additional (MIUI 12+):**
Settings → Apps → Manage apps → ReAppzuku → Other permissions → Run in background → Allow

</details>

---

### One UI (Samsung)

<details>
<summary>Expand instructions</summary>

**Allow background activity:**
Settings → Device care → Battery → Background usage limits → Sleeping apps → make sure ReAppzuku is not in the list

**Disable Adaptive battery:**
Settings → Device care → Battery → More battery settings → Adaptive battery → Off (optional, if issues persist)

**Autostart:**
Settings → Apps → ReAppzuku → Battery → Unrestricted

</details>

---

### ColorOS / OxygenOS (OPPO, OnePlus, Realme)

<details>
<summary>Expand instructions</summary>

**Autostart:**
Settings → App management → ReAppzuku → Autostart → Enable

**Background activity:**
Settings → App management → ReAppzuku → Battery saver → No restrictions

**Additional:**
Settings → Battery → Battery optimization → ReAppzuku → Don't optimize

</details>

---

### Flyme (Meizu)

<details>
<summary>Expand instructions</summary>

**Autostart:**
Settings → Permissions → Autostart → ReAppzuku → Enable

**Background activity:**
Settings → Permissions → Background execution → ReAppzuku → Enable

**App security:**
Security Center → Permission manager → ReAppzuku → enable all permissions

</details>

---

### OriginOS / Funtouch OS (Vivo)

<details>
<summary>Expand instructions</summary>

**Autostart:**
Settings → Apps → App management → ReAppzuku → Permissions → Autostart → Enable

**Background activity:**
Settings → Apps → App management → ReAppzuku → Power consumption → High background performance

</details>

---

### MagicOS (Honor)

<details>
<summary>Expand instructions</summary>

**Autostart:**
Settings → Apps → App launch → ReAppzuku → Manual → Autostart, Background activity

**Battery:**
Settings → Battery → App launch → ReAppzuku → No restrictions

</details>

---

### How to verify everything is configured correctly

After setup:
1. Enable the **Background service** in ReAppzuku settings
2. Lock the screen for 10–15 minutes
3. Unlock and open ReAppzuku — the service should be active
4. If the service stopped — repeat the steps for your firmware

> 💡 Up-to-date instructions for specific devices can also be found at [dontkillmyapp.com](https://dontkillmyapp.com)

---

## Quick start

1. Install and open ReAppzuku
2. Grant Root access or set up Shizuku
3. The main screen will show a list of active background apps
4. Select apps and tap the **Kill** button — or configure automation

---

## Manual control

Features that work **without the background service**.

### Main screen

- View all active background processes with RAM usage
- Select multiple apps and kill them all at once
- Long-press an app — quickly add it to the whitelist
- Menu (⋮) next to each app — extended actions

### Quick Tiles

Added to the notification shade:

| Tile | Action |
|---|---|
| **Stop app** | Kills the current foreground app |
| **Stop background apps** | Runs Auto-Kill with your whitelist/blacklist settings |

### Widget

A home screen widget — one tap runs Auto-Kill and shows the current RAM state.

### Shortcut

A static shortcut via long-press on the app icon — kills the current foreground app.

---

## Settings

### 🔵 Information

#### ReAppzuku operating mode

Shows the current mode of access to system commands: **Root**, **Shizuku**, or **No access**. Informational only, not editable.

#### Help

Link to this FAQ.

---

### 🎨 Appearance

#### App theme

Choose a theme: system, light, dark, or AMOLED.

#### Color accent

Choose the app's color accent: indigo, crimson, forest green, amber, and other shades.

#### Show system apps

Enables the display of system apps in the main list. A warning appears on first enable — it is best not to touch system apps.

#### Show critical apps

Enables the display of persistent processes (apps with the `persistent` flag) in the main list.

---

### ⚙️ Automation

> ⚠️ All options in this section require the **Background service** to be enabled

#### Background service

The main automation toggle. Starts a persistent background process for ReAppzuku. Without it, periodic Auto-Kill and Auto-Kill on screen lock will not work.

#### Periodic Auto-Kill

Automatically performs Kill (force-stop) of apps on an interval while the background service is running.

#### Auto-Kill interval

| Interval | Description |
|---|---|
| 10 seconds | Maximum aggressive cleanup |
| **18 seconds** | Default |
| 30 seconds | Moderate cleanup |
| 1 minute | Soft cleanup |
| 5 minutes | Minimal intervention |

#### Kill on screen off

Performs Kill (force-stop) at the moment the screen locks. Useful for cleaning up every time you put your phone down.

#### Kill on RAM load

An additional condition — Kill triggers **only if** RAM usage exceeds the selected threshold. Applies to both periodic Kill and screen-off Kill.

| Threshold | Description |
|---|---|
| 75% | Early cleanup |
| **80%** | Default |
| 85–95% | Cleanup only when memory is genuinely low |
| 100% | Only in critical situations |

---

### 🎯 Auto-Kill settings

#### Auto-Kill mode

Determines **which** apps Auto-Kill affects.

**🛡️ Whitelist** — kills all background apps **except** those added to the whitelist. Use this for maximum cleanup.

**🎯 Blacklist (default)** — kills **only** apps from the blacklist. Use this to selectively stop specific apps without touching others.

#### White / Black list

The list of apps for the selected mode. Depending on the mode, one of the two lists is displayed.

---

### 🔧 Additional tools

#### Background restrictions

> Available only on **Android 11+**

Uses Android's system mechanism (`appops`) to **block an app from running in the background at the OS level**. A deeper tool than a regular Kill.

| | Regular Kill | Background restrictions |
|---|---|---|
| How it works | Force-stops the process | Prevents Android from launching the process in the background |
| Can restart | ✅ Yes | ❌ No |
| Persists after reboot | ❌ No | ✅ Yes |
| Requires Android 11+ | ❌ No | ✅ Yes |

**Types of restrictions**
- Soft (RUN_ANY_IN_BACKGROUND ignore)\
Blocks the app from auto-starting at a stricter level than standard activity settings.\
**How it works**: If you opened an app and minimized it, it will continue running (while in Recents). But on its own (at night or in the background) it will not wake up until you open it.

- Hard (Soft restriction + START_FOREGROUND ignore + RECEIVE_BOOT_COMPLETED ignore + Removal from battery optimization whitelist)\
Blocks any background activity for the app.\
**How it works**: As soon as you minimize the app or switch to another one, the system immediately "kills" it. It cannot keep itself in memory for a single second without your direct attention (even if it appears in Recents). Be careful with the Hard restriction, as it may completely prevent an app from performing any background operations (downloading files, playing media, long-running internal tasks).

Statuses in the list:
- **Saved in ReAppzuku** — saved, but the status from the system is unknown (no permission)
- **Saved in ReAppzuku, but not applied** — saved, but Android has not applied the restriction
- **Restricted, but not by ReAppzuku** — restricted by Android or another app

> ⚠️ Be careful with system apps — restricting their background activity may break notifications, VPN, widgets, and the keyboard

#### Re-apply background restrictions

Manually re-applies all saved restrictions. After a reboot this happens **automatically** when the background service starts.

#### Sleep Mode

Completely **freezes** selected apps when the device is idle. Unlike background restrictions — the app simply cannot start, it is fully disabled by the system.

How it works:
1. Screen turns off → a timer starts
2. Timer expires → selected apps are frozen
3. Screen turns on and is unlocked → apps are unfrozen automatically

> ⚠️ Only works with user apps — system apps cannot be frozen (limitation of modern Android)

> ⚠️ When Sleep Mode is enabled, the app restarts — this is necessary for correct initialization

#### Sleep Mode app list

Choose which apps will be frozen in Sleep Mode.

#### Freeze timer

The inactivity period after which freezing triggers: from **5 to 60 minutes** (default 60 minutes).

#### Clear cache for all apps

Runs the system command `pm trim-caches` — clears the cache of all apps at once.

#### Hidden apps

Apps in this list are not shown in the main list and are never affected by Auto-Kill. Useful for service processes you don't need to see.

#### Backup and restore

Export and import all settings to a JSON file. Saves the whitelist, blacklist, hidden apps, background restrictions, Sleep Mode, and all automation parameters.

---

### 📊 Statistics and Logs

#### Auto-Kill log

Shows activity over the last **12 hours**: number of Auto-Kills, restarts, freed RAM, and the time of the last event for each app.

> 💡 Apps that restart more than 3 times are good candidates for Background restrictions. You can apply restrictions to all such apps in one tap directly from the log.

#### Top offenders

App ranking by combined score (Kills + restarts + RAM volume). Filterable by: 12 hours / 24 hours / 7 days / all time.

#### Background Restrictions log

Detailed log of background restriction operations. Stored in cache, maximum 200 entries.

| Status | Meaning |
|---|---|
| `Sent` | Command executed successfully (may not be applied by the system) |
| `Applied` | Restriction confirmed by the system (100% result) |
| `NOT APPLIED` | Command executed, but the system did not apply the change |
| `ERROR` | Command finished with an error |
| `Skipped` | Operation was not performed (no permission, Android < 11, etc.) |
| `Verification unavailable` | Could not query the actual state from the system |
| `Removed from whitelist` | App removed from battery optimization exceptions |
| `Restored to whitelist` | App restored to battery optimization exceptions |

---

### ℹ️ About

#### Source code

Link to the GitHub repository.

#### Check for updates

Link to the GitHub releases page.

---

## Protected apps

The following apps are **never affected** by Auto-Kill regardless of settings:

- ReAppzuku (itself)
- Google Play Services
- System UI
- Bluetooth
- External storage
- Current keyboard (detected automatically)
- Current launcher (detected automatically)

---

## Configuration examples

### 🔋 Maximum battery saving
```
Background service: ON
Periodic Auto-Kill: ON, interval 18 sec
Kill on screen off: ON
RAM limit: OFF
Mode: Whitelist
```

### ⚖️ Balanced mode
```
Background service: ON
Periodic Auto-Kill: ON, interval 1 min
Kill on screen off: ON
RAM limit: ON, threshold 80%
Mode: Whitelist
```

### 🎯 Targeted control
```
Background service: ON
Periodic Auto-Kill: ON, interval 5 min
Kill on screen off: OFF
RAM limit: OFF
Mode: Blacklist (only selected apps)
```

### 😴 Maximum overnight freeze
```
Background service: ON
Kill on screen off: ON
Sleep Mode: ON, timer 15 min
Background restrictions: on aggressive apps
```

---

## FAQ

**❓ An app restarts immediately after Kill — what should I do?**

Add it to **Background restrictions**. This will prevent Android from restarting it in the background at the system level.

---

**❓ Background restrictions are lost after a reboot**

Enable the **Background service** — when it starts after a reboot, it automatically restores all saved restrictions.

---

**❓ Which mode should I choose — whitelist or blacklist?**

Whitelist — if you want to stop everything except what's important. Blacklist — if you want to stop only specific apps without touching the rest.

---

**❓ Is the background service needed for manual Kill?**

No. Manual Kill from the main screen, quick tiles, widget, and shortcut all work without the background service.

---

**❓ Is it safe to stop system apps?**

No. Stopping or restricting system apps can cause device instability, freezes, lost notifications, and boot loops. ReAppzuku warns you when you attempt to affect system apps.

---

**❓ What is the difference between Sleep Mode and Background restrictions?**

Background restrictions — the app cannot **start** in the background, but remains installed and visible. Sleep Mode — the app is completely **frozen** by the system, as if disabled, until the screen is unlocked.

---

**❓ Shizuku stopped working after a reboot**

Shizuku requires re-activation after every reboot (unless wireless mode is used). Open the Shizuku app and start the service again.
