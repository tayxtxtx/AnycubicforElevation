# Make Nashville Autoshop Bay — Reservation App

A Slack-integrated reservation system for [Make Nashville](https://makenashville.org)'s
single autoshop bay (lift + tools). Web UI is the primary booking surface;
Slack handles auth, channel announcements, DM reminders, and a `/bay` slash
command for quick queries.

Inspired by [dovstrechiapp.ru](https://dovstrechiapp.ru) (a Telegram-native
Calendly) — same idea, Slack-flavored.

## How it works

1. **Sign in with Slack** — only members of the Make Nashville workspace can book.
2. **Pick a time** — start + duration, max 4 h per reservation, up to 14 days ahead.
3. **Get reminders** — Slack DMs you ~1 h before your slot; create/cancel events
   post to `#autoshop`. Cancel from web or `/bay cancel <id>`.

A read-only ICS feed is published at `/calendar.ics?token=…` so members can
subscribe in Google/Apple Calendar.

## Slack app — approval request (give this to the workspace admin)

The app needs to be created at <https://api.slack.com/apps> and installed to
the Make Nashville workspace. Here's exactly what scopes/permissions it asks for:

**App name:** Make Nashville Autoshop

**Bot Token Scopes** (the bot needs these to function):
- `chat:write` — post booking announcements to `#autoshop`
- `chat:write.public` — post to `#autoshop` without being explicitly invited
- `commands` — register the `/bay` slash command
- `im:write` — DM members reminders before their slot
- `users:read` — look up display names for command responses

**Sign-in-with-Slack (OIDC) Scopes** (used only to verify a logging-in user is
in the workspace; no message reading):
- `openid`, `profile`, `email`

**Slash Commands:**
- `/bay` → request URL `https://<host>/slack/commands`
- Usage hint: `[today | week | cancel <id> | help]`

**OAuth & Permissions → Redirect URLs:**
- `https://<host>/auth/slack/callback`

**Event Subscriptions:** OFF (the app does not read any messages).

**Interactivity:** OFF (all UI is on the web; the slash command uses
`response_url`).

Once approved, install to the Make Nashville workspace and copy these values
into the app's environment:
- `SLACK_CLIENT_ID`, `SLACK_CLIENT_SECRET`, `SLACK_SIGNING_SECRET` — from
  *Basic Information*.
- `SLACK_BOT_TOKEN` — from *OAuth & Permissions → Bot User OAuth Token* after install.
- `MAKE_NASHVILLE_TEAM_ID` — from any Slack URL (`T0XXXXXX`).
- `AUTOSHOP_CHANNEL_ID` — right-click `#autoshop` → Copy link; the trailing
  `C…` value is the ID.

## Local development

```bash
cd apps/slack-autoshop
npm install
cp .env.example .env   # fill in
npm run dev
```

Then expose a public URL for Slack to call back to:

```bash
cloudflared tunnel --url http://localhost:3000
```

Set the resulting `https://…trycloudflare.com` URL as the OAuth redirect URL
and slash-command request URL in the (dev) Slack app, and as `BASE_URL` in
`.env`.

For end-to-end testing without disturbing the real Make Nashville workspace,
register a *separate* dev Slack app in your own personal workspace with the
same config.

## Booking rules

Tuned by env vars (see `.env.example`):
- `MAX_DURATION_HOURS=4` — caps a single booking.
- `BOOKING_WINDOW_DAYS=14` — max days into the future.
- `REMINDER_LEAD_MINUTES=60` — how long before a slot the DM fires.

Overlap prevention runs in a SQLite `IMMEDIATE` transaction so two concurrent
`POST /book` calls serialize through the conflict check.

## Production deploy (Fly.io)

```bash
fly launch --no-deploy             # claim a name, edit fly.toml as needed
fly volumes create data --size 1   # SQLite lives at /data/autoshop.db
fly secrets set \
  SLACK_CLIENT_ID=... SLACK_CLIENT_SECRET=... SLACK_SIGNING_SECRET=... \
  SLACK_BOT_TOKEN=xoxb-... \
  MAKE_NASHVILLE_TEAM_ID=T... AUTOSHOP_CHANNEL_ID=C... \
  SESSION_SECRET="$(node -e 'console.log(require("crypto").randomBytes(32).toString("base64"))')" \
  BASE_URL=https://make-nashville-autoshop.fly.dev \
  ICS_TOKEN="$(node -e 'console.log(require("crypto").randomBytes(16).toString("hex"))')"
fly deploy
```

## License

MIT.
