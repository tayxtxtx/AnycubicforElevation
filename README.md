# Anycubic for Hubitat Elevation

Hubitat drivers that bring Anycubic 3D printers into your smart home so you can
monitor and control them from rules, dashboards, and routines.

Two drivers are included; pick whichever matches how your printer is reachable
on the network:

| Driver | Best for | Local-only? |
| --- | --- | --- |
| `Anycubic (Moonraker) 3D Printer` | Klipper-based Anycubics (Kobra 3, modded Kobra 2 series) and any other Klipper printer with Moonraker exposed on the LAN | Yes |
| `Anycubic (OctoPrint) 3D Printer` | Older Anycubics paired with an OctoPrint host over USB (Mega, Chiron, Vyper, Photon, older Kobra, etc.) | Yes |

The Anycubic cloud / app MQTT path is intentionally **not** used. It depends on
reverse-engineered authentication against Anycubic's servers and breaks
whenever they change it. Both drivers here talk to your printer (or its
companion host) directly on your LAN.

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

1. In Hubitat, open **Developer tools → Drivers code → New driver**.
2. Paste the contents of `drivers/anycubic-moonraker-driver.groovy` (and/or
   `drivers/anycubic-octoprint-driver.groovy`) and click **Save**.
3. Open **Devices → Add device → Virtual**.
4. Set **Type** to *Anycubic (Moonraker) 3D Printer* or *Anycubic (OctoPrint)
   3D Printer*.
5. Open the new device, fill in the preferences (see below), and click **Save
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
