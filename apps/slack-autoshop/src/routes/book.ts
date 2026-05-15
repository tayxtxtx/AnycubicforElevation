import { Router } from 'express';
import { z } from 'zod';
import { config } from '../config.js';
import { requireUser } from '../auth/session.js';
import {
  OverlapError,
  createBooking,
  listBookingsInRange,
} from '../db/bookings.js';
import { BookingRuleError, validateBooking } from '../rules/booking-rules.js';
import { notifyBookingCreated } from '../slack/notify.js';
import { logger } from '../logger.js';

export const bookRouter = Router();

bookRouter.get('/book', requireUser, (req, res) => {
  res.render('book', {
    user: req.user!,
    error: null,
    form: { start: '', durationMinutes: '60', note: '' },
    config: {
      tz: config.TZ,
      maxDurationHours: config.MAX_DURATION_HOURS,
      bookingWindowDays: config.BOOKING_WINDOW_DAYS,
    },
  });
});

const formSchema = z.object({
  start: z.string().min(1, 'Pick a start time.'),
  durationMinutes: z.coerce.number().int().positive().max(24 * 60),
  note: z.string().max(280).optional().default(''),
});

bookRouter.post('/book', requireUser, async (req, res) => {
  const parsed = formSchema.safeParse(req.body);
  if (!parsed.success) {
    return res.status(400).render('book', {
      user: req.user!,
      error: parsed.error.issues.map((i) => i.message).join(' '),
      form: req.body,
      config: { tz: config.TZ, maxDurationHours: config.MAX_DURATION_HOURS, bookingWindowDays: config.BOOKING_WINDOW_DAYS },
    });
  }
  const { start, durationMinutes, note } = parsed.data;
  // <input type="datetime-local"> sends local wall-clock without a tz, e.g.
  // "2026-05-15T14:30". Interpret it as wall-time in config.TZ.
  const startsAt = parseLocalToUnix(start, config.TZ);
  if (!Number.isFinite(startsAt)) {
    return res.status(400).render('book', {
      user: req.user!,
      error: 'Could not parse start time.',
      form: req.body,
      config: { tz: config.TZ, maxDurationHours: config.MAX_DURATION_HOURS, bookingWindowDays: config.BOOKING_WINDOW_DAYS },
    });
  }
  const endsAt = startsAt + durationMinutes * 60;
  const now = Math.floor(Date.now() / 1000);
  try {
    validateBooking({ startsAt, endsAt, now });
  } catch (err) {
    if (err instanceof BookingRuleError) {
      return res.status(400).render('book', {
        user: req.user!,
        error: err.message,
        form: req.body,
        config: { tz: config.TZ, maxDurationHours: config.MAX_DURATION_HOURS, bookingWindowDays: config.BOOKING_WINDOW_DAYS },
      });
    }
    throw err;
  }

  try {
    const booking = createBooking({
      user_id: req.user!.slackUserId,
      starts_at: startsAt,
      ends_at: endsAt,
      note: note.trim() || null,
    });
    void notifyBookingCreated({
      id: booking.id,
      user_id: req.user!.slackUserId,
      user_name: req.user!.name,
      starts_at: booking.starts_at,
      ends_at: booking.ends_at,
      note: booking.note,
    });
    res.redirect('/');
  } catch (err) {
    if (err instanceof OverlapError) {
      return res.status(409).render('book', {
        user: req.user!,
        error: err.message,
        form: req.body,
        config: { tz: config.TZ, maxDurationHours: config.MAX_DURATION_HOURS, bookingWindowDays: config.BOOKING_WINDOW_DAYS },
      });
    }
    logger.error({ err }, 'create booking failed');
    throw err;
  }
});

bookRouter.get('/api/conflict-check', requireUser, (req, res) => {
  const start = String(req.query.start ?? '');
  const duration = Number(req.query.durationMinutes ?? 0);
  const startsAt = parseLocalToUnix(start, config.TZ);
  if (!Number.isFinite(startsAt) || !duration) {
    res.json({ ok: false, error: 'bad input' });
    return;
  }
  const endsAt = startsAt + duration * 60;
  const hits = listBookingsInRange(startsAt, endsAt);
  res.json({ ok: hits.length === 0, conflicts: hits });
});

// Parse "YYYY-MM-DDTHH:MM" as wall-clock in tz and return unix seconds.
function parseLocalToUnix(local: string, tz: string): number {
  const m = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2}))?$/.exec(local);
  if (!m) return NaN;
  const [, Y, Mo, D, H, Mi, S] = m;
  // Probe: format the same wall-clock as if it were UTC, then ask the tz how
  // it perceives that instant. The delta is the offset.
  const asUTC = Date.UTC(+Y!, +Mo! - 1, +D!, +H!, +Mi!, S ? +S : 0);
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: tz,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hourCycle: 'h23',
  })
    .formatToParts(new Date(asUTC))
    .reduce<Record<string, string>>((acc, p) => {
      if (p.type !== 'literal') acc[p.type] = p.value;
      return acc;
    }, {});
  const tzAsUTC = Date.UTC(
    Number(parts.year),
    Number(parts.month) - 1,
    Number(parts.day),
    Number(parts.hour),
    Number(parts.minute),
    Number(parts.second),
  );
  const offsetMs = tzAsUTC - asUTC;
  return Math.floor((asUTC - offsetMs) / 1000);
}
