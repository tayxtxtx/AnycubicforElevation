import Database from 'better-sqlite3';
import { mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { config } from '../config.js';

const SCHEMA = `
CREATE TABLE IF NOT EXISTS users (
  slack_user_id TEXT PRIMARY KEY,
  slack_team_id TEXT NOT NULL,
  name TEXT NOT NULL,
  email TEXT,
  created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS bookings (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id TEXT NOT NULL REFERENCES users(slack_user_id),
  starts_at INTEGER NOT NULL,
  ends_at INTEGER NOT NULL,
  note TEXT,
  created_at INTEGER NOT NULL,
  cancelled_at INTEGER,
  reminder_sent_at INTEGER,
  CHECK (ends_at > starts_at)
);

CREATE INDEX IF NOT EXISTS idx_bookings_range
  ON bookings(starts_at, ends_at) WHERE cancelled_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_bookings_reminder
  ON bookings(starts_at) WHERE cancelled_at IS NULL AND reminder_sent_at IS NULL;
`;

mkdirSync(dirname(resolve(config.DATABASE_PATH)), { recursive: true });

export const db = new Database(config.DATABASE_PATH);
db.pragma('journal_mode = WAL');
db.pragma('foreign_keys = ON');
db.pragma('busy_timeout = 5000');
db.exec(SCHEMA);
