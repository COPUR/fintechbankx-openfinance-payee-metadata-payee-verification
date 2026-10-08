#!/usr/bin/env bash
# Operator path of ADR-021 decision 4: takes one outbox row out of the relay by
# hand, with the reason recorded. Use it only for a row the relay keeps
# retrying (it never parks such a row itself) once the owning squad has
# decided the event will not be sent; the row stays in outbox_event with
# parked_at, parked_reason and parked_by (your login role).
#
# Runs as the operator role payee_verification_ops (secret
# <env>/payee-verification-service/db-ops), which may only EXECUTE the SECURITY
# DEFINER function park_outbox_event (V9) and read the columns needed to find
# rows; it needs no schema-owner credential.
#
# Usage:
#   PGPASSWORD=... db/ops/park-outbox-event.sh "<conninfo as payee_verification_ops>" <event-id> "<reason>"
# The reason should name the incident or ticket and why the event is dropped.
# Refuses a blank reason and an unknown, published or already parked event.
# The park leaves park_counted false; the relay counts it once on its next run
# (outbox_parked_events_total{exception="OperatorPark"}, platform alert
# OutboxEventsParked). There is no replay path; one added later must reset
# park_counted to false.
set -euo pipefail

if [ "$#" -ne 3 ]; then
  echo "usage: $0 \"<conninfo>\" <event-id> \"<reason>\"" >&2
  exit 2
fi
conninfo="$1" event_id="$2" reason="$3"
if ! [[ "$event_id" =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$ ]]; then
  echo "event id must be a UUID" >&2
  exit 2
fi

PGAPPNAME="${PGAPPNAME:-payee-verification-operator}" psql --no-psqlrc -v ON_ERROR_STOP=1 -q -At \
  -v event_id="$event_id" -v reason="$reason" "$conninfo" <<'SQL'
SET search_path = sc_of_payee_verification;
SELECT 'parked ' || :'event_id' || ' at ' || park_outbox_event(:'event_id'::uuid, :'reason');
SQL
