-- Parking for outbox rows, ADR-021 decision 4 (additive; V3 unchanged).
--
--   * The relay parks a row by itself only on a payload error (RecordTooLarge,
--     Serialization, InvalidTopic): parked_by = 'relay'.
--   * Every other error stops the batch without marking a row; such a row is
--     never parked automatically. Only an operator may park it, with a reason,
--     through park_outbox_event() below (runbook, db/ops/park-outbox-event.sh).
--
-- A parked row is never relayed again and is not purged (purge removes
-- published rows only), so it stays available for investigation.

ALTER TABLE outbox_event
    ADD COLUMN parked_at     TIMESTAMPTZ,
    ADD COLUMN parked_reason VARCHAR(512),
    ADD COLUMN parked_by     VARCHAR(128),
    ADD CONSTRAINT ck_outbox_parked_complete CHECK (
        (parked_at IS NULL AND parked_reason IS NULL AND parked_by IS NULL)
        OR (parked_at IS NOT NULL AND length(btrim(parked_reason)) > 0 AND parked_by IS NOT NULL)),
    ADD CONSTRAINT ck_outbox_parked_or_published CHECK (parked_at IS NULL OR published_at IS NULL);

-- What the relay reads: neither published nor parked, in insertion order. Also
-- serves the oldest-pending-age gauge.
CREATE INDEX ix_outbox_pending ON outbox_event (created_seq) WHERE published_at IS NULL AND parked_at IS NULL;
CREATE INDEX ix_outbox_parked ON outbox_event (parked_at) WHERE parked_at IS NOT NULL;

COMMENT ON COLUMN outbox_event.parked_reason IS 'Why the row left the relay: the payload error class, or the operator''s reason.';
COMMENT ON COLUMN outbox_event.parked_by IS '''relay'' for a payload error, otherwise the operator''s login role (session_user).';

-- Operator path for a row the relay keeps retrying. Runs as the schema owner
-- (payee_verification_migrate, break-glass credential); EXECUTE is not granted
-- to the runtime or import roles. Refuses unknown, published or already parked
-- rows and a blank reason.
DO $$
BEGIN
    EXECUTE format($f$
        CREATE FUNCTION park_outbox_event(p_event_id UUID, p_reason TEXT) RETURNS TIMESTAMPTZ
            LANGUAGE plpgsql SET search_path = %I, pg_temp AS
        $body$
        DECLARE
            parked TIMESTAMPTZ;
        BEGIN
            IF p_reason IS NULL OR length(btrim(p_reason)) = 0 THEN
                RAISE EXCEPTION 'a reason is required to park an outbox event';
            END IF;
            UPDATE outbox_event
               SET parked_at = now(), parked_reason = left(btrim(p_reason), 512),
                   parked_by = left(session_user, 128)
             WHERE event_id = p_event_id AND published_at IS NULL AND parked_at IS NULL
            RETURNING parked_at INTO parked;
            IF parked IS NULL THEN
                RAISE EXCEPTION 'outbox event %% is unknown, already published or already parked', p_event_id;
            END IF;
            RETURN parked;
        END
        $body$
    $f$, current_schema());
END
$$;

REVOKE ALL ON FUNCTION park_outbox_event(UUID, TEXT) FROM PUBLIC;
