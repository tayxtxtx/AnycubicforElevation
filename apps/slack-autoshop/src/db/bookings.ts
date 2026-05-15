import { db } from './index.js';

export interface BookingRow {
  id: number;
  user_id: string;
  starts_at: number;
  ends_at: number;
  note: string | null;
  created_at: number;
  cancelled_at: number | null;
  reminder_sent_at: number | null;
}

export interface BookingWithUser extends BookingRow {
  user_name: string;
}

export interface UserRow {
  slack_user_id: string;
  slack_team_id: string;
  name: string;
  email: string | null;
  created_at: number;
}

const upsertUserStmt = db.prepare(`
  INSERT INTO users (slack_user_id, slack_team_id, name, email, created_at)
  VALUES (@slack_user_id, @slack_team_id, @name, @email, @created_at)
  ON CONFLICT(slack_user_id) DO UPDATE SET
    name = excluded.name,
    email = excluded.email
`);

export function upsertUser(u: Omit<UserRow, 'created_at'>) {
  upsertUserStmt.run({ ...u, created_at: Math.floor(Date.now() / 1000) });
}

const overlapStmt = db.prepare(`
  SELECT 1 FROM bookings
  WHERE cancelled_at IS NULL
    AND starts_at < @ends_at
    AND ends_at > @starts_at
  LIMIT 1
`);

const insertStmt = db.prepare(`
  INSERT INTO bookings (user_id, starts_at, ends_at, note, created_at)
  VALUES (@user_id, @starts_at, @ends_at, @note, @created_at)
`);

export class OverlapError extends Error {
  constructor() {
    super('A reservation already exists for that time range.');
    this.name = 'OverlapError';
  }
}

interface CreateInput {
  user_id: string;
  starts_at: number;
  ends_at: number;
  note: string | null;
}

const createBookingTxn = db.transaction((input: CreateInput): BookingRow => {
  const conflict = overlapStmt.get({ starts_at: input.starts_at, ends_at: input.ends_at });
  if (conflict) throw new OverlapError();
  const now = Math.floor(Date.now() / 1000);
  const result = insertStmt.run({ ...input, created_at: now });
  return {
    id: Number(result.lastInsertRowid),
    user_id: input.user_id,
    starts_at: input.starts_at,
    ends_at: input.ends_at,
    note: input.note,
    created_at: now,
    cancelled_at: null,
    reminder_sent_at: null,
  };
});

// IMMEDIATE acquires the reserved write lock at BEGIN, so two concurrent
// writers serialize through the overlap check instead of both passing it.
export function createBooking(input: CreateInput): BookingRow {
  return createBookingTxn.immediate(input);
}

const cancelStmt = db.prepare(`
  UPDATE bookings
  SET cancelled_at = @now
  WHERE id = @id AND user_id = @user_id AND cancelled_at IS NULL
`);

export function cancelBooking(id: number, user_id: string): boolean {
  const now = Math.floor(Date.now() / 1000);
  const info = cancelStmt.run({ id, user_id, now });
  return info.changes === 1;
}

const getByIdStmt = db.prepare(`SELECT * FROM bookings WHERE id = ?`);
export function getBookingById(id: number): BookingRow | undefined {
  return getByIdStmt.get(id) as BookingRow | undefined;
}

const listRangeStmt = db.prepare(`
  SELECT b.*, u.name AS user_name
  FROM bookings b
  JOIN users u ON u.slack_user_id = b.user_id
  WHERE b.cancelled_at IS NULL
    AND b.ends_at > @from
    AND b.starts_at < @to
  ORDER BY b.starts_at ASC
`);

export function listBookingsInRange(fromUnix: number, toUnix: number): BookingWithUser[] {
  return listRangeStmt.all({ from: fromUnix, to: toUnix }) as BookingWithUser[];
}

const dueReminderStmt = db.prepare(`
  SELECT b.*, u.name AS user_name
  FROM bookings b
  JOIN users u ON u.slack_user_id = b.user_id
  WHERE b.cancelled_at IS NULL
    AND b.reminder_sent_at IS NULL
    AND b.starts_at BETWEEN @from AND @to
`);

export function dueReminders(fromUnix: number, toUnix: number): BookingWithUser[] {
  return dueReminderStmt.all({ from: fromUnix, to: toUnix }) as BookingWithUser[];
}

const markReminderStmt = db.prepare(
  `UPDATE bookings SET reminder_sent_at = @now WHERE id = @id`,
);
export function markReminderSent(id: number) {
  markReminderStmt.run({ id, now: Math.floor(Date.now() / 1000) });
}
