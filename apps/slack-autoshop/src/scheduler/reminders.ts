import cron from 'node-cron';
import { config } from '../config.js';
import { dueReminders, markReminderSent } from '../db/bookings.js';
import { bookingReminderText, dmUser } from '../slack/notify.js';
import { logger } from '../logger.js';

const WINDOW_MIN = 5;

async function tick(): Promise<void> {
  const now = Math.floor(Date.now() / 1000);
  const lead = config.REMINDER_LEAD_MINUTES * 60;
  const from = now + lead - WINDOW_MIN * 60;
  const to = now + lead + WINDOW_MIN * 60;
  const rows = dueReminders(from, to);
  for (const b of rows) {
    try {
      await dmUser(b.user_id, bookingReminderText(b));
      markReminderSent(b.id);
    } catch (err) {
      logger.warn({ err, bookingId: b.id }, 'reminder dispatch failed');
    }
  }
}

export function startReminderScheduler(): void {
  if (!config.SLACK_BOT_TOKEN) {
    logger.info('SLACK_BOT_TOKEN not set; skipping reminder scheduler');
    return;
  }
  cron.schedule('* * * * *', () => {
    void tick();
  });
  logger.info(
    { leadMinutes: config.REMINDER_LEAD_MINUTES },
    'reminder scheduler started',
  );
}
