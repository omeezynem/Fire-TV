# TV Dash: a new home screen for Fire TV

TV Dash is a custom dashboard app for Amazon Fire TV (and any Android TV). It runs on its own and needs no accounts or API keys.

## What's on it
- **Big clock and date** in 12- or 24-hour style.
- **Weather**: current temperature, conditions, feels-like, humidity, wind, and a 3-day forecast from Open-Meteo, which is free. It finds your city from your internet connection, or you can set one.
- **Market ticker**: a strip of stock, ETF, and crypto prices. Each symbol shows its name, the latest price, and a green ▲ or red ▼ with today's percent change. It does not show quantities, balances, or holdings. The list starts empty, so the strip stays hidden until you add symbols. The same prices show on the screensaver.
- **Favorites row**: your streaming apps as large tiles using each app's own TV artwork. On first launch it pins whichever of Netflix, Prime Video, YouTube, Disney+, Hulu, Max, Peacock, Paramount+, Pluto, Plex and Spotify are installed.
- **All apps** grid with every app on the TV, sorted A–Z. It updates on its own when you install or remove an app.
- **System row**: one-click Fire TV Settings, Network, Manage apps, Dashboard settings, Work, Screensaver, and New photo.
- **Work**: a view-only map of sample open jobs. Dots are colored by trade. A coral ring marks a hot job. The remote moves between dots and shows a small card (trade, city, state, phase). It does not dispatch, edit, or show money, street addresses, or anyone's name or phone.
- **Status line**: whether you're online, plus free storage and free memory.
- **Photo slideshow background** that cross-fades every 15 s, 30 s, 1 min or 5 min. It shows random scenic photos, or your own photo links.
- **Screensaver**: after a few minutes without the remote, the dashboard fades away and leaves the photos, a clock, and the weather. Any button brings it back.

## Phone remote (works live with the TV)
TV Dash includes a phone remote. Your phone opens it in the browser, so there's nothing to install from an app store.

1. On the TV, go to the **System** row and choose **Phone remote**. Scan the QR code with your phone's camera, or type the address and the 4-digit PIN. You only do this once.
2. On iPhone, tap **Share → Add to Home Screen**. On Android, use **⋮ → Add to Home screen**. It then opens like a normal app, already signed in.

From your phone you can:
- Open any TV app. The app list and icons come straight from the TV.
- Pin, unpin and reorder Favorites.
- Send photos from your phone. They appear on the TV right away and join the slideshow.
- Show a message on the TV screen ("Dinner's ready 🍕").
- Turn the screensaver on or off and skip to the next photo.
- Change the weather city, °F/°C, clock style, photo timing and screensaver timing. The TV updates instantly.
- Set the stock, ETF, and crypto symbols for the market ticker.

The first time, the phone trades that PIN for a saved sign-in. After that it does not ask again, even if you close the page or the TV is reached at a different address. At home, use the Wi‑Fi address. Away from home, use the Tailscale address shown on the TV (it looks like `100.x.x.x`). The remote page also offers that link once you're signed in; open it once and add that to your home screen. The TV listens on every network interface, so a VPN address works as well as the home network.

Five wrong PINs still lock pairing for 5 minutes. **Sign out phones** on the TV makes a new PIN and signs every phone out. The remote keeps running in the background and starts when the TV turns on.

## Phone buttons in any app

The **Remote** tab on the phone is a direction pad: up, down, left, right, select in the middle, Back, Home, menu, settings, a keyboard, and play, rewind, and fast-forward. Your favorite apps sit in a strip under the pad. Photos, messages, and dashboard settings are on the **Apps** and **More** tabs.

Android does not let a normal app press buttons inside Netflix or any other app. TV Dash uses the TV's own debugging service, which is the same path as a real remote (`input keyevent`). One-time setup, on the TV:

1. **Settings → My Fire TV → About.**
2. Click the device name **7 times**, until it says you are a developer.
3. Go back, then open **Developer options**.
4. Turn on **ADB debugging**.
5. Open **Phone remote** and tap **Connect ADB** (or tap **Try connection** on the phone). If the TV asks to allow a connection, tap **Allow** and check **Always allow**.

After that, every button on the phone pad works in any app, including typing. The microphone and the power button from Amazon's phone app are not included.

If you skip debugging, you can turn on **Settings → Accessibility → TV Dash**. Back, Home, and play/pause work. The direction pad only works in apps that show which item is selected. Menu and typing into every app need the debugging step above.

**Opening apps while you're watching something else:** Android 10+ (Fire OS 8) blocks background apps from switching screens. While TV Dash is on screen this always works. To make it work at any time, allow "display over other apps" once from a computer:
`adb shell appops set com.tvdash.launcher SYSTEM_ALERT_WINDOW allow`
Fire OS 7 and older don't need this.

## Market ticker

The ticker is off until you add symbols. Those symbols are saved on the TV only. They are not built into the app.

On the TV, open **System → Dashboard settings → Markets**.

- **Stocks and ETFs**: symbols separated by commas, for example `SPY, QQQ`. A class share can be written `BRK.B`.
- **Crypto**: symbols separated by commas, for example `BTC, ETH, XRP, DOGE`.

Up to 16 of each. From the phone remote, use the same two fields under **Dashboard settings**. The TV updates as soon as you save, then refreshes about every 90 seconds while TV Dash is on screen (including the screensaver).

Stock and ETF prices come from Yahoo Finance. Crypto prices come from CoinGecko. Both are free and need no API key. Each symbol shows the price and whether it is up or down today. If the network fails, the last prices stay up. Clear both lists to hide the strip.

## Remote controls
| Button | What it does |
|---|---|
| Arrows / Select | Move around and open an app |
| **Menu (☰)** or hold Select on an app | Add to Favorites, Move left/right, Move to front, Remove, App info |
| Back | Jump back to the top |
| Fast-forward ⏩ | Show the next photo now |

## Install it on your Fire TV
> Works on Fire TV models running Fire OS (Android-based), which is nearly every Fire TV Stick, Cube and Fire TV-edition TV. Some of Amazon's newest devices run **Vega OS**, which can't install Android apps. If **Settings → My Fire TV → About** doesn't mention Fire OS, this app won't install.

1. On the Fire TV, open **Settings → My Fire TV → Developer options**. If you don't see Developer options, go to **My Fire TV → About** and click the device name 7 times.
2. Turn on **Install unknown apps** for the **Downloader** app. Install Downloader from the Appstore first if you need to.
3. Open Downloader and enter the APK link:
   `https://github.com/omeezynem/Fire-TV/releases/latest/download/tv-dash.apk`
   (this works only if the repository is public). For a private repository, download `tv-dash.apk` from the Actions run or the Releases page on your phone or computer, then send it to the TV with an app like "Send Files to TV", or with `adb install tv-dash.apk`.
4. Choose **Install**, then **Open**. TV Dash shows up in **Your Apps & Channels**. Move it to the front so it's one click from Home.

New builds install over old ones and keep your favorites and settings, because every build is signed with the same key (`app/tv-dash.keystore`).

### About the Home button
Fire OS always sends the Home button to Amazon's own home screen and doesn't let you pick a different one. To get there faster, put TV Dash first in Your Apps & Channels, or use a button-remapping app from the Appstore to open TV Dash on Home (or a double-press of Home). **Don't** disable Amazon's launcher with `adb pm disable`. On some models that leaves the TV with no home screen at all.

## Build it yourself
Every push runs GitHub Actions (`.github/workflows/build-apk.yml`). It builds `tv-dash.apk`, attaches it to the run, and publishes it as the `latest` release when the push is to the default branch.

To build locally you need Android Studio, or JDK 17 + the Android SDK + Gradle 8.9:
```bash
gradle assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
adb connect <fire-tv-ip>:5555 && adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Code map
| File | What it does |
|---|---|
| `MainActivity.java` | The dashboard: layout, app rows, clock, status, weather, market ticker, screensaver, remote handling |
| `AppEntry.java` | Finds the TV's launchable apps and their banners |
| `Weather.java` | Location lookup plus the Open-Meteo forecast |
| `Markets.java` | Stock, ETF, and crypto prices for the ticker (Yahoo Finance and CoinGecko) |
| `Slideshow.java` | Downloads, resizes and cross-fades the background photos |
| `TvServer.java` | Small web server and the PIN-protected API the phone uses |
| `AdbRemote.java` | Sends real remote keys through the TV's ADB debugging service |
| `RemoteAccessibility.java` | Limited Back, Home, and focus control when debugging is off |
| `RemoteControl.java` | Picks ADB or accessibility for each phone button |
| `RemoteService.java`, `BootReceiver.java` | Keep the phone remote running, including after the TV restarts |
| `Hub.java` | Passes phone commands to the dashboard while it's on screen |
| `Photos.java` | Stores photos sent from the phone (up to 60) |
| `QrCode.java` | Draws the pairing QR code (no libraries) |
| `assets/remote.html` | The phone remote page |
| `SettingsActivity.java` | Settings screen built for the remote |
| `Prefs.java` | Saved settings and favorites |
| `Ui.java` | Shared tile and text styling |
