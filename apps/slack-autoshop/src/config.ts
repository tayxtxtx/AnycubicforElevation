import 'dotenv/config';
import { z } from 'zod';

const schema = z.object({
  SLACK_CLIENT_ID: z.string().min(1),
  SLACK_CLIENT_SECRET: z.string().min(1),
  SLACK_SIGNING_SECRET: z.string().min(1),
  SLACK_BOT_TOKEN: z.string().min(1).optional(),
  MAKE_NASHVILLE_TEAM_ID: z.string().regex(/^T[A-Z0-9]+$/, 'expected Slack team id like T0XXXXXX'),
  AUTOSHOP_CHANNEL_ID: z.string().regex(/^[CG][A-Z0-9]+$/, 'expected Slack channel id like C0XXXXXX').optional(),
  SESSION_SECRET: z.string().min(32, 'SESSION_SECRET must be at least 32 chars'),
  BASE_URL: z.string().url(),
  DATABASE_PATH: z.string().min(1).default('./data/autoshop.db'),
  ICS_TOKEN: z.string().min(8),
  MAX_DURATION_HOURS: z.coerce.number().int().positive().default(4),
  BOOKING_WINDOW_DAYS: z.coerce.number().int().positive().default(14),
  REMINDER_LEAD_MINUTES: z.coerce.number().int().positive().default(60),
  PORT: z.coerce.number().int().positive().default(3000),
  TZ: z.string().default('America/Chicago'),
  LOG_LEVEL: z.enum(['fatal', 'error', 'warn', 'info', 'debug', 'trace']).default('info'),
});

const parsed = schema.safeParse(process.env);
if (!parsed.success) {
  console.error('Invalid environment configuration:');
  for (const issue of parsed.error.issues) {
    console.error(`  ${issue.path.join('.')}: ${issue.message}`);
  }
  process.exit(1);
}

export const config = parsed.data;
export type Config = typeof config;
