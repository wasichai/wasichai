-- dev seed. opt-in: wasichai.seed.dev=true. password "admin".
-- the bcrypt hash is written out instead of calling pgcrypto's crypt(): with several schemas in one
-- database the extension lives in whichever schema created it first, and crypt() would not resolve.
-- the hash uses the $2a$ prefix, since pgcrypto's own crypt() rejects $2y$ (Spring's
-- BCryptPasswordEncoder emits $2a$ by default; this hash comes straight from that encoder).
INSERT INTO ${metadataSchema}.organizations (id, name, slug)
VALUES ('00000000-0000-0000-0000-000000000001', 'Demo', 'demo')
ON CONFLICT (slug) DO NOTHING;

INSERT INTO ${metadataSchema}.roles (id, organization_id, name, label)
VALUES ('00000000-0000-0000-0000-000000000010',
        '00000000-0000-0000-0000-000000000001', 'ADMIN', 'Administrator')
ON CONFLICT (organization_id, name) DO NOTHING;

INSERT INTO ${metadataSchema}.users (id, organization_id, email, password_hash, display_name)
VALUES ('00000000-0000-0000-0000-000000000100',
        '00000000-0000-0000-0000-000000000001',
        'admin@wasichai.local',
        '$2a$10$vZ1/D.ORhnHCBpfdi2qcneeT28E8V42tnXIQRs74ubzXzE4sPWMPa',
        'Platform Administrator')
ON CONFLICT (organization_id, email) DO NOTHING;

INSERT INTO ${metadataSchema}.user_roles (user_id, role_id)
VALUES ('00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000010')
ON CONFLICT DO NOTHING;

-- object_id NULL = applies to every object in the org
INSERT INTO ${metadataSchema}.permissions (role_id, object_id, action)
SELECT '00000000-0000-0000-0000-000000000010', NULL, action
FROM unnest(ARRAY['READ', 'CREATE', 'UPDATE', 'DELETE', 'MANAGE_METADATA', 'MANAGE_ORGANIZATION']) AS action
ON CONFLICT DO NOTHING;
