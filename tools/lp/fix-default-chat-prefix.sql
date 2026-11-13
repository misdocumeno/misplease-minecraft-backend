-- Default name color in chat/tab: violet (brand), not aqua.
-- LuckPerms stores prefixes as permission nodes: prefix.<priority>.<value>
-- Run once against the misplease DB (or: /lp group default meta setprefix &5):
--   docker exec -i misplease-db psql -U misplease -d misplease < tools/lp/fix-default-chat-prefix.sql

UPDATE luckperms_group_permissions
SET permission = 'prefix.1.&5'
WHERE name = 'default'
  AND permission = 'prefix.1.&3';
