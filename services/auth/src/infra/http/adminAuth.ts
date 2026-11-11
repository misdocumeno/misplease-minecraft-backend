import type { Request, Response, NextFunction } from 'express'

export function requireAdminToken(req: Request, res: Response, next: NextFunction) {
    const expected = process.env.AUTH_ADMIN_TOKEN
    if (!expected || expected.length < 16) {
        return res.status(503).json({ ok: false, code: 'AdminDisabled' })
    }
    const header = req.header('authorization') ?? ''
    const match = /^Bearer\s+(.+)$/i.exec(header)
    if (!match || match[1] !== expected) {
        return res.status(401).json({ ok: false, code: 'Unauthorized' })
    }
    return next()
}
