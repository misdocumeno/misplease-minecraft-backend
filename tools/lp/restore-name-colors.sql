-- Restore chat/tab name colors after mistaken default violet (tools/lp/fix-default-chat-prefix.sql).
-- Backup (cutover-20261113): default=&3 (Miembro), misdocumeno=&5, MasterAngel personal &0 in LP-era intent.
-- Apply when no players are online (or use equivalent `/lp` on main + `/lp networksync` is automatic via Postgres).
--
--   docker exec -i misplease-db psql -U misplease -d misplease < tools/lp/restore-name-colors.sql
-- Then: `lp reload` on velocity + main (or restart containers).

-- Default: turquoise for everyone without a higher-priority user prefix.
DELETE FROM luckperms_group_permissions
WHERE name = 'default'
  AND permission IN ('prefix.1.&5', 'prefix.10.&5');

INSERT INTO luckperms_group_permissions (name, permission, value, server, world, expiry, contexts)
SELECT 'default', 'prefix.1.&3', true, '', '', 0, '{}'
WHERE NOT EXISTS (
  SELECT 1 FROM luckperms_group_permissions
  WHERE name = 'default' AND permission = 'prefix.1.&3'
);

-- Personal colors (priority 100 beats group prefix.1).
INSERT INTO luckperms_user_permissions (uuid, permission, value, server, world, expiry, contexts)
SELECT v.uuid, v.permission, true, '', '', 0, '{}'
FROM (VALUES
  ('f62a41ae-4198-391b-ad68-6dbbc530da23', 'prefix.100.&5'),
  ('11852b1a-5cc4-391a-834c-4109345f9c', 'prefix.100.&0')
) AS v(uuid, permission)
WHERE NOT EXISTS (
  SELECT 1 FROM luckperms_user_permissions u
  WHERE u.uuid = v.uuid AND u.permission = v.permission
);