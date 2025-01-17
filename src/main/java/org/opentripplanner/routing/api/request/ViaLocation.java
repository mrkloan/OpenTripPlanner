package org.opentripplanner.routing.api.request;

import java.time.Duration;
import java.util.Objects;
import org.opentripplanner.model.GenericLocation;

/**
 * Represents a point to pass on trip.
 *
 * @param point            Coordinates of Via point
 * @param passThroughPoint Does the via point represent a pass through
 * @param minSlack         Minimum time that is allowed to wait for interchange.
 * @param maxSlack         Maximum time to wait for next departure.
 */
public record ViaLocation(
  GenericLocation point,
  boolean passThroughPoint,
  Duration minSlack,
  Duration maxSlack
) {
  public static final Duration DEFAULT_MAX_SLACK = Duration.ofMinutes(5L);
  public static final Duration DEFAULT_MIN_SLACK = Duration.ofMinutes(20L);

  public ViaLocation {
    Objects.requireNonNull(minSlack);
    Objects.requireNonNull(maxSlack);
  }

  public ViaLocation(GenericLocation point, boolean passThroughPoint, Duration minSlack) {
    this(point, passThroughPoint, minSlack, DEFAULT_MAX_SLACK);
  }

  public ViaLocation(GenericLocation point, boolean passThroughPoint) {
    this(point, passThroughPoint, DEFAULT_MIN_SLACK, DEFAULT_MAX_SLACK);
  }
}
