Feature: Message-level contract on the output topics
  Covers TC-35..TC-39 from TEST_SUITE_SPEC.md section 7.4. This file asserts on the Kafka
  message envelope rather than the booking payload: the message key, propagated headers,
  the enrichment status stamp, and relative ordering for bookings sharing a key.

  @functional @message-contract @critical @fast @TC-35
  Scenario: The message key is the booking id on the enriched route
    Given a booking "BKG-1001" with origin "Mumbai" and destination "New Delhi"
    When it is published to the raw topic
    Then it lands on the enriched topic
    And the message key equals the booking id

  @functional @message-contract @critical @fast @TC-36
  Scenario: The message key is the booking id on the flagged route
    Given a booking "BKG-1002" with origin "Warsaw" and destination "New Delhi"
    When it is published to the raw topic
    Then it lands on the flagged topic
    And the message key equals the booking id

  @functional @message-contract @high @fast @TC-37
  Scenario: A publisher correlation-id header survives to the output message
    Given a booking with origin "Mumbai" and destination "New Delhi"
    When it is published to the raw topic with a correlation-id header "corr-abc-123"
    Then it lands on the enriched topic
    And the output message carries the correlation-id header "corr-abc-123"

  @functional @message-contract @medium @fast @TC-38
  Scenario Outline: The service stamps the enrichment status header correctly per route
    Given a booking with origin "<origin>" and destination "<destination>"
    When it is published to the raw topic
    Then the booking lands on the "<outcome>" route
    And the output message carries an enrichment status header of "<outcome>"

    Examples:
      | origin | destination | outcome  |
      | Mumbai | New Delhi   | ENRICHED |
      | Warsaw | New Delhi   | FLAGGED  |

  @functional @message-contract @high @slow @TC-39
  Scenario: Bookings sharing a key retain their relative order
    # Ordering only has meaning within a partition, so this scenario needs a
    # broker-backed instance rather than the fast in-process path — hence @slow
    # rather than @fast.
    Given two bookings sharing the key "BKG-SAME-1", the first with origin "Mumbai" and destination "New Delhi" and the second with origin "Pune" and destination "Chennai"
    When they are published to the raw topic in that order
    Then their outputs arrive in the same relative order they were published
