Feature: Correctness at scale
  Covers TC-60..TC-64 from TEST_SUITE_SPEC.md section 7.7. Framed as correctness at scale,
  not performance measurement — no throughput figure is asserted anywhere in this file.
  What is asserted is that nothing is lost, nothing is double-counted, and nothing crosses
  onto the wrong topic once the volume of bookings goes beyond what a human would eyeball.

  @volume @routing @critical @nightly @slow @TC-60
  Scenario Outline: Every booking is accounted for under load
    Given <total> bookings where <invalid> contain unmatchable cities
    When they are published to the raw topic
    Then <valid> bookings arrive on the enriched topic
    And <invalid> bookings arrive on the flagged topic
    And no booking is lost
    # B-8: totals alone cannot catch a swap. Route one enriched booking to the
    # flagged topic and one flagged booking to the enriched topic and every
    # count above still matches. These two compare identities, not sizes.
    And each booking lands on the topic its data says it should
    And every booking appears exactly once across the two output topics

    Examples:
      | total | invalid | valid |
      | 1000  | 150     | 850   |

  @volume @routing @high @nightly @slow @TC-61
  Scenario: Per-topic totals match the oracle after a bulk run
    Given the bulk sample data set and its labelled oracle
    When the full data set is published to the raw topic
    Then the count of messages on the enriched topic matches the oracle's enriched count
    And the count of messages on the flagged topic matches the oracle's flagged count

  @volume @flagging @high @nightly @slow @TC-62
  Scenario: Per-reason totals match the oracle after a bulk run
    Given the bulk sample data set and its labelled oracle
    When the full data set is published to the raw topic
    Then the count of flagged messages for each reason matches the oracle's count for that reason

  @volume @routing @critical @nightly @slow @TC-63
  Scenario: No booking id appears on both output topics after a bulk run
    Given the bulk sample data set and its labelled oracle
    When the full data set is published to the raw topic
    Then no booking id present on the enriched topic also appears on the flagged topic

  @volume @routing @high @nightly @slow @TC-64
  Scenario: Concurrent publishers lose no messages
    # Publishing from several threads simultaneously is closer to a real
    # ingestion profile than sequential publishing, and is where message loss
    # actually shows up. This is still framed as correctness at scale, not a
    # performance measurement — no throughput figure is asserted.
    Given the bulk sample data set and its labelled oracle
    When the full data set is published to the raw topic from several publishers at once
    Then every booking in the data set is accounted for across both output topics
