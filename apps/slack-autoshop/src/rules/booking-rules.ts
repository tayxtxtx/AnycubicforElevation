import { config } from '../config.js';

export interface BookingRequest {
  startsAt: number;
  endsAt: number;
  now: number;
}

export class BookingRuleError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'BookingRuleError';
  }
}

const QUARTER_HOUR = 15 * 60;

export function validateBooking({ startsAt, endsAt, now }: BookingRequest): void {
  if (endsAt <= startsAt) {
    throw new BookingRuleError('End time must be after start time.');
  }
  if (startsAt < now - 60) {
    throw new BookingRuleError('Start time cannot be in the past.');
  }
  const windowEnd = now + config.BOOKING_WINDOW_DAYS * 24 * 60 * 60;
  if (startsAt > windowEnd) {
    throw new BookingRuleError(
      `Bookings can be made up to ${config.BOOKING_WINDOW_DAYS} days ahead.`,
    );
  }
  const maxDuration = config.MAX_DURATION_HOURS * 60 * 60;
  if (endsAt - startsAt > maxDuration) {
    throw new BookingRuleError(
      `Reservations are limited to ${config.MAX_DURATION_HOURS} hours.`,
    );
  }
  if (startsAt % QUARTER_HOUR !== 0 || endsAt % QUARTER_HOUR !== 0) {
    throw new BookingRuleError('Times must be on 15-minute boundaries.');
  }
}
