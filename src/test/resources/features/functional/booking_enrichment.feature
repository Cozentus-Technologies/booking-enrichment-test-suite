Feature: Booking-level enrichment behaviour
  Covers TC-21..TC-29 from TEST_SUITE_SPEC.md section 7.2. Where city_correction.feature
  proves the matching contract field by field, this file proves what happens to a whole
  booking once both its city fields have gone through matching: which route it lands on,
  what the enrichment metadata carries, and that fields unrelated to city matching are
  left alone.

  @functional @city-correction @critical @fast @TC-21
  Scenario: Both cities resolve, so the booking is enriched with both corrected
    Given a booking with origin "Mumbi" and destination "now delhi"
    When it is published to the raw topic
    Then it lands on the enriched topic
    And its origin is "Mumbai"
    And its destination is "New Delhi"

  @functional @flagging @critical @fast @TC-22
  Scenario: Origin resolves but destination fails, so the booking is flagged naming the destination
    Given a booking with origin "Mumbai" and destination "Warsaw"
    When it is published to the raw topic
    Then it lands on the flagged topic
    And the reason concerns the destination field
    And the reason is "UNMATCHED_DESTINATION_CITY"

  @functional @flagging @high @fast @TC-23
  Scenario: Both cities fail, producing two reasons ordered origin then destination
    Given a booking with origin "Warsaw" and destination "Lisbon"
    When it is published to the raw topic
    Then it lands on the flagged topic
    And the reasons are "UNMATCHED_ORIGIN_CITY" and "UNMATCHED_DESTINATION_CITY", in that order

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
