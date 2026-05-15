import type { Request, Response, NextFunction, RequestHandler } from 'express';
import { getIronSession, type SessionOptions } from 'iron-session';
import { config } from '../config.js';

export interface SessionData {
  slackUserId?: string;
  name?: string;
  // CSRF state token for the in-flight OIDC handshake.
  oauthState?: string;
}

export const sessionOptions: SessionOptions = {
  password: config.SESSION_SECRET,
  cookieName: 'autoshop_session',
  cookieOptions: {
    httpOnly: true,
    secure: config.BASE_URL.startsWith('https://'),
    sameSite: 'lax',
    path: '/',
  },
};

export async function getSession(req: Request, res: Response) {
  return getIronSession<SessionData>(req, res, sessionOptions);
}

declare module 'express-serve-static-core' {
  interface Request {
    user?: { slackUserId: string; name: string };
  }
}

export const attachSession: RequestHandler = async (req, res, next) => {
  const session = await getSession(req, res);
  if (session.slackUserId && session.name) {
    req.user = { slackUserId: session.slackUserId, name: session.name };
  }
  next();
};

export function requireUser(req: Request, res: Response, next: NextFunction) {
  if (!req.user) {
    res.redirect('/login');
    return;
  }
  next();
}
