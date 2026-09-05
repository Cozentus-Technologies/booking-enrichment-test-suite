Feature: Schema conformance of the output messages
  Covers TC-40..TC-45 from TEST_SUITE_SPEC.md section 7.5. Where routing_and_flagging.feature
  proves the routing rule behaviourally, this file proves it is also enforced structurally:
  each output message validates against its own published schema and fails validation
  against the other route's schema, and a schema change against its previous committed
  version is additive only.

  @contract @schema @critical @fast @TC-40
  Scenario: Every enriched message validates against the enriched schema
    Given a booking with origin "Mumbai" and destination "New Delhi"
    When it is published to the raw topic
    Then it lands on the enriched topic
    And the message validates against the "booking-enriched-v1" schema

  @contract @schema @critical @fast @TC-41
  Scenario: Every flagged message validates against the flagged schema
    Given a booking with origin "Warsaw" and destination "New Delhi"
    When it is published to the raw topic
    Then it lands on the flagged topic
    And the message validates against the "booking-flagged-v1" schema

  @contract @schema @high @fast @TC-42
  Scenario: A flagged message fails validation against the enriched schema
    # The enriched schema constrains its status field to the single value ENRICHED,
    # so a flagged message can never satisfy it. This gives the routing guarantee
    # asserted behaviourally in TC-32 a second, independent enforcement mechanism
    # at the contract layer — two mechanisms catching the same class of defect on
    # the critical path.
    Given a booking with origin "Warsaw" and destination "New Delhi"
    When it is published to the raw topic
    Then it lands on the flagged topic
    And the message fails validation against the "booking-enriched-v1" schema

  @contract @schema @medium @fast @TC-43
  Scenario: An extra unrecognised field does not break schema conformance
    Given a booking with origin "Mumbai" and destination "New Delhi"
    And an extra unrecognised field "vehiclePlate" set to "MH-12-AB-1234"
    When it is published to the raw topic
    Then it lands on the enriched topic
    And the message validates against the "booking-enriched-v1" schema

  @contract @schema @city-correction @high @fast @TC-44
  Scenario: Enrichment metadata carries original and corrected values for each city
    Given a booking with origin "Mumbi" and destination "now delhi"
    When it is published to the raw topic
    Then it lands on the enriched topic
    And the message validates against the "booking-enriched-v1" schema
    And the enrichment metadata records the original origin "Mumbi" and the original destination "now delhi"

  @contract @schema @medium @fast @TC-45
  Scenario: A schema change against the previous version is additive only
    # This scenario does not publish a booking at all. It compares the committed
    # schema file against its previous version and fails on a field removal, a
    # type narrowing, an enum reduction, or an optional field made required —
    # the check a schema registry performs in production, run here at the suite
    # level in its absence.
    Given the current "booking-enriched-v1" schema and its previous committed version
    Then the schema change between them is additive only
