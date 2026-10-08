-- SAMPLE DATA ONLY. Applied only when PAYEE_DIRECTORY_SEED_ENABLED=true
-- (local, dev and CI). Never enable in staging or production: real entries
-- come from db/import/import-payee-directory.sh.
-- Every IBAN below is synthetic (bank code 033) with a valid check digit.

INSERT INTO payee_directory_entry (scheme_name, identification, holder_name, account_type, account_status, updated_at)
VALUES
    ('IBAN', 'AE280330000000123456789', 'Al Tareq Trading LLC',  'BUSINESS', 'ACTIVE',   TIMESTAMPTZ '2026-01-01 00:00:00+00'),
    ('IBAN', 'AE770330000000987654321', 'Atlas Services LLC',    'BUSINESS', 'ACTIVE',   TIMESTAMPTZ '2026-01-01 00:00:00+00'),
    ('IBAN', 'AE120330000000111111111', 'Dormant Company LLC',   'BUSINESS', 'CLOSED',   TIMESTAMPTZ '2026-01-01 00:00:00+00'),
    ('IBAN', 'AE100330000000222222222', 'Sample Personal Holder', 'PERSONAL', 'ACTIVE',  TIMESTAMPTZ '2026-01-01 00:00:00+00'),
    ('IBAN', 'AE080330000000333333333', 'شركة الطارق للتجارة',    'BUSINESS', 'ACTIVE',   TIMESTAMPTZ '2026-01-01 00:00:00+00')
ON CONFLICT (scheme_name, identification) DO UPDATE
    SET holder_name    = EXCLUDED.holder_name,
        account_type   = EXCLUDED.account_type,
        account_status = EXCLUDED.account_status,
        updated_at     = EXCLUDED.updated_at
    WHERE payee_directory_entry.updated_at <= EXCLUDED.updated_at
      AND (payee_directory_entry.holder_name, payee_directory_entry.account_type, payee_directory_entry.account_status)
          IS DISTINCT FROM (EXCLUDED.holder_name, EXCLUDED.account_type, EXCLUDED.account_status);
