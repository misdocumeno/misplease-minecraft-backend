import { randomUUID } from 'node:crypto'
import type { NextFunction, Request, Response } from 'express'

/**
 * Auth is the one service where "who did what, and why" must be reconstructable
 * after the fact: a dropped session looks exactly like a wrong password to the
 * player. Every line is one JSON object on stdout (`docker logs` and VictoriaLogs
 * read it as-is), and every mutation carries the same `requestId` the caller used.
 *
 * Never log credentials or request bodies: `username`, `source` and `requestId`
 * are picked explicitly.
 */
export type LogLevel = 'info' | 'warn' | 'error'

export interface LogFields {
    [key: string]: string | number | boolean | null | undefined
}

export function logEvent(level: LogLevel, event: string, fields: LogFields = {}): void {
    const line = JSON.stringify({
        ts: new Date().toISOString(),
        level,
        svc: 'auth',
        event,
        // `msg` is the field VictoriaLogs promotes to the searchable message
        // (alloy pushes with ?_msg_field=msg). Without it every line lands as
        // "missing _msg field" and full-text search skips it.
        msg: event,
        ...fields,
    })
    if (level === 'error') console.error(line)
    else if (level === 'warn') console.warn(line)
    else console.log(line)
}

export function newRequestId(): string {
    return randomUUID()
}

/** Caller-supplied correlation id (the proxy logs the same value). */
export function requestIdOf(res: Response): string {
    const id = res.locals.requestId
    return typeof id === 'string' && id.length > 0 ? id : 'unknown'
}

function inboundRequestId(req: Request): string {
    const raw = req.header('x-request-id')
    return raw && raw.length <= 128 ? raw : newRequestId()
}

/** Attach a request id, echo it back, and log one line when the response ends. */
export function requestLogger(req: Request, res: Response, next: NextFunction): void {
    const requestId = inboundRequestId(req)
    res.locals.requestId = requestId
    res.setHeader('X-Request-Id', requestId)

    const startedAt = Date.now()
    const username = bodyString(req, 'username')
    const source = bodyString(req, 'source')
    res.on('finish', () => {
        logEvent(res.statusCode >= 500 ? 'error' : 'info', 'http.request', {
            requestId,
            method: req.method,
            path: req.path,
            status: res.statusCode,
            ms: Date.now() - startedAt,
            username,
            source,
            remote: req.ip,
        })
    })
    next()
}

function bodyString(req: Request, key: string): string | undefined {
    const body: unknown = req.body
    if (!body || typeof body !== 'object') return undefined
    const value = (body as Record<string, unknown>)[key]
    return typeof value === 'string' ? value : undefined
}
