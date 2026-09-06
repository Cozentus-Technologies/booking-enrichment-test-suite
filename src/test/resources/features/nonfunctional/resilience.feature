Feature: Resilience of the raw-topic consumer to bad input
  Covers TC-50..TC-55 from TEST_SUITE_SPEC.md section 7.6. Asserts that the service never
  throws out of its message handler: unparseable, empty, or wrongly shaped input is flagged
  rather than dropped or left to crash the consumer, and a bad message never stops the next
  good one from being processed.

  @resilience @flagging @critical @fast @smoke @TC-50
  Scenario: A malformed payload is flagged as MALFORMED_MESSAGE
    Given a payload that is not valid JSON
    When it is published to the raw topic
    Then it lands on the flagged topic
    And the reason is "MALFORMED_MESSAGE"
    # B-6: the reason alone would pass for a message carrying a fabricated
    # booking id, an invented field list, or no record of what actually arrived.
    And the flagged booking id is null
    And the flagged fields list is empty
    And the flagged original is carried as raw text
    And the flagged original equals the payload published
    And the message validates against the "booking-flagged-v1" schema

  @resilience @flagging @critical @fast @TC-51
  Scenario: A valid booking published after a malformed one is still processed
    Given a payload that is not valid JSON
    And a booking with origin "Mumbai" and destination "New Delhi"
    When the malformed payload is published to the raw topic
    And the booking is published to the raw topic immediately afterward
    Then the malformed payload lands on the flagged topic with reason "MALFORMED_MESSAGE"
    And the booking lands on the enriched topic

  @resilience @flagging @medium @fast @TC-52
  Scenario: A malformed message with no key uses the UNKNOWN fallback
    Given a payload that is not valid JSON
    When it is published to the raw topic without a message key
    Then it lands on the flagged topic
    And the message key is "UNKNOWN"

  @resilience @flagging @high @fast @TC-53
  Scenario: A duplicate booking id is processed each time it arrives
    # Delivery is at-least-once and the service is deliberately not idempotent:
    # two identical raw messages must produce two enriched messages. The
    # assertion here is that both outputs are correct and neither is
    # corrupted — not that the duplicate is suppressed. A deduplicating
    # service would fail this scenario just as surely as a corrupting one.
    Given the same booking published twice with the booking id "BKG-DUP-1"
    When both copies are published to the raw topic
    Then two enriched messages for "BKG-DUP-1" arrive on the enriched topic
    And both are correctly enriched

  @resilience @flagging @medium @fast @TC-54
  Scenario: An empty payload is flagged, not silently dropped
    Given a payload that is empty
    When it is published to the raw topic
    Then it lands on the flagged topic
    And the reason is "MALFORMED_MESSAGE"

  @resilience @flagging @low @fast @TC-55
  Scenario: A JSON array where an object is expected is flagged
    Given a payload that is a JSON array instead of a booking object
    When it is published to the raw topic
    Then it lands on the flagged topic
    And the reason is "MALFORMED_MESSAGE"

  @resilience @routing @high @slow @regression @TC-56
  Scenario: A booking is not lost when the consumer restarts mid-batch
    # Redelivery on restart is the whole reason offsets are committed after
    # processing rather than before. A service that committed early would lose
    # whatever was in flight, and until this scenario existed nothing would have
    # noticed. The restart keeps the same consumer group and the same topics: a
    # new group would replay from the beginning and prove nothing.
    Given 50 bookings are published to the raw topic
    When the service is restarted on the same consumer group before all are processed
    Then every booking is accounted for across both output topics
