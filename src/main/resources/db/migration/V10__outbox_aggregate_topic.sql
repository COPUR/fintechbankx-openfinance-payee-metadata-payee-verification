-- One Kafka topic per aggregate (ADR-019): payee verification events go to
-- evt.of.payee.v1, named by their eventType header, instead of the per-event
-- topic evt.of.payee.verification-completed.v1.
-- Unpublished rows (pending or parked) move to the aggregate topic; published
-- rows keep the topic they were sent to. From now on an unpublished row can only
-- target the aggregate topic, so the relay never writes a per-event topic.

UPDATE outbox_event SET topic = 'evt.of.payee.v1' WHERE published_at IS NULL;

ALTER TABLE outbox_event ADD CONSTRAINT ck_outbox_aggregate_topic
    CHECK (published_at IS NOT NULL OR topic = 'evt.of.payee.v1');
