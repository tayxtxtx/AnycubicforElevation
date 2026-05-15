import { Router } from 'express';
import { requireUser } from '../auth/session.js';
import { cancelBooking, getBookingById } from '../db/bookings.js';
import { notifyBookingCancelled } from '../slack/notify.js';

export const cancelRouter = Router();

cancelRouter.post('/bookings/:id/cancel', requireUser, async (req, res) => {
  const id = Number(req.params.id);
  if (!Number.isInteger(id)) {
    return res.status(400).send('bad id');
  }
  const booking = getBookingById(id);
  if (!booking || booking.cancelled_at) {
    return res.redirect('/');
  }
  if (booking.user_id !== req.user!.slackUserId) {
    return res.status(403).send('not your booking');
  }
  const ok = cancelBooking(id, req.user!.slackUserId);
  if (ok) {
    void notifyBookingCancelled(booking);
  }
  res.redirect('/');
});
