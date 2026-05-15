import { Router } from 'express';
import { config } from '../config.js';
import { listBookingsInRange } from '../db/bookings.js';
import { requireUser } from '../auth/session.js';

export const indexRouter = Router();

indexRouter.get('/login', (req, res) => {
  if (req.user) return res.redirect('/');
  res.render('login', { error: null });
});

indexRouter.get('/', requireUser, (req, res) => {
  const now = Math.floor(Date.now() / 1000);
  const horizon = now + 14 * 86400;
  const bookings = listBookingsInRange(now, horizon);
  res.render('index', {
    user: req.user!,
    bookings,
    config: {
      tz: config.TZ,
      maxDurationHours: config.MAX_DURATION_HOURS,
      bookingWindowDays: config.BOOKING_WINDOW_DAYS,
    },
  });
});

indexRouter.get('/healthz', (_req, res) => {
  res.json({ ok: true });
});
