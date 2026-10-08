#!/usr/bin/env bash
# Operator path of ADR-021 decision 4: takes one outbox row out of the relay by
# hand, with the reason recorded. Use it only for a row the relay keeps
# retrying (it never parks such a row itself) once the owning squad has
# decided the event will not be sent; the row stays in outbox_event with
# parked_at, parked_reason and parked_by (your login role).
#
# Usage:
#   PGPASSWORD=... db/ops/park-outbox-event.sh "<conninfo as payee_verification_migrate>" <event-id> "<reason>"
# The reason should name the incident or ticket and why the event is dropped.
# Refuses a blank reason and an unknown, published or already parked event.
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
