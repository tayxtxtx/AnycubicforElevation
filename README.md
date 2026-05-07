# Anycubic for Hubitat Elevation

Hubitat drivers that bring Anycubic 3D printers into your smart home so you can
monitor and control them from rules, dashboards, and routines.

Three drivers are included; pick whichever matches how your printer is
reachable:

| Driver | Best for | Network path |
| --- | --- | --- |
| `Anycubic (Moonraker) 3D Printer` | Klipper-based Anycubics (Kobra 3, modded Kobra 2 series) and any Klipper printer with Moonraker exposed on the LAN | Local HTTP |
| `Anycubic (OctoPrint) 3D Printer` | Older Anycubics paired with an OctoPrint host over USB (Mega, Chiron, Vyper, Photon, older Kobra, etc.) | Local HTTP |
| `Anycubic Cloud Printer` (paired with the *Anycubic Cloud Manager* app) | **Stock-firmware** Anycubics that only talk to Anycubic's cloud — Kobra S1, Kobra 3, Photon Mono M5s/M7, etc. | HTTPS to Anycubic Cloud |

**Local vs cloud — which do I want?** If you can reach Moonraker or OctoPrint
on your LAN, use one of those. They're faster, more private, and don't break
when Anycubic ships a backend change. Use the cloud driver only if your
printers are stock and you can't / don't want to mod them. The cloud driver is
HTTP-polling-only (15–60 s lag) because Anycubic's MQTT broker requires a
client TLS certificate that Hubitat's MQTT client cannot supply.

## Features

Both drivers expose, at a minimum:

- `printerStatus` — `ready`, `printing`, `paused`, `complete`, `cancelled`, `error`, `offline`
- `printProgress` (0–100), `currentFile`
- `printTimeElapsed`, `printTimeRemaining`
- `nozzleTemperature` / `nozzleTarget`, `bedTemperature` / `bedTarget`
- `temperature` (Hubitat `TemperatureMeasurement` capability — pick nozzle or bed)
- `switch` — on/off either when the printer is printing or when it is online (configurable)
- Commands: `pausePrint`, `resumePrint`, `cancelPrint`, `refresh`

The Moonraker driver additionally exposes `emergencyStop`, `firmwareRestart`,
`klippyState`, `filamentUsed`, and `totalDuration`. The OctoPrint driver
additionally exposes `connectPrinter` / `disconnectPrinter`.

The polling cadence is two-tier: a slower interval while idle and a faster one
while a print is running, both configurable.

## Installation

You can either install just the drivers and add devices manually, or also
install the companion app for one-click network discovery.

### Drivers (required)

1. In Hubitat, open **Developer tools → Drivers code → New driver**.
2. Paste the contents of `drivers/anycubic-moonraker-driver.groovy` and click
   **Save**.
3. Repeat for `drivers/anycubic-octoprint-driver.groovy` if you want OctoPrint
   support too.

### Manager app (optional, for auto-discovery)

1. Open **Developer tools → Apps code → New app**.
2. Paste the contents of `apps/anycubic-printer-manager.groovy` and click
   **Save**.
3. Open **Apps → Add user app** and pick *Anycubic Printer Manager*.
4. Click **Scan my network**, wait for the probe to finish, then click **Add as
   device** next to each printer it finds. The app auto-detects the hub's /24
   subnet and probes Moonraker on `7125` and OctoPrint on `80`/`5000` by
   default; you can override either before clicking **Start scan**.
5. Open each newly created device and add an API key if required (Moonraker
   when not using `trusted_clients`, OctoPrint always).

### Cloud setup (stock-firmware printers)

1. Install both files first:
   - `drivers/anycubic-cloud-printer.groovy` → **Drivers code → New driver**
   - `apps/anycubic-cloud-manager.groovy` → **Apps code → New app**
2. **Get your Anycubic access_token.** Install Anycubic Slicer Next and log in with your Anycubic account. Then locate the slicer's config file:
   - Windows: `%AppData%\AnycubicSlicerNext\AnycubicSlicerNext.conf`
   - macOS: `~/Library/Application Support/AnycubicSlicerNext/AnycubicSlicerNext.conf`
   - Linux: `~/.config/AnycubicSlicerNext/AnycubicSlicerNext.conf`

   Open it in a text editor and copy the `access_token` value (about 344 characters).
3. In Hubitat, **Apps → Add user app → Anycubic Cloud Manager**. Paste the
   access token, set polling intervals if you want to override defaults, and
   click **Done** (or **Test login now** to verify before saving).
4. Within a few seconds the app logs in, fetches your printer list, and creates
   one **Anycubic Cloud Printer** child device per printer. They'll be named
   after whatever you've called the printer in the Anycubic app.

Notes on the cloud path:
- The app re-logs in automatically when the session expires (Anycubic doesn't
  document an expiry — typically days).
- **Don't run Slicer Next at the same time** if you can help it — Anycubic's
  cloud sometimes kicks the older session when two clients with the same
  client identity connect.
- Status updates lag by your poll interval. Default is 60 s when idle, 15 s
  during active prints.
- Pause/resume/cancel commands go through Anycubic's HTTP API and take
  effect within a couple of seconds; the next poll picks up the new state.
- This integration is reverse-engineered against Anycubic's undocumented
  cloud API and can break without warning when they change it. The session
  token approach (vs. email/password) was forced on the community after
  Anycubic locked down the easier paths in late 2024.

### Adding a device manually (without the manager app)

1. Open **Devices → Add device → Virtual**.
2. Set **Type** to *Anycubic (Moonraker) 3D Printer* or *Anycubic (OctoPrint)
   3D Printer*.
3. Open the new device, fill in the preferences (see below), and click **Save
   Preferences**.

## Configuration — Moonraker driver

Use this if your Anycubic runs Klipper with Moonraker reachable on your LAN
(default port `7125`).

| Preference | Notes |
| --- | --- |
| Printer IP address | The LAN IP of the printer / Moonraker host |
| Moonraker port | Defaults to `7125` |
| Moonraker API key | Optional. Leave blank if Moonraker is configured to trust the Hubitat hub's IP via `[authorization] trusted_clients`. Otherwise generate a key in Moonraker and paste it here |
| Poll interval | Seconds between polls while idle |
| Poll interval while printing | Faster cadence while a print is active |
| Temperature reported as device temperature | `nozzle` or `bed` — drives the `temperature` attribute |
| Switch state reflects | `printing` (switch on while printing) or `online` (switch on whenever reachable) |

### Authorising Hubitat to talk to Moonraker

Either add the Hubitat hub's IP to Moonraker's `[authorization]` block:

```ini
[authorization]
trusted_clients:
    192.168.1.0/24
```

…or generate an API key from Mainsail/Fluidd (**Settings → Authorization →
API Keys**) and paste it into the **Moonraker API key** preference.

## Configuration — OctoPrint driver

Use this if your Anycubic is connected over USB to an OctoPrint host
(OctoPi, Docker, etc.).

| Preference | Notes |
| --- | --- |
| OctoPrint host | LAN IP or hostname (e.g. `octopi.local`) |
| OctoPrint port | Defaults to `80`; OctoPi often uses `5000` for direct access |
| Use HTTPS | Toggle if your OctoPrint front-end is HTTPS |
| OctoPrint API key | Required. Get it from **Settings → API** in OctoPrint |
| Poll interval(s) | As above |
| Temperature reported as device temperature | As above |
| Switch state reflects | As above |

## Example automations

- *"When my printer finishes, turn on the office lamp and announce it on the
  Echo."* — trigger on `printerStatus` becoming `complete`.
- *"If the nozzle is over 50 °C and nobody is home, send me a push."* — trigger
  on `nozzleTemperature` and presence.
- *"Pause the print when smoke is detected."* — your smoke alarm triggers
  `pausePrint()` on the printer device.
- *"Show progress on the dashboard."* — pin `printProgress`, `currentFile`,
  and `printTimeRemaining` as tile attributes.

## Compatibility notes

- **Kobra 3** ships with Klipper but Moonraker is not exposed on the network
  out of the box. You'll need to enable LAN access (root/SSH or one of the
  community mods) before this driver can reach it.
- **Kobra 2 / 2 Pro / 2 Plus / 2 Max** ship with Anycubic's own firmware. Use
  the OctoPrint driver if you have OctoPrint bridging USB, or the Moonraker
  driver if you've flashed Klipper.
- **Mega, Chiron, Vyper, i3 Mega, Photon Mono series** — pair with OctoPrint
  and use the OctoPrint driver.
- The drivers do not start prints (no file upload). They monitor and
  pause/resume/cancel; that's a deliberate scope choice — kicking off a print
  remotely on a printer that nobody is watching is a footgun.

## Troubleshooting

- `printerStatus` stuck at `offline`: enable **debug logging** on the device
  page; the `lastError` attribute will usually say `HTTP 401`, `HTTP 403`,
  `unauthorized`, or `no response`. Verify IP, port, and API key, and that the
  Hubitat hub can reach the printer (no VLAN isolation).
- Moonraker returns 503 while Klipper is restarting; the driver shows that as
  `printerStatus = error` with `klippyState = shutdown`. It will recover on
  the next successful poll.
- OctoPrint returns 409 from `/api/printer` when no printer is connected over
  USB. The driver treats this as "no temperature data" rather than a fatal
  error.

## License

MIT — see `LICENSE`.
