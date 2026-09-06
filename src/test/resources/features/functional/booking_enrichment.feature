Feature: Booking-level enrichment behaviour
  Covers TC-21..TC-29 from TEST_SUITE_SPEC.md section 7.2. Where city_correction.feature
  proves the matching contract field by field, this file proves what happens to a whole
  booking once both its city fields have gone through matching: which route it lands on,
  what the enrichment metadata carries, and that fields unrelated to city matching are
  left alone.

  @functional @city-correction @critical @fast @smoke @TC-21
  Scenario: Both cities resolve, so the booking is enriched with both corrected
    Given a booking with origin "Mumbi" and destination "now delhi"
    When it is published to the raw topic
    Then it lands on the enriched topic
    And its origin is "Mumbai"
    And its destination is "New Delhi"

  @functional @flagging @critical @fast @smoke @TC-22
  Scenario: Origin resolves but destination fails, so the booking is flagged naming the destination
    Given a booking with origin "Mumbai" and destination "Warsaw"
    When it is published to the raw topic
    Then it lands on the flagged topic
    And the reason concerns the destination field
    And the reason is "UNMATCHED_DESTINATION_CITY"
    And the flagged original is carried as a booking object
    And the flagged original equals the payload published

  @functional @flagging @high @fast @TC-23
  Scenario: Both cities fail, producing two reasons ordered origin then destination
    Given a booking with origin "Warsaw" and destination "Lisbon"
    When it is published to the raw topic
    Then it lands on the flagged topic
    And the reasons are "UNMATCHED_ORIGIN_CITY" and "UNMATCHED_DESTINATION_CITY", in that order
    And the flagged original is carried as a booking object
    And the flagged original equals the payload published

  @functional @flagging @critical @fast @TC-24
  Scenario: A valid city does not rescue a booking that has one invalid city
    # The booking as a whole is flagged even though the destination resolves cleanly.
    # A service that let one good field carry a bad one through to the enriched topic
    # would defeat the point of validating both fields independently.
    Given a booking with origin "Mumbaiii" and destination "New Delhi"
    When it is published to the raw topic
    Then it lands on the flagged topic
    And the reason concerns the origin field
    And it is confirmed absent from the enriched topic after a bounded wait

  @functional @city-correction @high @fast @TC-25
  Scenario: Enrichment metadata retains both original values alongside the corrected ones
    Given a booking with origin "Mumbi" and destination "now delhi"
    When it is published to the raw topic
    Then it lands on the enriched topic
    And the enrichment metadata records the original origin "Mumbi" and the original destination "now delhi"

  @functional @passthrough @critical @fast @TC-26
  Scenario: Shipper, mode and requested date pass through unchanged
    Given a booking with shipper "ABC Logistics", mode "ROAD" and requested date "2026-09-05"
    And its origin is "Mumbai" and its destination is "New Delhi"
    When it is published to the raw topic
    Then it lands on the enriched topic
    And the shipper, mode and requested date are unchanged

  @functional @passthrough @medium @fast @TC-27
  Scenario: An unrecognised extra field does not break processing
    Given a booking with origin "Mumbai" and destination "New Delhi"
    And an extra unrecognised field "vehiclePlate" set to "MH-12-AB-1234"
    When it is published to the raw topic
    Then it lands on the enriched topic

  @functional @city-correction @medium @fast @TC-28
  Scenario: A confidence score is present for each corrected city
    Given a booking with origin "Mumbi" and destination "now delhi"
    When it is published to the raw topic
    Then it lands on the enriched topic
    And a confidence score is present for the origin correction
    And a confidence score is present for the destination correction

  @functional @city-correction @flagging @high @slow @TC-29
  Scenario: Ambiguous input is flagged with candidates listed in reference order
    # This scenario needs a service instance started against an extended reference
    # list that includes both "New Delhi" and "Delhi", so "Delh" resolves to two
    # equally valid candidates instead of one and the ambiguity path is actually
    # reachable. That is a different service configuration, not just a longer
    # wait, which is why the scenario is tagged @slow rather than @fast.
    Given a booking with origin "Mumbai" and destination "Delh"
    When it is published to the raw topic
    Then it lands on the flagged topic
    And the reason concerns the destination field
    And the reason is "AMBIGUOUS_DESTINATION_CITY"
    And the candidates are "New Delhi" and "Delhi", in reference order

  @functional @city-correction @flagging @critical @slow @regression @TC-46
  Scenario: Ambiguous origin is flagged with candidates in reference order
    # The origin mirror of TC-29. Until this existed, AMBIGUOUS_ORIGIN_CITY was a
    # reason the contract allowed and no scenario asserted, so a service that
    # reported every ambiguity under the destination reason would have passed.
    # Needs the extended reference list, hence @slow.
    Given a booking with origin "Delh" and destination "Mumbai"
    When it is published to the raw topic
    Then it lands on the flagged topic
    And the reason concerns the origin field
    And the reason is "AMBIGUOUS_ORIGIN_CITY"
    And the candidates are "New Delhi" and "Delhi", in reference order
    And the flagged original is carried as a booking object
    And the flagged original equals the payload published

  @functional @city-correction @flagging @high @slow @regression @TC-47
  Scenario: Both fields ambiguous produces both reasons, origin then destination
    # Two reasons on one booking, and their order is part of the contract: a
    # consumer reading reasons[0] to decide which field to route for review gets
    # the wrong field if the order is not fixed.
    Given a booking with origin "Delh" and destination "Delh"
    When it is published to the raw topic
    Then it lands on the flagged topic
    And the reasons are "AMBIGUOUS_ORIGIN_CITY" and "AMBIGUOUS_DESTINATION_CITY", in that order

  @functional @city-correction @critical @slow @regression @TC-48
  Scenario: An exact match wins outright and is never reported ambiguous
    # The contract ranks the exact rule above the fuzzy rule. With both "New Delhi"
    # and "Delhi" in the reference list, the input "Delhi" equals one of them exactly
    # and must enrich to it rather than being reported ambiguous with the other.
    # The confidence assertion is what makes this a precedence test: enriching to
    # "Delhi" with a confidence below 1.0 would mean the fuzzy rule happened to
    # pick the right city, not that the exact rule took priority.
    Given a booking with origin "Delhi" and destination "Mumbai"
    When it is published to the raw topic
    Then it lands on the enriched topic
    And its origin is "Delhi"
    And the origin confidence is exactly 1.0
