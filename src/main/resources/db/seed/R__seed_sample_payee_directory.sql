-- SAMPLE DATA ONLY. Applied only when PAYEE_DIRECTORY_SEED_ENABLED=true
-- (local, dev and CI). Never enable in staging or production: real entries
-- come from db/import/import-payee-directory.sh.
--
-- Insert-only, and in an id space the import refuses: scheme_name SAMPLE and
-- identifications with the SAMPLE- prefix. A re-applied seed can therefore
-- never revert or shadow an imported account. Call the API with
-- {"SchemeName": "SAMPLE", "Identification": "SAMPLE-AE28...", ...}.
-- The digits after the prefix are synthetic IBANs (bank code 033).

INSERT INTO payee_directory_entry (scheme_name, identification, holder_name, account_type, account_status, updated_at)
VALUES
    ('SAMPLE', 'SAMPLE-AE280330000000123456789', 'Al Tareq Trading LLC',   'BUSINESS', 'ACTIVE', TIMESTAMPTZ '2026-01-01 00:00:00+00'),
    ('SAMPLE', 'SAMPLE-AE770330000000987654321', 'Atlas Services LLC',     'BUSINESS', 'ACTIVE', TIMESTAMPTZ '2026-01-01 00:00:00+00'),
    ('SAMPLE', 'SAMPLE-AE120330000000111111111', 'Dormant Company LLC',    'BUSINESS', 'CLOSED', TIMESTAMPTZ '2026-01-01 00:00:00+00'),
    ('SAMPLE', 'SAMPLE-AE100330000000222222222', 'Sample Personal Holder', 'PERSONAL', 'ACTIVE', TIMESTAMPTZ '2026-01-01 00:00:00+00'),
    ('SAMPLE', 'SAMPLE-AE080330000000333333333', 'شركة الطارق للتجارة',     'BUSINESS', 'ACTIVE', TIMESTAMPTZ '2026-01-01 00:00:00+00')
ON CONFLICT (scheme_name, identification) DO NOTHING;
