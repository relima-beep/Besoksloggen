package se.besoksloggen;

import java.time.LocalDate;
import java.util.List;

public final class VisitLogicChecks {
    private static final long M = VisitLogic.MINUTE;
    private static final long BASE = 100 * M;
    private static final double LAT = 59.4231, LON = 17.8365;
    private static final VisitLogic.Place WORK = new VisitLogic.Place(
            "work", VisitLogic.WORK, "Jobb", "Järfälla", LAT, LON, 150);
    private static final List<VisitLogic.Place> PLACES = List.of(WORK);

    public static void main(String[] args) {
        VisitLogic.Stop visit = null;
        for (int minute = 0; minute <= 20; minute += 5) {
            visit = step(visit, minute, 0, 10).active();
        }
        check(visit.category().equals("Jobb"), "known destination labeled as work");
        check(!visit.qualifies(VisitLogic.DEFAULT_VISIT_MINUTES), "exactly 20 minutes is below the threshold");
        check(visit.qualifies(10), "a shorter setting qualifies the same stay");
        visit = step(visit, 21, 0, 10).active();
        check(visit.qualifies(VisitLogic.DEFAULT_VISIT_MINUTES), "whole stay recorded after 20 minutes");
        check(!visit.qualifies(45), "longer setting keeps the same stay pending");
        check(visit.start() == BASE, "start preserved after qualifying");

        VisitLogic.Change jump = step(visit, 22, 0.01, 10);
        check(jump.ended() == null, "first distant fix doesn't close the visit");
        visit = step(jump.active(), 23, 0, 12).active();
        check(visit.outside() == null, "inside fix cancels spurious departure");
        check(step(visit, 24, 0.01, 125).active() == visit, "poor location rejected");
        check(step(visit, 22, 0.01, 10).active() == visit, "old fixes rejected");

        jump = step(visit, 24, 0.01, 10);
        VisitLogic.Change leave = step(jump.active(), 25, 0.01, 10);
        check(leave.ended().lastInside() == BASE + 23 * M + M / 2, "departure midpoint");
        check(leave.active().start() == BASE + 24 * M, "next visit starts at first stable fix");
        check(leave.active().start() >= leave.ended().lastInside(), "visits don't overlap");

        VisitLogic.Change gap = step(visit, 40, 0, 10);
        check(gap.ended().lastInside() == visit.lastInside(), "unobserved time excluded");
        check(gap.active().start() == BASE + 40 * M, "new visit after a long gap");

        VisitLogic.Stop unknown = VisitLogic.step(null, fix(0, 0, 10), List.of()).active();
        VisitLogic.Stop identified = step(unknown, 1, 0, 10).active();
        check(identified.start() == BASE && identified.category().equals("Jobb"),
                "setting a place during a visit keeps its start time");

        VisitLogic.Place home = new VisitLogic.Place("home", VisitLogic.HOME, "Hemma", "",
                LAT + 0.001, LON, 150);
        check(VisitLogic.match(fix(0, 0.001, 10), List.of(home, WORK)).equals(home),
                "nearest address wins when radii overlap");

        long boundary = LocalDate.of(2026, 11, 20).atStartOfDay(VisitLogic.SWEDEN)
                .toInstant().toEpochMilli();
        VisitLogic.Period previous = VisitLogic.period(boundary - 1, 0);
        VisitLogic.Period next = VisitLogic.period(boundary, 0);
        check(previous.from().equals(LocalDate.of(2026, 10, 20)), "previous date before midnight");
        check(next.from().equals(LocalDate.of(2026, 11, 20)), "new period at local midnight on 20th");
        check(next.until().equals(LocalDate.of(2026, 12, 20)), "period ends on the next 20th");
        check(previous.overlap(boundary - 90 * M, boundary + 30 * M) == 90 * M, "before boundary");
        check(next.overlap(boundary - 90 * M, boundary + 30 * M) == 30 * M, "after boundary");
        check(previous.end() - previous.start() == (31 * 24 + 1) * 60 * M, "autumn DST adds an hour");
        check(VisitLogic.period(boundary, -1).equals(previous), "browse earlier periods");
        addressAlerts();
        System.out.println("Checks passed for visit detection, 20th-to-20th reporting and address alerts");
    }

    private static void addressAlerts() {
        VisitLogic.Dwell dwell = VisitLogic.Dwell.EMPTY;
        for (int minute = 0; minute <= 30; minute += 5) {
            dwell = dwell.step(fix(minute, 0, 10), LAT, LON, 150);
        }
        check(!dwell.due(30), "alert waits at exactly 30 minutes");
        dwell = dwell.step(fix(31, 0, 10), LAT, LON, 150);
        check(dwell.due(30) && !dwell.due(45), "each address has its own delay");
        dwell = dwell.markNotified().step(fix(32, 0, 10), LAT, LON, 150);
        check(!dwell.due(30), "one alert per continuous stay");
        dwell = dwell.step(fix(33, 0.01, 15), LAT, LON, 150);
        check(dwell.outside() != 0, "possible exit recorded");
        dwell = dwell.step(fix(34, 0, 15), LAT, LON, 150);
        check(dwell.entered() == BASE && dwell.notified(), "a single jump does not reset the alert");
        dwell = dwell.step(fix(35, 0.01, 15), LAT, LON, 150)
                .step(fix(36, 0.01, 15), LAT, LON, 150);
        check(dwell.equals(VisitLogic.Dwell.EMPTY), "confirmed exit clears previous dwell");
        for (int minute = 37; minute <= 68; minute += 5) {
            dwell = dwell.step(fix(minute, 0, 10), LAT, LON, 150);
        }
        dwell = dwell.step(fix(68, 0, 10), LAT, LON, 150);
        check(dwell.due(30), "a later stay can alert again");
        dwell = dwell.step(fix(84, 0, 10), LAT, LON, 150);
        check(dwell.entered() == BASE + 84 * M && !dwell.due(30), "location outage is excluded");

        VisitLogic.Dwell noFix = VisitLogic.Dwell.EMPTY.step(fix(0, 0, 75), LAT, LON, 50);
        check(noFix.equals(VisitLogic.Dwell.EMPTY), "location error exceeds a small radius");
        VisitLogic.Dwell far = VisitLogic.Dwell.EMPTY.step(fix(0, 0, 15), LAT + 0.03, LON, 150);
        check(far.equals(VisitLogic.Dwell.EMPTY), "unrelated address does not start a timer");

        VisitLogic.Dwell leaving = new VisitLogic.Dwell(BASE, BASE + 30 * M, 0, false)
                .step(fix(31, 0.01, 15), LAT, LON, 150);
        check(!leaving.due(30), "time outside the address does not trigger an alert");
    }

    private static VisitLogic.Change step(VisitLogic.Stop stop, int minute, double shift, float accuracy) {
        return VisitLogic.step(stop, fix(minute, shift, accuracy), PLACES);
    }

    private static VisitLogic.Fix fix(int minute, double shift, float accuracy) {
        return new VisitLogic.Fix(BASE + minute * M, LAT + shift, LON, accuracy);
    }

    private static void check(boolean result, String description) {
        if (!result) throw new AssertionError(description);
    }
}
