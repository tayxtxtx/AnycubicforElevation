import express from 'express';
import { pinoHttp } from 'pino-http';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { config } from './config.js';
import { logger } from './logger.js';
import './db/index.js';
import { attachSession } from './auth/session.js';
import { slackOidcRouter } from './auth/slack-oidc.js';
import { indexRouter } from './routes/index.js';
import { bookRouter } from './routes/book.js';
import { cancelRouter } from './routes/cancel.js';
import { icsRouter } from './routes/ics.js';
import { slackRouter } from './slack/bolt.js';
import { startReminderScheduler } from './scheduler/reminders.js';

const here = dirname(fileURLToPath(import.meta.url));
// Views and static assets live at the package root (not under src/dist) so a
// single path resolves the same in dev (src/server.ts) and prod (dist/server.js).
const pkgRoot = resolve(here, '..');
const viewsDir = resolve(pkgRoot, 'views');
const publicDir = resolve(pkgRoot, 'public');

const app = express();
app.set('view engine', 'ejs');
app.set('views', viewsDir);
app.disable('x-powered-by');

app.use(pinoHttp({ logger, autoLogging: { ignore: (req) => req.url === '/healthz' } }));
app.use('/public', express.static(publicDir, { maxAge: '1d' }));
app.use(express.urlencoded({ extended: false }));
app.use(attachSession);

app.use(indexRouter);
app.use(slackOidcRouter);
app.use(bookRouter);
app.use(cancelRouter);
app.use(icsRouter);

if (slackRouter) {
  app.use('/slack', slackRouter);
} else {
  app.use('/slack', (_req, res) => {
    res.status(503).send('Slack integration not yet configured.');
  });
  logger.warn('SLACK_BOT_TOKEN not set; /slack/* returns 503 until configured');
}

app.use((err: unknown, _req: express.Request, res: express.Response, _next: express.NextFunction) => {
  logger.error({ err }, 'unhandled error');
  if (res.headersSent) return;
  res.status(500).send('Something went wrong.');
});

const server = app.listen(config.PORT, () => {
  logger.info({ port: config.PORT, baseUrl: config.BASE_URL }, 'autoshop listening');
});

startReminderScheduler();

function shutdown(signal: string) {
  logger.info({ signal }, 'shutting down');
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(1), 10_000).unref();
}
process.on('SIGTERM', () => shutdown('SIGTERM'));
process.on('SIGINT', () => shutdown('SIGINT'));
