Feature: Encoding and date edge cases
  Covers TC-70..TC-75 from TEST_SUITE_SPEC.md section 7.8. These scenarios sit outside the
  matching contract itself: they check that text the service does not try to match against
  the reference list — diacritics, non-Latin script, dates, and oversized fields — survives
  the round trip unmangled, and that inputs with no single mandated outcome are still
  handled predictably rather than causing a crash or a hang.

  @resilience @encoding @high @fast @TC-70
  Scenario: A city name with diacritics is preserved intact on the flagged message
    Given a booking with origin "Kraków" and destination "New Delhi"
    When it is published to the raw topic
    Then it lands on the flagged topic
    And the reason is "UNMATCHED_ORIGIN_CITY"
    And the origin value on the flagged message is byte-identical to the value that was published

  @resilience @encoding @medium @fast @TC-71
  Scenario: Non-Latin script survives the round trip without mangling
    Given a booking with origin "東京" and destination "New Delhi"
    When it is published to the raw topic
    Then it lands on the flagged topic
    And the reason is "UNMATCHED_ORIGIN_CITY"
    And the origin value on the flagged message is byte-identical to the value that was published

  @resilience @dates @high @fast @TC-72
  Scenario: The requested date passes through byte-identical, with no timezone shift
    # requestedDate is a plain date with no timezone component. Any attempt by
    # the service to parse and re-serialise it risks a day shift depending on
    # the runtime's default zone, which matters in a cross-border logistics
    # context. The service is expected to treat requestedDate as an opaque
    # string and pass it through unexamined rather than round-trip it through
    # a date type.
    Given a booking with requested date "2026-09-05"
    And its origin is "Mumbai" and its destination is "New Delhi"
    When it is published to the raw topic
    Then it lands on the enriched topic
    And the requested date on the output is byte-identical to "2026-09-05"

  @resilience @dates @medium @fast @TC-73
  Scenario: An invalid date string is passed through, not rejected
    # Consistent with TC-72: requestedDate is opaque to the service, so a
    # string that is not a valid date at all is still carried through
    # unexamined rather than causing the booking to be rejected or flagged.
    Given a booking with requested date "not-a-date"
    And its origin is "Mumbai" and its destination is "New Delhi"
    When it is published to the raw topic
    Then it lands on the enriched topic
    And the requested date on the output is byte-identical to "not-a-date"

  @resilience @passthrough @low @slow @TC-74
  Scenario: A very long shipper name is handled or rejected predictably
    # The contract does not state a maximum shipper length, so this scenario
    # does not assert a single mandated outcome. It asserts that the service
    # behaves predictably one way or the other: either the booking is
    # enriched with the shipper name intact, or it is flagged — never a
    # crash, a hang, or a silently truncated field.
    Given a booking with a shipper name of 5000 characters
    And its origin is "Mumbai" and its destination is "New Delhi"
    When it is published to the raw topic
    Then it lands on exactly one of the enriched or flagged topics
    And if it lands on the enriched topic, the shipper name is unchanged

  @resilience @message-contract @low @nightly @TC-75
  Scenario: A payload near the broker message size limit behaves predictably
    # As with TC-74, no single outcome is mandated here either. The service
    # must not crash, hang, or silently drop the message — it must land on
    # exactly one output topic, enriched or flagged.
    Given a booking whose payload size is close to the broker's message size limit
    When it is published to the raw topic
    Then it lands on exactly one of the enriched or flagged topics
