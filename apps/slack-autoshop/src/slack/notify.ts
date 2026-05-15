import { WebClient } from '@slack/web-api';
import { config } from '../config.js';
import { logger } from '../logger.js';

let _client: WebClient | null = null;
function client(): WebClient | null {
  if (!config.SLACK_BOT_TOKEN) return null;
  if (!_client) _client = new WebClient(config.SLACK_BOT_TOKEN);
  return _client;
}

function fmtRange(startsAt: number, endsAt: number): string {
  const fmt = new Intl.DateTimeFormat('en-US', {
    timeZone: config.TZ,
    weekday: 'short',
    month: 'short',
    day: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
  });
  const t = new Intl.DateTimeFormat('en-US', {
    timeZone: config.TZ,
    hour: 'numeric',
    minute: '2-digit',
  });
  return `${fmt.format(new Date(startsAt * 1000))} – ${t.format(new Date(endsAt * 1000))}`;
}

export async function notifyBookingCreated(b: {
  id: number;
  user_id: string;
  user_name: string;
  starts_at: number;
  ends_at: number;
  note: string | null;
}): Promise<void> {
  const c = client();
  if (!c || !config.AUTOSHOP_CHANNEL_ID) return;
  const text =
    `:car: <@${b.user_id}> booked the autoshop bay\n` +
    `*When:* ${fmtRange(b.starts_at, b.ends_at)}` +
    (b.note ? `\n*Note:* ${b.note}` : '') +
    `\n_id #${b.id}_`;
  try {
    await c.chat.postMessage({ channel: config.AUTOSHOP_CHANNEL_ID, text });
  } catch (err) {
    logger.warn({ err }, 'notifyBookingCreated failed');
  }
}

export async function notifyBookingCancelled(b: {
  id: number;
  user_id: string;
  starts_at: number;
  ends_at: number;
}): Promise<void> {
  const c = client();
  if (!c || !config.AUTOSHOP_CHANNEL_ID) return;
  const text =
    `:x: <@${b.user_id}> cancelled their bay booking\n` +
    `*Was:* ${fmtRange(b.starts_at, b.ends_at)}\n_id #${b.id}_`;
  try {
    await c.chat.postMessage({ channel: config.AUTOSHOP_CHANNEL_ID, text });
  } catch (err) {
    logger.warn({ err }, 'notifyBookingCancelled failed');
  }
}

export async function dmUser(slackUserId: string, text: string): Promise<void> {
  const c = client();
  if (!c) return;
  try {
    // chat.postMessage accepts a user ID directly with im:write scope; Slack
    // resolves the DM channel automatically.
    await c.chat.postMessage({ channel: slackUserId, text });
  } catch (err) {
    logger.warn({ err, slackUserId }, 'dmUser failed');
  }
}

export function bookingReminderText(b: {
  starts_at: number;
  ends_at: number;
  note: string | null;
}): string {
  return (
    `:car: Reminder: your autoshop bay reservation starts soon.\n` +
    `*When:* ${fmtRange(b.starts_at, b.ends_at)}` +
    (b.note ? `\n*Note:* ${b.note}` : '')
  );
}
