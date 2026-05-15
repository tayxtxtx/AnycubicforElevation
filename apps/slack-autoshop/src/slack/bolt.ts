import bolt from '@slack/bolt';
import { config } from '../config.js';
import { registerCommands } from './commands.js';

const { App, ExpressReceiver } = bolt;
type ExpressReceiverInstance = InstanceType<typeof ExpressReceiver>;

// We only construct Bolt if we have a bot token; otherwise we expose a stub
// router that returns 503 on /slack/* so the rest of the app still boots
// (useful before the workspace admin has installed the app).
function buildReceiver(): ExpressReceiverInstance | null {
  if (!config.SLACK_BOT_TOKEN) return null;
  return new ExpressReceiver({
    signingSecret: config.SLACK_SIGNING_SECRET,
    endpoints: { commands: '/commands', events: '/events' },
    processBeforeResponse: true,
  });
}

const receiver = buildReceiver();

export const boltApp = receiver
  ? new App({ token: config.SLACK_BOT_TOKEN, receiver })
  : null;

if (boltApp) registerCommands(boltApp);

export const slackRouter = receiver?.router ?? null;
