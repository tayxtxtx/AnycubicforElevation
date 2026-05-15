// Standalone smoke test. Run with:
//   tsx src/__smoke__.ts
// Validates: env loads with test values, schema bootstraps, overlap detection
// works, booking-rules enforce limits. Does not touch Slack.

import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const tmp = mkdtempSync(join(tmpdir(), 'autoshop-smoke-'));
process.env.DATABASE_PATH = join(tmp, 'test.db');
process.env.SLACK_CLIENT_ID = 'test';
process.env.SLACK_CLIENT_SECRET = 'test';
process.env.SLACK_SIGNING_SECRET = 'test';
process.env.MAKE_NASHVILLE_TEAM_ID = 'T0TESTTEAM';
process.env.SESSION_SECRET = 'x'.repeat(40);
process.env.BASE_URL = 'http://localhost:3000';
process.env.ICS_TOKEN = 'test-token';

const { upsertUser, createBooking, OverlapError, listBookingsInRange } =
  await import('./db/bookings.js');
const { validateBooking, BookingRuleError } =
  await import('./rules/booking-rules.js');

function assert(cond: unknown, msg: string): void {
  if (!cond) throw new Error('ASSERTION FAILED: ' + msg);
  console.log('  ok:', msg);
}

console.log('1. User upsert');
upsertUser({
  slack_user_id: 'U_ALICE',
  slack_team_id: 'T0TESTTEAM',
  name: 'Alice',
  email: 'alice@example.com',
});
upsertUser({
  slack_user_id: 'U_BOB',
  slack_team_id: 'T0TESTTEAM',
  name: 'Bob',
  email: null,
});
console.log('  ok: two users upserted');

const aBase = Math.floor(Date.now() / 1000);
// round to 15m boundary, +1 hour
const start = aBase - (aBase % 900) + 3600;

console.log('\n2. Booking rules');
try {
  validateBooking({ startsAt: start, endsAt: start + 5 * 3600, now: aBase });
  throw new Error('should have rejected 5h');
} catch (e) {
  assert(e instanceof BookingRuleError, 'rejects max-duration breach');
}
try {
  validateBooking({ startsAt: start + 30 * 86400, endsAt: start + 30 * 86400 + 3600, now: aBase });
  throw new Error('should have rejected 30 days out');
} catch (e) {
  assert(e instanceof BookingRuleError, 'rejects beyond booking window');
}
try {
  validateBooking({ startsAt: start + 60, endsAt: start + 3660, now: aBase });
  throw new Error('should have rejected non-15m boundary');
} catch (e) {
  assert(e instanceof BookingRuleError, 'rejects non-quarter-hour boundary');
}
validateBooking({ startsAt: start, endsAt: start + 3600, now: aBase });
assert(true, 'accepts 1h booking on quarter-hour, within window');

console.log('\n3. Overlap detection');
const b1 = createBooking({
  user_id: 'U_ALICE',
  starts_at: start,
  ends_at: start + 3600,
  note: 'first',
});
assert(b1.id > 0, 'first booking created');

try {
  createBooking({
    user_id: 'U_BOB',
    starts_at: start + 1800,
    ends_at: start + 1800 + 3600,
    note: 'overlap',
  });
  throw new Error('should have rejected overlap');
} catch (e) {
  assert(e instanceof OverlapError, 'rejects overlapping booking');
}

// Adjacent booking (starts exactly when previous ends) is fine
const b2 = createBooking({
  user_id: 'U_BOB',
  starts_at: start + 3600,
  ends_at: start + 7200,
  note: 'adjacent',
});
assert(b2.id > b1.id, 'adjacent booking allowed');

console.log('\n4. Listing');
const list = listBookingsInRange(start - 1, start + 10 * 3600);
assert(list.length === 2, 'list returns both bookings');
assert(list[0]!.user_name === 'Alice', 'first row has user name joined');

console.log('\nALL SMOKE TESTS PASSED');
