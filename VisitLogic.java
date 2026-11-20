package se.besoksloggen;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

final class VisitLogic {
    static final long MINUTE = 60_000L;
    static final int DEFAULT_VISIT_MINUTES = 20;
    static final long MAX_GAP = 8 * MINUTE;
    static final ZoneId SWEDEN = ZoneId.of("Europe/Stockholm");
    static final String WORK = "Jobb";
    static final String HOME = "Hemma";
    static final String BAR = "Krog";
    static final String RESTAURANT = "Restaurang";
    static final String OTHER = "Övrigt";

    record Fix(long at, double lat, double lon, float accuracy) {}

    record Place(String key, String category, String name, String address,
                 double lat, double lon, int meters) {}

    record Stop(long start, long lastInside, double lat, double lon, String key,
                String category, String name, String address, int radius, Fix outside) {
        boolean qualifies(int minutes) {
            return lastInside - start > minutes * MINUTE;
        }

        Stop confirmInside(long at) {
            return new Stop(start, at, lat, lon, key, category, name, address, radius, null);
        }

        Stop pendingExit(Fix fix) {
            return new Stop(start, lastInside, lat, lon, key, category, name, address, radius, fix);
        }
    }

    record Change(Stop ended, Stop active) {}

    record Dwell(long entered, long inside, long outside, boolean notified) {
        static final Dwell EMPTY = new Dwell(0, 0, 0, false);

        boolean due(int minutes) {
            return !notified && outside == 0 && inside - entered > minutes * MINUTE;
        }

        Dwell markNotified() {
            return new Dwell(entered, inside, outside, true);
        }

        Dwell step(Fix fix, double lat, double lon, int radius) {
            if (!Float.isFinite(fix.accuracy) || fix.accuracy <= 0
                    || fix.accuracy > Math.min(85, radius) || fix.at <= Math.max(inside, outside)) return this;
            double meters = distance(fix.lat, fix.lon, lat, lon);
            if (!Double.isFinite(meters)) return this;
            if (meters <= radius) {
                if (entered == 0 || fix.at - inside > MAX_GAP) {
                    return new Dwell(fix.at, fix.at, 0, false);
                }
                return new Dwell(entered, fix.at, 0, notified);
            }
            if (entered == 0 || fix.at - inside > MAX_GAP) return EMPTY;
            if (meters <= radius + Math.max(15, fix.accuracy * 0.5)) return this;
            if (outside == 0) return new Dwell(entered, inside, fix.at, notified);
            return fix.at - outside >= 20_000L ? EMPTY : this;
        }
    }

    record Period(LocalDate from, LocalDate until) {
        long start() {
            return from.atStartOfDay(SWEDEN).toInstant().toEpochMilli();
        }

        long end() {
            return until.atStartOfDay(SWEDEN).toInstant().toEpochMilli();
        }

        long overlap(long start, long end) {
            return Math.max(0L, Math.min(end, end()) - Math.max(start, start()));
        }
    }

    static Period period(long time, int offset) {
        LocalDate date = Instant.ofEpochMilli(time).atZone(SWEDEN).toLocalDate();
        LocalDate cut = date.withDayOfMonth(20);
        if (date.isBefore(cut)) cut = cut.minusMonths(1);
        cut = cut.plusMonths(offset);
        return new Period(cut, cut.plusMonths(1));
    }

    static double distance(double lat, double lon, double otherLat, double otherLon) {
        double dLat = Math.toRadians(otherLat - lat);
        double dLon = Math.toRadians(otherLon - lon);
        double a = Math.pow(Math.sin(dLat / 2), 2) + Math.cos(Math.toRadians(lat))
                * Math.cos(Math.toRadians(otherLat)) * Math.pow(Math.sin(dLon / 2), 2);
        return 12_742_000 * Math.asin(Math.sqrt(Math.min(1, a)));
    }

    static Place match(Fix fix, List<Place> places) {
        Place best = null;
        double nearest = Double.POSITIVE_INFINITY;
        for (Place place : places) {
            double meters = distance(fix.lat, fix.lon, place.lat, place.lon);
            if (meters <= place.meters && fix.accuracy <= Math.min(85, place.meters)
                    && meters < nearest) {
                best = place;
                nearest = meters;
            }
        }
        return best;
    }

    private static Stop enter(Fix fix, Place place) {
        if (place == null) {
            return new Stop(fix.at, fix.at, fix.lat, fix.lon, "", OTHER, "", "", 110, null);
        }
        return new Stop(fix.at, fix.at, place.lat, place.lon, place.key, place.category,
                place.name, place.address, place.meters, null);
    }

    static Change step(Stop current, Fix fix, List<Place> places) {
        if (!Double.isFinite(fix.lat) || !Double.isFinite(fix.lon) || !Float.isFinite(fix.accuracy)
                || fix.accuracy <= 0 || fix.accuracy > 85) {
            return new Change(null, current);
        }
        Place place = match(fix, places);
        if (current == null) return new Change(null, enter(fix, place));
        long previous = current.outside == null ? current.lastInside : current.outside.at;
        if (fix.at <= previous) return new Change(null, current);
        if (fix.at - current.lastInside > MAX_GAP) {
            return new Change(current.pendingExit(null), enter(fix, place));
        }

        if (current.key.isEmpty() && place != null
                && distance(current.lat, current.lon, place.lat, place.lon) <= place.meters) {
            return new Change(null, new Stop(current.start, fix.at, place.lat, place.lon,
                    place.key, place.category, place.name, place.address, place.meters, null));
        }
        double fromCenter = distance(fix.lat, fix.lon, current.lat, current.lon);
        boolean changedPlace = place != null && !current.key.equals(place.key)
                && distance(fix.lat, fix.lon, place.lat, place.lon) + fix.accuracy < fromCenter;
        double margin = Math.max(15, fix.accuracy * 0.5);
        if (!changedPlace && fromCenter <= current.radius) {
            return new Change(null, current.confirmInside(fix.at));
        }
        if (!changedPlace && fromCenter <= current.radius + margin) return new Change(null, current);
        if (current.outside == null) return new Change(null, current.pendingExit(fix));
        if (fix.at - current.outside.at < 20_000L) return new Change(null, current);

        long departure = current.lastInside + (current.outside.at - current.lastInside) / 2;
        Stop ended = current.confirmInside(departure);
        Place previousPlace = match(current.outside, places);
        boolean sameNextPlace = (previousPlace == null && place == null)
                || (previousPlace != null && place != null && previousPlace.key.equals(place.key));
        boolean close = distance(fix.lat, fix.lon, current.outside.lat, current.outside.lon) <= 110;
        Fix first = sameNextPlace && close ? current.outside : fix;
        return new Change(ended, enter(first, place).confirmInside(fix.at));
    }
}
