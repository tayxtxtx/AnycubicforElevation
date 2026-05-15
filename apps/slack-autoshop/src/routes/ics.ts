import { Router } from 'express';
import ical from 'ical-generator';
import { config } from '../config.js';
import { listBookingsInRange } from '../db/bookings.js';

export const icsRouter = Router();

let cache: { body: string; expiresAt: number } | null = null;
const CACHE_TTL_MS = 60_000;

icsRouter.get('/calendar.ics', (req, res) => {
  if (req.query.token !== config.ICS_TOKEN) {
    return res.status(401).send('missing or wrong token');
  }
  const now = Date.now();
  if (!cache || now >= cache.expiresAt) {
    const fromUnix = Math.floor(now / 1000);
    const toUnix = fromUnix + 90 * 86400;
    const bookings = listBookingsInRange(fromUnix, toUnix);
    const cal = ical({
      name: 'Make Nashville Autoshop Bay',
      timezone: config.TZ,
    });
    for (const b of bookings) {
      cal.createEvent({
        id: `booking-${b.id}@autoshop.makenashville`,
        start: new Date(b.starts_at * 1000),
        end: new Date(b.ends_at * 1000),
        summary: `Autoshop: ${b.user_name}`,
        description: b.note ?? undefined,
      });
    }
    cache = { body: cal.toString(), expiresAt: now + CACHE_TTL_MS };
  }
  res.type('text/calendar').send(cache.body);
});
