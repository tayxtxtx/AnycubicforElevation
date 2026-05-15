import { Router } from 'express';
import { randomBytes } from 'node:crypto';
import { config } from '../config.js';
import { getSession } from './session.js';
import { upsertUser } from '../db/bookings.js';
import { logger } from '../logger.js';

export const slackOidcRouter = Router();

const AUTHORIZE = 'https://slack.com/openid/connect/authorize';
const TOKEN = 'https://slack.com/api/openid.connect.token';
const USERINFO = 'https://slack.com/api/openid.connect.userInfo';

slackOidcRouter.get('/auth/slack/login', async (req, res) => {
  const session = await getSession(req, res);
  const state = randomBytes(16).toString('hex');
  session.oauthState = state;
  await session.save();

  const params = new URLSearchParams({
    response_type: 'code',
    scope: 'openid profile email',
    client_id: config.SLACK_CLIENT_ID,
    redirect_uri: `${config.BASE_URL}/auth/slack/callback`,
    state,
    team: config.MAKE_NASHVILLE_TEAM_ID,
  });
  res.redirect(`${AUTHORIZE}?${params.toString()}`);
});

slackOidcRouter.get('/auth/slack/callback', async (req, res) => {
  const session = await getSession(req, res);
  const { code, state, error } = req.query as Record<string, string | undefined>;

  if (error) {
    logger.warn({ error }, 'slack oauth returned error');
    return res.status(400).render('login', { error: `Slack returned: ${error}` });
  }
  if (!code || !state || state !== session.oauthState) {
    return res.status(400).render('login', { error: 'Login state mismatch. Try again.' });
  }
  session.oauthState = undefined;
  await session.save();

  // Exchange code for tokens
  const tokenBody = new URLSearchParams({
    code,
    client_id: config.SLACK_CLIENT_ID,
    client_secret: config.SLACK_CLIENT_SECRET,
    redirect_uri: `${config.BASE_URL}/auth/slack/callback`,
    grant_type: 'authorization_code',
  });
  const tokenRes = await fetch(TOKEN, {
    method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    body: tokenBody,
  });
  const tokenJson = (await tokenRes.json()) as {
    ok: boolean;
    error?: string;
    access_token?: string;
    id_token?: string;
  };
  if (!tokenJson.ok || !tokenJson.access_token) {
    logger.warn({ tokenJson }, 'token exchange failed');
    return res.status(400).render('login', { error: 'Could not exchange Slack code.' });
  }

  // Fetch userinfo
  const userRes = await fetch(USERINFO, {
    headers: { authorization: `Bearer ${tokenJson.access_token}` },
  });
  const userJson = (await userRes.json()) as {
    ok: boolean;
    error?: string;
    sub?: string;
    'https://slack.com/team_id'?: string;
    'https://slack.com/user_id'?: string;
    name?: string;
    email?: string;
  };
  if (!userJson.ok) {
    logger.warn({ userJson }, 'userinfo failed');
    return res.status(400).render('login', { error: 'Could not fetch Slack profile.' });
  }

  const teamId = userJson['https://slack.com/team_id'];
  const slackUserId = userJson['https://slack.com/user_id'];
  if (!teamId || !slackUserId) {
    return res.status(400).render('login', { error: 'Slack profile missing team/user.' });
  }
  if (teamId !== config.MAKE_NASHVILLE_TEAM_ID) {
    return res.status(403).render('login', {
      error: 'This app is for Make Nashville members. Wrong Slack workspace.',
    });
  }

  upsertUser({
    slack_user_id: slackUserId,
    slack_team_id: teamId,
    name: userJson.name ?? slackUserId,
    email: userJson.email ?? null,
  });

  session.slackUserId = slackUserId;
  session.name = userJson.name ?? slackUserId;
  await session.save();
  res.redirect('/');
});

slackOidcRouter.post('/auth/logout', async (req, res) => {
  const session = await getSession(req, res);
  session.destroy();
  res.redirect('/login');
});
