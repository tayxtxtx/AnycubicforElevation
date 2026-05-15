import type bolt from '@slack/bolt';
type App = InstanceType<typeof bolt.App>;
import { config } from '../config.js';
import {
  cancelBooking,
  getBookingById,
  listBookingsInRange,
} from '../db/bookings.js';
import { notifyBookingCancelled } from './notify.js';

function startOfDay(unix: number, tz: string): number {
  // Subtract the wall-clock seconds-since-midnight (as seen in `tz`) from
  // `unix` to land on midnight in `tz`. Naturally handles DST because we ask
  // the tz how it currently sees the clock.
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: tz,
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hourCycle: 'h23',
  })
    .formatToParts(new Date(unix * 1000))
    .reduce<Record<string, string>>((acc, p) => {
      if (p.type !== 'literal') acc[p.type] = p.value;
      return acc;
    }, {});
  const h = Number(parts.hour);
  const m = Number(parts.minute);
  const s = Number(parts.second);
  return unix - (h * 3600 + m * 60 + s);
}

function fmtTime(unix: number): string {
  return new Intl.DateTimeFormat('en-US', {
    timeZone: config.TZ,
    hour: 'numeric',
    minute: '2-digit',
  }).format(new Date(unix * 1000));
}

export function registerCommands(app: App): void {
  app.command('/bay', async ({ command, ack, respond }) => {
    await ack();
    const args = command.text.trim().split(/\s+/).filter(Boolean);
    const sub = args[0]?.toLowerCase() ?? 'today';

    if (sub === 'help') {
      await respond({
        response_type: 'ephemeral',
        text:
          '*/bay* commands:\n' +
          '• `/bay` or `/bay today` — today\'s bookings\n' +
          '• `/bay week` — next 7 days\n' +
          '• `/bay cancel <id>` — cancel your own booking\n' +
          `Book the bay: ${config.BASE_URL}`,
      });
      return;
    }

    if (sub === 'cancel') {
      const id = Number(args[1]);
      if (!Number.isInteger(id)) {
        await respond({ response_type: 'ephemeral', text: 'Usage: `/bay cancel <id>`' });
        return;
      }
      const booking = getBookingById(id);
      if (!booking || booking.cancelled_at) {
        await respond({ response_type: 'ephemeral', text: `No active booking #${id}.` });
        return;
      }
      if (booking.user_id !== command.user_id) {
        await respond({
          response_type: 'ephemeral',
          text: `Booking #${id} isn\'t yours. Ask <@${booking.user_id}> to cancel.`,
        });
        return;
      }
      const ok = cancelBooking(id, command.user_id);
      if (!ok) {
        await respond({ response_type: 'ephemeral', text: `Couldn\'t cancel #${id}.` });
        return;
      }
      await notifyBookingCancelled(booking);
      await respond({ response_type: 'ephemeral', text: `Cancelled #${id}.` });
      return;
    }

    const now = Math.floor(Date.now() / 1000);
    const dayStart = startOfDay(now, config.TZ);
    const horizon = sub === 'week' ? dayStart + 7 * 86400 : dayStart + 86400;
    const bookings = listBookingsInRange(now, horizon);

    if (bookings.length === 0) {
      await respond({
        response_type: 'ephemeral',
        text: sub === 'week' ? 'No bookings in the next 7 days.' : 'No bookings today.',
      });
      return;
    }

    const lines = bookings.map(
      (b) =>
        `• *${fmtTime(b.starts_at)}–${fmtTime(b.ends_at)}* — <@${b.user_id}>` +
        (b.note ? ` _${b.note}_` : '') +
        ` (id #${b.id})`,
    );
    await respond({
      response_type: 'ephemeral',
      text:
        (sub === 'week' ? '*Bay schedule — next 7 days*\n' : '*Bay schedule — today*\n') +
        lines.join('\n'),
    });
  });
}
