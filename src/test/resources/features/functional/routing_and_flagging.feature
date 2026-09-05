Feature: Routing between the enriched and flagged topics
  Covers TC-30..TC-34 from TEST_SUITE_SPEC.md section 7.3. Routing is the guarantee the
  whole harness exists to check: a booking lands on exactly one of the two output topics,
  never both, and never neither. The negative assertions here (TC-32, TC-33) are as
  important as the positive ones.

  @functional @routing @critical @fast @TC-30
  Scenario: An enriched booking reaches the enriched topic
    Given a booking with origin "Mumbai" and destination "New Delhi"
    When it is published to the raw topic
    Then it lands on the enriched topic

  @functional @routing @flagging @critical @fast @TC-31
  Scenario: A flagged booking reaches the flagged topic
    Given a booking with origin "Warsaw" and destination "New Delhi"
    When it is published to the raw topic
    Then it lands on the flagged topic

  @functional @routing @flagging @critical @fast @TC-32
  Scenario: A flagged booking never reaches the enriched topic
    # This is the negative assertion the suite exists to guarantee: routing is
    # exclusive, so a booking that fails matching must never also appear on the
    # success topic. Presence on the flagged topic is confirmed first, and then
    # absence from the enriched topic is confirmed by a bounded wait rather than
    # simply assumed because nothing was asserted there.
    Given a booking with origin "Warsaw" and destination "New Delhi"
    When it is published to the raw topic
    Then it lands on the flagged topic
    And it is confirmed absent from the enriched topic after a bounded wait

  @functional @routing @critical @fast @TC-33
  Scenario: An enriched booking never reaches the flagged topic
    Given a booking with origin "Mumbai" and destination "New Delhi"
    When it is published to the raw topic
    Then it lands on the enriched topic
    And it is confirmed absent from the flagged topic after a bounded wait

  @functional @routing @critical @fast @TC-34
  Scenario Outline: Every booking lands on exactly one output topic
    Given a booking with origin "<origin>" and destination "<destination>"
    When it is published to the raw topic
    Then it lands on exactly one of the enriched or flagged topics

    Examples:
      | origin | destination |
      | Mumbai | New Delhi   |
      | Warsaw | New Delhi   |
