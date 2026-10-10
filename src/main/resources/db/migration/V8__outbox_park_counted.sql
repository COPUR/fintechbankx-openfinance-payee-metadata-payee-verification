-- Platform parked-metric ruling (additive; V7 unchanged): counter
-- outbox.parked.events (outbox_parked_events_total{exception}) rises once per
-- parked row, for the relay's own payload parks and for operator parks done
-- outside the app with park_outbox_event() (db/ops/park-outbox-event.sh).
-- The relay sets park_counted when it parks a row; an operator park leaves it
-- false and the relay, holding the relay lock (so one replica), counts it once
-- on its next run with exception="OperatorPark" and sets it. Rows parked before
-- V8 count as already counted. Any replay path must reset it to false (none
-- exists today: a parked row is never relayed again).

ALTER TABLE outbox_event ADD COLUMN park_counted BOOLEAN NOT NULL DEFAULT false;

UPDATE outbox_event SET park_counted = true WHERE parked_at IS NOT NULL;

CREATE INDEX ix_outbox_park_uncounted ON outbox_event (created_seq)
    WHERE parked_at IS NOT NULL AND NOT park_counted;

COMMENT ON COLUMN outbox_event.park_counted IS
    'True once the park was counted in outbox_parked_events_total; a replay must reset it to false.';
