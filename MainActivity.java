package se.besoksloggen;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.location.Address;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class MainActivity extends Activity {
    private static final int BG = 0xff0d141e, CARD = 0xff172330, BORDER = 0xff263748;
    private static final int FG = 0xffedf6ff, MUTED = 0xffa7bacc, MINT = 0xff71e3ca;
    private static final int WARM = 0xfff0c488, BLUE = 0xff9abfff, DARK = 0xff152421;
    private static final int ASK_LOCATION = 8, ASK_BACKGROUND = 9, ASK_NOTIFICATIONS = 10;
    private static final int DEFAULT_ALERT_MINUTES = 30;
    private static final Locale SV = Locale.forLanguageTag("sv-SE");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", SV);
    private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("d MMM", SV);
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm", SV);
    private static final String[] CATEGORIES = {
        VisitLogic.OTHER, VisitLogic.WORK, VisitLogic.HOME, VisitLogic.BAR, VisitLogic.RESTAURANT
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService geocoding = Executors.newSingleThreadExecutor();
    private enum Tab { OVERVIEW, DAY, JOB, PLACES, SETTINGS }
    private VisitStore store;
    private AddressNotifications addressNotifications;
    private LinearLayout root, body;
    private ScrollView scroll;
    private Tab tab = Tab.OVERVIEW;
    private int offset;
    private int dayOffset;
    private Runnable onPreciseGranted;
    private LocationListener positionListener;
    private Runnable positionTimeout;
    private AlertDialog positionDialog;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        store = VisitStore.get(this);
        addressNotifications = new AddressNotifications(this);
        if (state != null) {
            tab = Tab.valueOf(state.getString("tab", Tab.OVERVIEW.name()));
            offset = state.getInt("offset");
            dayOffset = state.getInt("dayOffset");
        }
        root = vertical();
        root.setBackgroundColor(BG);
        setContentView(root);
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                if (Build.VERSION.SDK_INT >= 35) {
                    view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                }
                return insets;
            });
            root.requestApplyInsets();
        }
        render();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("tab", tab.name());
        state.putInt("offset", offset);
        state.putInt("dayOffset", dayOffset);
        super.onSaveInstanceState(state);
    }

    @Override protected void onResume() {
        super.onResume();
        if (store.enabled() && !TrackingService.running && TrackingService.hasPrecise(this)
                && locationOn()) startTracking(false);
        render();
        handler.postDelayed(refresh, 12_000);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    @Override protected void onDestroy() {
        clearPositionRequest();
        geocoding.shutdownNow();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (tab == Tab.OVERVIEW || tab == Tab.DAY || tab == Tab.JOB) render();
            handler.postDelayed(this, 12_000);
        }
    };

    private void render() {
        int previousScroll = scroll == null ? 0 : scroll.getScrollY();
        root.removeAllViews();
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        body = vertical();
        body.setPadding(dp(20), dp(20), dp(20), dp(24));
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        String heading = switch (tab) {
            case OVERVIEW -> "Period 20:e–20:e";
            case DAY -> "Dagslogg";
            case JOB -> "Jobbstatistik";
            case PLACES -> "Sparade platser";
            case SETTINGS -> "Inställningar";
        };
        body.addView(label(heading, 34, FG, true));
        gap(body, 21);
        switch (tab) {
            case OVERVIEW -> renderOverview();
            case DAY -> renderDay();
            case JOB -> renderJob();
            case PLACES -> renderPlaces();
            case SETTINGS -> renderSettings();
        }

        LinearLayout nav = horizontal();
        nav.setBackgroundColor(CARD);
        nav.setPadding(dp(9), dp(7), dp(9), dp(8));
        weight(nav, tabButton("20:e–20:e", Tab.OVERVIEW));
        space(nav, 5);
        weight(nav, tabButton("Dag", Tab.DAY));
        space(nav, 3);
        weight(nav, tabButton("Jobb", Tab.JOB));
        space(nav, 3);
        weight(nav, tabButton("Platser", Tab.PLACES));
        space(nav, 5);
        weight(nav, tabButton("Inställningar", Tab.SETTINGS));
        root.addView(nav, new LinearLayout.LayoutParams(-1, -2));
        ScrollView current = scroll;
        current.post(() -> current.scrollTo(0, previousScroll));
    }

    private TextView tabButton(String name, Tab destination) {
        TextView item = button(name, tab == destination, () -> switchTab(destination));
        item.setTextSize(12);
        item.setPadding(dp(2), dp(4), dp(2), dp(4));
        return item;
    }

    private void switchTab(Tab destination) {
        if (tab == destination) return;
        tab = destination;
        scroll.scrollTo(0, 0);
        render();
    }

    private void renderJob() {
        long now = System.currentTimeMillis();
        VisitLogic.Period period = VisitLogic.period(now, offset);
        List<VisitStore.WorkDay> days = store.workDays(period.from().toString(), period.until().toString());
        long officeTotal = 0;
        for (VisitStore.WorkDay day : days) officeTotal += roundedShift(day.arrival(), day.departure());
        long homeTotal = 0;
        for (VisitStore.HomeWork session : store.homeWork(period.start(), period.end()))
            homeTotal += Math.max(0, Math.min(session.end(), period.end()) - Math.max(session.start(), period.start()));
        long total = officeTotal + homeTotal;

        LinearLayout selector = horizontal();
        selector.setGravity(Gravity.CENTER_VERTICAL);
        TextView prev = button("‹", false, () -> shift(-1));
        prev.setContentDescription("Föregående period");
        selector.addView(prev, new LinearLayout.LayoutParams(dp(45), dp(46)));
        TextView range = label(periodLabel(period), 15, FG, true);
        range.setGravity(Gravity.CENTER);
        weight(selector, range);
        TextView next = button("›", false, () -> shift(1));
        next.setContentDescription("Nästa period");
        next.setEnabled(offset < 0);
        if (offset == 0) next.setAlpha(0.38f);
        selector.addView(next, new LinearLayout.LayoutParams(dp(45), dp(46)));
        body.addView(selector);
        gap(body, 12);

        body.addView(label("Sammanställning för perioden", 16, MUTED, true));
        gap(body, 8);
        LinearLayout totals = horizontal();
        weight(totals, stat("Totalt jobb", total, MINT));
        space(totals, 8);
        weight(totals, stat("På arbetsplats", officeTotal, BLUE));
        body.addView(totals);
        gap(body, 8);
        LinearLayout details = card();
        long average = days.isEmpty() ? 0 : officeTotal / days.size();
        details.addView(label("Arbetsdagar registrerade: " + days.size(), 15, FG, true));
        gap(details, 6);
        details.addView(label("Jobba hemifrån: " + duration(homeTotal), 14, BLUE, false));
        gap(details, 4);
        details.addView(label("Snitt per registrerad arbetsdag: " + duration(average), 14, MUTED, false));
        body.addView(details);
        gap(body, 14);
        renderWorkTime(period, now);
    }

    private void renderOverview() {
        long now = System.currentTimeMillis();
        VisitLogic.Period period = VisitLogic.period(now, offset);
        List<VisitStore.Visit> visits = store.visits(period);
        Map<String, Long> totals = new LinkedHashMap<>();
        Map<String, Long> nightlife = new LinkedHashMap<>();
        for (VisitStore.Visit visit : visits) {
            long included = period.overlap(visit.start(), visit.end());
            totals.merge(visit.category(), included, Long::sum);
            if (visit.category().equals(VisitLogic.BAR) || visit.category().equals(VisitLogic.RESTAURANT)) {
                nightlife.merge(visit.title(), included, Long::sum);
            }
        }
        addStatus(now);
        gap(body, 12);
        List<VisitLogic.Place> saved = store.places();
        if (saved.stream().noneMatch(p -> p.key().equals("work"))
                || saved.stream().noneMatch(p -> p.key().equals("home"))) {
            body.addView(button("Ställ in hem och jobb", false, () -> switchTab(Tab.PLACES)));
            gap(body, 12);
        }
        LinearLayout selector = horizontal();
        selector.setGravity(Gravity.CENTER_VERTICAL);
        TextView prev = button("‹", false, () -> shift(-1));
        prev.setContentDescription("Föregående period");
        selector.addView(prev, new LinearLayout.LayoutParams(dp(45), dp(46)));
        TextView range = label(periodLabel(period), 15, FG, true);
        range.setGravity(Gravity.CENTER);
        weight(selector, range);
        TextView next = button("›", false, () -> shift(1));
        next.setContentDescription("Nästa period");
        next.setEnabled(offset < 0);
        if (offset == 0) next.setAlpha(0.38f);
        selector.addView(next, new LinearLayout.LayoutParams(dp(45), dp(46)));
        body.addView(selector);
        gap(body, 12);

        LinearLayout first = horizontal();
        weight(first, stat("Jobb", workPeriodTotal(period), MINT));
        space(first, 9);
        weight(first, stat("Hemma", totals.getOrDefault(VisitLogic.HOME, 0L), BLUE));
        body.addView(first);
        gap(body, 18);
        gap(body, 9);
        LinearLayout second = horizontal();
        weight(second, stat("Krogar", totals.getOrDefault(VisitLogic.BAR, 0L), WARM));
        space(second, 9);
        weight(second, stat("Restauranger", totals.getOrDefault(VisitLogic.RESTAURANT, 0L), FG));
        body.addView(second);
        if (!nightlife.isEmpty()) {
            gap(body, 18);
            body.addView(label("Krogar och restauranger", 17, FG, true));
            gap(body, 6);
            LinearLayout split = card();
            for (Map.Entry<String, Long> item : nightlife.entrySet()) {
                LinearLayout line = horizontal();
                line.setGravity(Gravity.CENTER_VERTICAL);
                weight(line, label(item.getKey(), 14, FG, false));
                space(line, 8);
                line.addView(label(duration(item.getValue()), 14, WARM, true));
                split.addView(line);
                gap(split, 4);
            }
            body.addView(split);
        }

        gap(body, 21);
        LinearLayout heading = horizontal();
        heading.setGravity(Gravity.CENTER_VERTICAL);
        weight(heading, label("Besök", 22, FG, true));
        heading.addView(label(String.valueOf(visits.size()), 15, MUTED, true));
        body.addView(heading);
        gap(body, 9);
        if (visits.isEmpty()) {
            LinearLayout empty = card();
            empty.addView(label("Inga besök under perioden", 16, FG, true));
            gap(empty, 5);
            empty.addView(label("Ett besök visas när du har stannat längre än "
                    + store.visitMinutes() + " min.", 14, MUTED, false));
            body.addView(empty);
        }
        LocalDate lastDate = null;
        for (VisitStore.Visit visit : visits) {
            long shownStart = Math.max(visit.start(), period.start());
            LocalDate date = Instant.ofEpochMilli(shownStart).atZone(VisitLogic.SWEDEN).toLocalDate();
            if (!date.equals(lastDate)) {
                gap(body, 8);
                body.addView(label(date.format(DATE), 13, MUTED, true));
                gap(body, 7);
                lastDate = date;
            }
            addVisit(visit, period, now);
            gap(body, 8);
        }
    }

    private void renderDay() {
        LocalDate date = LocalDate.now(VisitLogic.SWEDEN).plusDays(dayOffset);
        VisitLogic.Period day = new VisitLogic.Period(date, date.plusDays(1));
        List<VisitStore.Visit> visits = store.visits(day);
        Map<String, Long> categories = new LinkedHashMap<>();
        for (VisitStore.Visit visit : visits) {
            long included = day.overlap(visit.start(), visit.end());
            categories.merge(visit.category(), included, Long::sum);
        }
        long total = categories.values().stream().mapToLong(Long::longValue).sum();

        LinearLayout selector = horizontal();
        selector.setGravity(Gravity.CENTER_VERTICAL);
        TextView prev = button("‹", false, () -> shiftDay(-1));
        prev.setContentDescription("Föregående dag");
        selector.addView(prev, new LinearLayout.LayoutParams(dp(45), dp(46)));
        String dateTitle = dateOffsetLabel(date, dayOffset);
        TextView heading = label(dateTitle, 16, FG, true);
        heading.setGravity(Gravity.CENTER);
        weight(selector, heading);
        TextView next = button("›", false, () -> shiftDay(1));
        next.setContentDescription("Nästa dag");
        next.setEnabled(dayOffset < 0);
        if (dayOffset == 0) next.setAlpha(0.38f);
        selector.addView(next, new LinearLayout.LayoutParams(dp(45), dp(46)));
        body.addView(selector);
        gap(body, 12);

        LinearLayout summary = card();
        summary.addView(label("Dagens sammanfattning", 18, FG, true));
        gap(summary, 7);
        summary.addView(label(visits.size() + " besök · " + duration(total) + " sammanlagt", 15, MINT, true));
        if (!categories.isEmpty()) {
            gap(summary, 5);
            List<String> lines = new ArrayList<>();
            for (Map.Entry<String, Long> item : categories.entrySet())
                lines.add(item.getKey() + ": " + duration(item.getValue()));
            summary.addView(label(TextUtils.join("  ·  ", lines), 13, MUTED, false));
        } else {
            gap(summary, 5);
            summary.addView(label("Besök längre än " + store.visitMinutes()
                    + " minuter visas här när registreringen är igång.", 13, MUTED, false));
        }
        gap(summary, 10);
        summary.addView(button("Dela dagsrapport", false, () -> shareDayReport(date, day, visits, total)));
        body.addView(summary);

        gap(body, 20);
        LinearLayout visitHeading = horizontal();
        visitHeading.setGravity(Gravity.CENTER_VERTICAL);
        weight(visitHeading, label("Besök under dagen", 21, FG, true));
        visitHeading.addView(label(String.valueOf(visits.size()), 14, MUTED, true));
        body.addView(visitHeading);
        gap(body, 9);
        if (visits.isEmpty()) {
            LinearLayout empty = card();
            empty.addView(label("Inga registrerade besök den här dagen", 15, FG, true));
            gap(empty, 5);
            empty.addView(label("Tryck på Starta på periodfliken för att registrera besök automatiskt.", 13, MUTED, false));
            body.addView(empty);
        }
        for (VisitStore.Visit visit : visits) {
            addVisit(visit, day, System.currentTimeMillis());
            gap(body, 8);
        }
    }

    private void shareDayReport(LocalDate date, VisitLogic.Period day,
                                List<VisitStore.Visit> visits, long total) {
        StringBuilder report = new StringBuilder("Besökslogg · ").append(date.format(DATE))
                .append("\n").append(visits.size()).append(" besök · ").append(duration(total));
        for (VisitStore.Visit visit : visits) {
            long start = Math.max(visit.start(), day.start());
            long end = Math.min(visit.end(), day.end());
            report.append("\n\n").append(visit.title()).append(" · ")
                    .append(visit.category()).append("\n")
                    .append(clock(start)).append("–").append(end == day.end() ? "00:00" : clock(end))
                    .append(" · ").append(duration(day.overlap(visit.start(), visit.end())));
            if (!visit.note().isEmpty()) report.append("\n").append(visit.note());
        }
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_SUBJECT, "Besökslogg " + date.format(DATE));
        share.putExtra(Intent.EXTRA_TEXT, report.toString());
        startActivity(Intent.createChooser(share, "Dela dagsrapport"));
    }

    private static String dateOffsetLabel(LocalDate date, int offset) {
        if (offset == 0) return "Idag · " + date.format(DATE);
        if (offset == -1) return "Igår · " + date.format(DATE);
        return date.format(DATE);
    }

    private void renderWorkTime(VisitLogic.Period period, long now) {
        LinearLayout work = card();
        LinearLayout heading = horizontal(); heading.setGravity(Gravity.CENTER_VERTICAL);
        weight(heading, label("Arbetstid", 19, FG, true));
        heading.addView(button("Fyll i dagens tider", true, this::editTodayWork));
        work.addView(heading);
        long shiftTotal = 0;
        List<VisitStore.WorkDay> days = store.workDays(period.from().toString(), period.until().toString());
        for (VisitStore.WorkDay day : days) shiftTotal += roundedShift(day.arrival(), day.departure());
        gap(work, 5);
        work.addView(label("Summa " + duration(shiftTotal) + " · avrundat uppåt till nästa halvtimme, högst 30 min extra per dag.", 13, MUTED, false));
        if (days.isEmpty()) {
            gap(work, 8); work.addView(label("Skriv in när du kommer och när du lämnar jobbet. Resor under dagen påverkar inte start- och sluttiden.", 14, MUTED, false));
        } else {
            gap(work, 8);
            for (VisitStore.WorkDay day : days) {
                LinearLayout line = horizontal(); line.setGravity(Gravity.CENTER_VERTICAL);
                LocalDate date = LocalDate.parse(day.date());
                weight(line, label(date.format(DATE), 13, FG, false));
                line.addView(label(String.format(SV, "%02d:%02d–%02d:%02d", day.arrival()/60, day.arrival()%60, day.departure()/60, day.departure()%60), 13, MUTED, false));
                space(line, 8);
                line.addView(label(duration(roundedShift(day.arrival(), day.departure())), 13, MINT, true));
                clickable(line, () -> editWorkDay(day), CARD);
                work.addView(line); gap(work, 5);
            }
        }
        body.addView(work);

        gap(body, 8);
        LinearLayout home = card();
        home.addView(label("Jobba hemifrån", 19, FG, true));
        long started = store.homeWorkStarted();
        gap(home, 5);
        home.addView(label(started == 0 ? "Starta klockan när du börjar jobba hemifrån." : "Klockan startade " + clock(started) + " · pågår " + duration(now-started), 14, MUTED, false));
        gap(home, 8);
        home.addView(button(started == 0 ? "Starta arbetsklocka" : "Stoppa arbetsklocka", started == 0,
                () -> { if (store.homeWorkStarted() == 0) store.startHomeWork(System.currentTimeMillis()); else store.stopHomeWork(System.currentTimeMillis()); render(); }));
        long homeTotal = 0;
        for (VisitStore.HomeWork session : store.homeWork(period.start(), period.end())) homeTotal += Math.max(0, Math.min(session.end(), period.end()) - Math.max(session.start(), period.start()));
        gap(home, 8); home.addView(label("Summa perioden: " + duration(homeTotal), 14, BLUE, true));
        body.addView(home);
    }

    private long workPeriodTotal(VisitLogic.Period period) {
        long total = 0;
        for (VisitStore.WorkDay day : store.workDays(period.from().toString(), period.until().toString()))
            total += roundedShift(day.arrival(), day.departure());
        for (VisitStore.HomeWork session : store.homeWork(period.start(), period.end()))
            total += Math.max(0, Math.min(session.end(), period.end()) - Math.max(session.start(), period.start()));
        return total;
    }

    private static long roundedShift(int arrival, int departure) {
        int minutes = departure - arrival;
        if (minutes < 0) minutes += 24 * 60;
        return ((minutes + 29L) / 30L) * 30L * VisitLogic.MINUTE;
    }

    private void editTodayWork() { editWorkDay(store.workDay(LocalDate.now(VisitLogic.SWEDEN).toString())); }

    private void editWorkDay(VisitStore.WorkDay current) {
        String date = current == null ? LocalDate.now(VisitLogic.SWEDEN).toString() : current.date();
        LinearLayout form = dialogBody();
        form.addView(label("Datum: " + LocalDate.parse(date).format(DATE), 14, MUTED, false)); gap(form, 9);
        form.addView(label("Tid du kommer", 14, MUTED, false));
        EditText arrival = input("HH:mm"); arrival.setInputType(InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_TIME);
        form.addView(arrival); gap(form, 8);
        form.addView(label("Senaste tid du lämnar", 14, MUTED, false));
        EditText departure = input("HH:mm"); departure.setInputType(InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_TIME);
        form.addView(departure);
        if (current != null) {
            arrival.setText(String.format(Locale.ROOT, "%02d:%02d", current.arrival()/60, current.arrival()%60));
            departure.setText(String.format(Locale.ROOT, "%02d:%02d", current.departure()/60, current.departure()%60));
        }
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Arbetstid").setView(form)
                .setNegativeButton("Avbryt", null).setPositiveButton("Spara", null).create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            int a = parseClock(arrival.getText().toString()), d = parseClock(departure.getText().toString());
            if (a < 0 || d < 0 || a == d) { message("Skriv båda tiderna som HH:mm, till exempel 08:00 och 17:00."); return; }
            store.saveWorkDay(date, a, d); dialog.dismiss(); render();
        });
    }

    private static int parseClock(String value) {
        try { String[] parts = value.trim().split(":"); if (parts.length != 2) return -1; int h = Integer.parseInt(parts[0]), m = Integer.parseInt(parts[1]); return h >= 0 && h < 24 && m >= 0 && m < 60 ? h*60+m : -1; }
        catch (RuntimeException e) { return -1; }
    }

    private void addStatus(long now) {
        LinearLayout status = card();
        LinearLayout line = horizontal();
        line.setGravity(Gravity.CENTER_VERTICAL);
        boolean active = TrackingService.running;
        String top = active ? "Registrering aktiv" : store.enabled() ? "Registrering avbruten" : "Registrering pausad";
        weight(line, label(top, 16, active ? MINT : FG, true));
        TextView action = button(active ? "Pausa" : "Starta", !active, active ? this::pause : this::askStart);
        line.addView(action);
        status.addView(line);
        String info = "Starta för att registrera besök även när skärmen är låst.";
        if (active) {
            long last = store.lastFix();
            if (!locationOn()) info = "Slå på plats i Android för att fortsätta.";
            else if (last == 0 || last > now || now - last > VisitLogic.MAX_GAP) info = "Väntar på en tillräckligt noggrann position.";
            else info = "Senaste position " + clock(last) + " · besök över " + store.visitMinutes() + " min";
        }
        gap(status, 5);
        status.addView(label(info, 13, MUTED, false));
        body.addView(status);
    }

    private void addVisit(VisitStore.Visit visit, VisitLogic.Period period, long now) {
        LinearLayout row = card();
        clickable(row, () -> editVisit(visit), CARD);
        LinearLayout top = horizontal();
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = label(visit.title(), 16, FG, true);
        weight(top, name);
        space(top, 8);
        top.addView(label(duration(period.overlap(visit.start(), visit.end())), 15,
                colorFor(visit.category()), true));
        row.addView(top);
        gap(row, 4);
        long start = Math.max(visit.start(), period.start());
        long end = Math.min(visit.end(), period.end());
        boolean continues = visit.ongoing() && TrackingService.running && end <= now
                && now - end <= VisitLogic.MAX_GAP && period.end() > now;
        String hours = clock(start) + "–" + (continues ? "pågår" : endLabel(start, end, period));
        row.addView(label((visit.category().equals(VisitLogic.OTHER) ? "" : visit.category() + " · ")
                + hours, 13, MUTED, false));
        if (!visit.address().isEmpty() && !visit.title().equals(visit.address())) {
            gap(row, 3);
            row.addView(label(visit.address(), 13, MUTED, false));
        }
        if (!visit.note().isEmpty()) {
            gap(row, 5);
            TextView note = label(visit.note(), 14, FG, false);
            note.setMaxLines(2);
            note.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(note);
        }
        body.addView(row);
    }

    private void renderSettings() {
        LinearLayout minimum = card();
        minimum.addView(label("Minsta besökstid", 19, FG, true));
        gap(minimum, 4);
        minimum.addView(label("När du stannat längre än " + store.visitMinutes()
                + " min sparas besöket från ankomst.", 14, MUTED, false));
        gap(minimum, 10);
        minimum.addView(button("Ändra minimitid", false, this::configureMinimum));
        body.addView(minimum);
        gap(body, 22);

        LinearLayout heading = horizontal();
        heading.setGravity(Gravity.CENTER_VERTICAL);
        weight(heading, label("Adressvarningar", 20, FG, true));
        heading.addView(button("Ny adress", true, () -> configureAlert(null)));
        body.addView(heading);
        gap(body, 8);
        List<VisitStore.AddressAlert> alerts = store.alerts();
        if (alerts.isEmpty()) {
            body.addView(label("Välj en adress och hur länge du vill stanna innan du får en varning.", 14, MUTED, false));
        }
        for (VisitStore.AddressAlert alert : alerts) {
            LinearLayout row = card();
            row.addView(label(alert.title(), 16, FG, true));
            if (!alert.name().isEmpty()) {
                gap(row, 4);
                row.addView(label(alert.address(), 13, MUTED, false));
            }
            gap(row, 4);
            row.addView(label("Över " + alert.minutes() + " min · radie " + alert.radius() + " m", 14, WARM, false));
            clickable(row, () -> configureAlert(alert), CARD);
            body.addView(row);
            gap(body, 8);
        }
        if (!alerts.isEmpty() && !addressNotifications.enabled()) {
            gap(body, 8);
            body.addView(button("Tillåt aviseringar", false, this::allowAlertNotifications));
        }
        if (!alerts.isEmpty() && !TrackingService.running) {
            gap(body, 6);
            body.addView(label("Starta registreringen i Översikt för att bevaka adresserna.", 14, MUTED, false));
        }
    }

    private void configureMinimum() {
        LinearLayout form = dialogBody();
        form.addView(label("Minuter (1–1 440)", 14, MUTED, false));
        EditText minutes = numberInput(store.visitMinutes());
        form.addView(minutes);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Minsta besökstid").setView(form)
                .setNegativeButton("Avbryt", null).setPositiveButton("Spara", null).create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            int amount = readMinutes(minutes);
            if (amount == 0) return;
            store.setVisitMinutes(amount);
            dialog.dismiss();
            render();
        });
    }

    private void configureAlert(VisitStore.AddressAlert current) {
        LinearLayout form = dialogBody();
        EditText name = input("Namn, valfritt");
        EditText address = input("Gatuadress och ort");
        if (current != null) {
            name.setText(current.name());
            address.setText(current.address());
        }
        form.addView(name);
        form.addView(address);
        gap(form, 10);
        form.addView(label("Varna efter antal minuter", 14, MUTED, false));
        EditText minutes = numberInput(current == null ? DEFAULT_ALERT_MINUTES : current.minutes());
        form.addView(minutes);
        gap(form, 10);
        form.addView(label("Radie i meter (50–500)", 14, MUTED, false));
        EditText radius = numberInput(current == null ? 150 : current.radius());
        form.addView(radius);
        gap(form, 13);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(current == null ? "Ny adressvarning" : "Redigera adressvarning")
                .setView(scrollForm(form)).setNegativeButton("Avbryt", null).create();
        form.addView(button(current == null ? "Sök adress" : "Spara", true, () -> {
            int amount = readMinutes(minutes);
            int meters = readRadius(radius);
            if (amount == 0 || meters == 0) return;
            String enteredName = name.getText().toString().trim();
            String query = address.getText().toString().trim();
            if (current != null && query.equals(current.address())) {
                saveAlert(current, enteredName, query, current.lat(), current.lon(), meters, amount, dialog);
            } else if (query.isEmpty()) {
                address.setError("Ange gatuadress och ort");
            } else {
                searchAddress(query, (result, resolved) -> saveAlert(current, enteredName, resolved,
                        result.getLatitude(), result.getLongitude(), meters, amount, dialog));
            }
        }));
        gap(form, 7);
        form.addView(button("Använd min position", false, () -> {
            int amount = readMinutes(minutes);
            int meters = readRadius(radius);
            if (amount == 0 || meters == 0) return;
            String enteredName = name.getText().toString().trim();
            withPrecise(() -> pickPosition(meters, fix -> saveAlert(current, enteredName,
                    coordinates(fix.getLatitude(), fix.getLongitude()), fix.getLatitude(),
                    fix.getLongitude(), meters, amount, dialog)));
        }));
        if (current != null) {
            gap(form, 7);
            form.addView(button("Ta bort varning", false, () -> {
                store.removeAlert(current.id());
                dialog.dismiss();
                render();
            }));
        }
        dialog.show();
    }

    private void saveAlert(VisitStore.AddressAlert current, String name, String address,
                           double lat, double lon, int meters, int minutes, AlertDialog dialog) {
        String id = current == null ? UUID.randomUUID().toString() : current.id();
        VisitLogic.Dwell dwell = VisitLogic.Dwell.EMPTY;
        for (VisitStore.AddressAlert saved : store.alerts()) {
            if (saved.id().equals(id) && saved.lat() == lat && saved.lon() == lon && saved.radius() == meters) {
                dwell = saved.dwell();
                break;
            }
        }
        store.putAlert(new VisitStore.AddressAlert(id, name, address, lat, lon, meters, minutes, dwell));
        dialog.dismiss();
        render();
        if (current == null && Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, ASK_NOTIFICATIONS);
        }
    }

    private void allowAlertNotifications() {
        if (Build.VERSION.SDK_INT >= 33
                && shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, ASK_NOTIFICATIONS);
        } else {
            startActivity(addressNotifications.settings());
        }
    }

    private void renderPlaces() {
        List<VisitLogic.Place> saved = store.places();
        addSavedPlace(VisitLogic.WORK, saved.stream().filter(p -> p.key().equals("work")).findFirst().orElse(null));
        gap(body, 10);
        addSavedPlace(VisitLogic.HOME, saved.stream().filter(p -> p.key().equals("home")).findFirst().orElse(null));
        gap(body, 17);
        body.addView(label("Andra sparade platser", 20, FG, true));
        gap(body, 8);
        boolean found = false;
        for (VisitLogic.Place place : saved) {
            if (!place.key().startsWith("poi-")) continue;
            found = true;
            LinearLayout row = card();
            row.addView(label(place.name().isEmpty() ? place.category() : place.name(), 16, FG, true));
            gap(row, 4);
            row.addView(label(place.address().isEmpty() ? place.category() : place.address(), 13, MUTED, false));
            clickable(row, () -> new AlertDialog.Builder(this).setTitle(place.name().isEmpty() ? place.category() : place.name())
                    .setMessage("Ta bort den sparade platsen")
                    .setNegativeButton("Avbryt", null).setPositiveButton("Ta bort", (d, w) -> {
                        store.removePlace(place.key());
                        render();
                    }).show(), CARD);
            body.addView(row);
            gap(body, 8);
        }
        if (!found) body.addView(label("Öppna ett besök för att spara platsens namn och kategori till nästa gång.", 14, MUTED, false));
        if (Build.VERSION.SDK_INT >= 29 && checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            gap(body, 20);
            body.addView(button("Starta automatiskt efter omstart", false, this::askBackground));
        }
    }

    private void addSavedPlace(String type, VisitLogic.Place place) {
        LinearLayout card = card();
        LinearLayout line = horizontal();
        line.setGravity(Gravity.CENTER_VERTICAL);
        weight(line, label(type, 19, colorFor(type), true));
        line.addView(button(place == null ? "Ställ in" : "Ändra", false, () -> configure(type, place)));
        card.addView(line);
        gap(card, 5);
        String details = place == null ? "Ingen adress vald"
                : (place.address().isEmpty() ? coordinates(place.lat(), place.lon()) : place.address())
                    + "\nRadie " + place.meters() + " m";
        card.addView(label(details, 14, MUTED, false));
        body.addView(card);
    }

    private void configure(String type, VisitLogic.Place current) {
        LinearLayout form = dialogBody();
        EditText address = input(type.equals(VisitLogic.WORK) ? "Gatuadress och ort, t.ex. Järfälla" : "Gatuadress och ort");
        if (current != null) address.setText(current.address());
        form.addView(address);
        gap(form, 9);
        form.addView(label("Radie runt platsen i meter (50–500)", 14, MUTED, false));
        EditText radius = numberInput(current == null ? 150 : current.meters());
        form.addView(radius);
        gap(form, 13);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(type).setView(form)
                .setNegativeButton("Avbryt", null).create();
        form.addView(button("Sök adress", true, () -> {
            int meters = readRadius(radius);
            String query = address.getText().toString().trim();
            if (meters == 0) return;
            if (query.isEmpty()) {
                address.setError("Ange gatuadress och ort");
                return;
            }
            searchAddress(query, (result, resolved) -> {
                savePlace(type, resolved, result.getLatitude(), result.getLongitude(), meters, true);
                dialog.dismiss();
            });
        }));
        gap(form, 7);
        form.addView(button("Använd min position", false, () -> {
            int meters = readRadius(radius);
            if (meters == 0) return;
            withPrecise(() -> usePosition(type, meters, dialog));
        }));
        if (current != null) {
            gap(form, 7);
            form.addView(button("Spara radie", false, () -> {
                int meters = readRadius(radius);
                if (meters == 0) return;
                savePlace(type, current.address(), current.lat(), current.lon(), meters, false);
                dialog.dismiss();
            }));
            gap(form, 7);
            form.addView(button("Ta bort plats", false, () -> {
                store.removePlace(current.key());
                dialog.dismiss();
                render();
            }));
        }
        dialog.show();
    }

    private int readRadius(EditText input) {
        return readNumber(input, 50, 500, "Ange 50 till 500 meter");
    }

    private int readMinutes(EditText input) {
        return readNumber(input, 1, 1440, "Ange 1 till 1 440 minuter");
    }

    private int readNumber(EditText input, int min, int max, String error) {
        try {
            int value = Integer.parseInt(input.getText().toString().trim());
            if (value >= min && value <= max) return value;
        } catch (NumberFormatException ignored) {}
        input.setError(error);
        return 0;
    }

    private void searchAddress(String query, BiConsumer<Address, String> picked) {
        AlertDialog progress = new AlertDialog.Builder(this).setMessage("Söker efter adressen…")
                .setNegativeButton("Avbryt", null).show();
        geocoding.submit(() -> {
            List<Address> result = new ArrayList<>();
            for (Address address : AddressLookup.search(this, query)) {
                if (address.hasLatitude() && address.hasLongitude()) result.add(address);
            }
            runOnUiThread(() -> {
                if (isDestroyed() || !progress.isShowing()) return;
                progress.dismiss();
                if (result.isEmpty()) {
                    message("Hittade ingen adress. Kontrollera adressen eller välj Använd min position när du är där.");
                    return;
                }
                String[] labels = result.stream().map(AddressLookup::line).toArray(String[]::new);
                for (int i = 0; i < labels.length; i++) {
                    if (labels[i].isEmpty()) {
                        labels[i] = coordinates(result.get(i).getLatitude(), result.get(i).getLongitude());
                    }
                }
                new AlertDialog.Builder(this).setTitle("Välj adress")
                        .setItems(labels, (d, selected) -> picked.accept(result.get(selected), labels[selected]))
                        .setNegativeButton("Avbryt", null).show();
            });
        });
    }

    private void usePosition(String type, int meters, AlertDialog owner) {
        pickPosition(meters, fix -> {
            double lat = fix.getLatitude();
            double lon = fix.getLongitude();
            savePlace(type, "", lat, lon, meters, true);
            owner.dismiss();
            geocoding.submit(() -> {
                String resolved = AddressLookup.line(AddressLookup.reverse(this, lat, lon));
                runOnUiThread(() -> {
                    if (isDestroyed() || resolved.isEmpty()) return;
                    String key = placeKey(type);
                    for (VisitLogic.Place place : store.places()) {
                        if (place.key().equals(key) && place.lat() == lat && place.lon() == lon
                                && place.address().isEmpty()) {
                            store.putPlace(new VisitLogic.Place(key, type, type, resolved, lat, lon, place.meters()));
                            render();
                            break;
                        }
                    }
                });
            });
        });
    }

    private void pickPosition(int meters, Consumer<Location> picked) {
        if (!locationOn()) {
            showLocationOff();
            return;
        }
        clearPositionRequest();
        LocationManager manager = getSystemService(LocationManager.class);
        positionDialog = new AlertDialog.Builder(this).setMessage("Hämtar din position…")
                .setNegativeButton("Avbryt", (d, i) -> clearPositionRequest()).show();
        positionDialog.setOnCancelListener(d -> clearPositionRequest());
        positionListener = new LocationListener() {
            @Override public void onLocationChanged(Location fix) {
                if (!goodForPlace(fix, meters)) return;
                clearPositionRequest();
                picked.accept(fix);
            }
            @Override public void onProviderEnabled(String p) {}
            @Override public void onProviderDisabled(String p) {}
            @Override public void onStatusChanged(String p, int state, Bundle extras) {}
        };
        positionTimeout = () -> {
            clearPositionRequest();
            message("Ingen tillräckligt noggrann position. Försök igen eller sök på adressen.");
        };
        handler.postDelayed(positionTimeout, 35_000);
        for (String provider : new String[]{"fused", LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
            try {
                if (!manager.isProviderEnabled(provider)) continue;
                Location cached = manager.getLastKnownLocation(provider);
                if (cached != null && goodForPlace(cached, meters)) {
                    positionListener.onLocationChanged(cached);
                    return;
                }
                manager.requestLocationUpdates(provider, 1000, 0f, positionListener, Looper.getMainLooper());
            } catch (IllegalArgumentException | SecurityException ignored) {}
        }
    }

    private static boolean goodForPlace(Location fix, int meters) {
        long age = SystemClock.elapsedRealtimeNanos() - fix.getElapsedRealtimeNanos();
        return age >= 0 && age <= 35_000_000_000L && fix.hasAccuracy()
                && fix.getAccuracy() > 0 && fix.getAccuracy() <= Math.min(55, meters * 0.65);
    }

    private void clearPositionRequest() {
        if (positionTimeout != null) handler.removeCallbacks(positionTimeout);
        positionTimeout = null;
        if (positionListener != null) {
            try {
                getSystemService(LocationManager.class).removeUpdates(positionListener);
            } catch (SecurityException ignored) {}
            positionListener = null;
        }
        if (positionDialog != null) {
            positionDialog.dismiss();
            positionDialog = null;
        }
    }

    private static String placeKey(String type) {
        return type.equals(VisitLogic.WORK) ? "work" : "home";
    }

    private void savePlace(String type, String address, double lat, double lon, int meters, boolean notify) {
        store.putPlace(new VisitLogic.Place(placeKey(type), type, type, address, lat, lon, meters));
        if (notify) message(type + " har sparats.");
        render();
    }

    private void editVisit(VisitStore.Visit visit) {
        LinearLayout form = dialogBody();
        String location = visit.address().isEmpty() ? coordinates(visit.lat(), visit.lon()) : visit.address();
        form.addView(label(location, 14, MUTED, false));
        gap(form, 8);
        EditText title = input("Platsens namn");
        title.setText(visit.name());
        form.addView(title);
        gap(form, 8);
        form.addView(label("Kategori", 14, MUTED, false));
        Spinner category = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, CATEGORIES);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        category.setAdapter(adapter);
        for (int i = 0; i < CATEGORIES.length; i++) {
            if (CATEGORIES[i].equals(visit.category())) category.setSelection(i);
        }
        form.addView(category, new LinearLayout.LayoutParams(-1, dp(47)));
        CheckBox remember = new CheckBox(this);
        remember.setText("Kom ihåg platsen för framtida besök");
        form.addView(remember);
        category.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int index, long id) {
                String type = CATEGORIES[index];
                boolean canRemember = !type.equals(VisitLogic.WORK) && !type.equals(VisitLogic.HOME);
                remember.setEnabled(canRemember);
                if (!canRemember) remember.setChecked(false);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        gap(form, 8);
        form.addView(label("Anteckning", 14, MUTED, false));
        EditText note = input("Vad gjorde du här?");
        note.setSingleLine(false);
        note.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        note.setMinLines(3);
        note.setGravity(Gravity.TOP | Gravity.START);
        note.setText(visit.note());
        form.addView(note);
        gap(form, 7);
        form.addView(button("Visa på karta", false, () -> openMap(visit)));
        new AlertDialog.Builder(this).setTitle("Redigera besök").setView(scrollForm(form))
                .setNegativeButton("Avbryt", null).setPositiveButton("Spara", (d, i) -> {
                    String type = (String) category.getSelectedItem();
                    String name = title.getText().toString().trim();
                    if (name.isEmpty() && !type.equals(visit.category()) && !type.equals(VisitLogic.OTHER)) {
                        name = type;
                    }
                    store.editVisit(visit, type, name, remember.isChecked(), note.getText().toString().trim());
                    render();
                }).show();
    }

    private void openMap(VisitStore.Visit visit) {
        String pos = visit.lat() + "," + visit.lon();
        Intent intent = new Intent(Intent.ACTION_VIEW,
                Uri.parse("geo:" + pos + "?q=" + pos + "(" + Uri.encode(visit.title()) + ")"));
        try {
            startActivity(intent);
        } catch (android.content.ActivityNotFoundException e) {
            message("Ingen kartapp installerad. " + coordinates(visit.lat(), visit.lon()));
        }
    }

    private void askStart() {
        withPrecise(() -> startTracking(true));
    }

    private void withPrecise(Runnable action) {
        if (TrackingService.hasPrecise(this)) {
            action.run();
        } else {
            onPreciseGranted = action;
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, ASK_LOCATION);
        }
    }

    private void startTracking(boolean askNotifications) {
        if (!locationOn()) {
            showLocationOff();
            return;
        }
        boolean alreadyEnabled = store.enabled();
        store.setEnabled(true);
        try {
            startForegroundService(new Intent(this, TrackingService.class));
        } catch (RuntimeException failure) {
            store.setEnabled(alreadyEnabled);
            message("Registreringen kunde inte starta. Öppna appen och försök igen.");
            return;
        }
        handler.postDelayed(this::render, 400);
        if (askNotifications && Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, ASK_NOTIFICATIONS);
        }
    }

    private boolean locationOn() {
        LocationManager manager = getSystemService(LocationManager.class);
        if (Build.VERSION.SDK_INT >= 28) return manager.isLocationEnabled();
        return manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
    }

    private void showLocationOff() {
        new AlertDialog.Builder(this).setTitle("Plats är avstängd")
                .setMessage("Aktivera plats i Android för att registrera besök.")
                .setNegativeButton("Avbryt", null).setPositiveButton("Öppna platsinställningar",
                        (d, i) -> startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))).show();
    }

    private void pause() {
        store.setEnabled(false);
        store.writeTracking(store.tracker(), null);
        store.resetDwells();
        stopService(new Intent(this, TrackingService.class));
        handler.postDelayed(this::render, 250);
    }

    private void askBackground() {
        withPrecise(() -> {
            if (Build.VERSION.SDK_INT >= 30) {
                String option = getPackageManager().getBackgroundPermissionOptionLabel().toString();
                new AlertDialog.Builder(this).setTitle("Registrering efter omstart")
                        .setMessage("Välj ”" + option + "” under Behörigheter → Plats för att Besöksloggen ska kunna starta igen efter omstart.")
                        .setNegativeButton("Avbryt", null).setPositiveButton("Öppna inställningar",
                                (d, i) -> appSettings()).show();
            } else if (Build.VERSION.SDK_INT >= 29) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, ASK_BACKGROUND);
            }
        });
    }

    private void appSettings() {
        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request == ASK_LOCATION) {
            Runnable action = onPreciseGranted;
            onPreciseGranted = null;
            if (TrackingService.hasPrecise(this)) {
                if (action != null) action.run();
            } else {
                new AlertDialog.Builder(this).setTitle("Exakt position behövs")
                        .setMessage("Aktivera Exakt plats för att kunna skilja adresser som ligger nära varandra.")
                        .setNegativeButton("Stäng", null)
                        .setPositiveButton("Öppna inställningar", (d, i) -> appSettings()).show();
            }
        }
        render();
    }

    private void shift(int delta) {
        offset = Math.min(0, offset + delta);
        scroll.scrollTo(0, 0);
        render();
    }

    private void shiftDay(int delta) {
        dayOffset = Math.min(0, dayOffset + delta);
        scroll.scrollTo(0, 0);
        render();
    }

    private static String periodLabel(VisitLogic.Period period) {
        String left = period.from().format(period.from().getYear() == period.until().getYear() ? SHORT_DATE : DATE);
        return left + " – " + period.until().format(DATE);
    }

    private static String clock(long at) {
        return CLOCK.format(Instant.ofEpochMilli(at).atZone(VisitLogic.SWEDEN));
    }

    private static String endLabel(long start, long end, VisitLogic.Period period) {
        if (end == period.end()) return period.until().getDayOfMonth() == 20
                ? "20:e 00:00" : "00:00";
        LocalDate from = Instant.ofEpochMilli(start).atZone(VisitLogic.SWEDEN).toLocalDate();
        LocalDate until = Instant.ofEpochMilli(end).atZone(VisitLogic.SWEDEN).toLocalDate();
        return from.equals(until) ? clock(end) : until.format(SHORT_DATE) + " " + clock(end);
    }

    private static String duration(long millis) {
        long min = millis / VisitLogic.MINUTE;
        if (min < 60) return min + " min";
        return min / 60 + " h " + min % 60 + " min";
    }

    private static String coordinates(double lat, double lon) {
        return String.format(Locale.ROOT, "%.5f, %.5f", lat, lon);
    }

    private static int colorFor(String category) {
        if (category.equals(VisitLogic.WORK)) return MINT;
        if (category.equals(VisitLogic.HOME)) return BLUE;
        if (category.equals(VisitLogic.BAR)) return WARM;
        return FG;
    }

    private LinearLayout stat(String title, long millis, int accent) {
        LinearLayout panel = card();
        panel.addView(label(title, 14, accent, true));
        gap(panel, 7);
        panel.addView(label(duration(millis), 23, FG, true));
        return panel;
    }

    private LinearLayout card() {
        LinearLayout view = vertical();
        view.setPadding(dp(14), dp(12), dp(14), dp(12));
        view.setBackground(shape(CARD, BORDER, 16));
        return view;
    }

    private LinearLayout dialogBody() {
        LinearLayout view = vertical();
        view.setPadding(dp(21), dp(12), dp(21), dp(7));
        return view;
    }

    private ScrollView scrollForm(LinearLayout form) {
        ScrollView panel = new ScrollView(this);
        panel.addView(form);
        return panel;
    }

    private EditText input(String hint) {
        EditText text = new EditText(this);
        text.setSingleLine();
        text.setHint(hint);
        text.setTextColor(FG);
        text.setHintTextColor(MUTED);
        return text;
    }

    private EditText numberInput(int value) {
        EditText text = input("");
        text.setInputType(InputType.TYPE_CLASS_NUMBER);
        text.setText(String.valueOf(value));
        return text;
    }

    private TextView button(String text, boolean primary, Runnable action) {
        TextView view = label(text, 14, primary ? DARK : FG, true);
        view.setGravity(Gravity.CENTER);
        view.setMinHeight(dp(44));
        view.setPadding(dp(12), dp(4), dp(12), dp(4));
        clickable(view, action, primary ? MINT : CARD);
        return view;
    }

    private void clickable(View view, Runnable action, int fill) {
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x3388c1ce),
                shape(fill, fill == MINT ? MINT : BORDER, 14), null));
        view.setOnClickListener(v -> action.run());
        view.setFocusable(true);
    }

    private GradientDrawable shape(int fill, int stroke, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radius));
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private TextView label(String text, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        return view;
    }

    private LinearLayout vertical() {
        LinearLayout result = new LinearLayout(this);
        result.setOrientation(LinearLayout.VERTICAL);
        return result;
    }

    private LinearLayout horizontal() {
        return new LinearLayout(this);
    }

    private void weight(LinearLayout parent, View view) {
        parent.addView(view, new LinearLayout.LayoutParams(0, -2, 1));
    }

    private void gap(LinearLayout parent, int height) {
        parent.addView(new View(this), new LinearLayout.LayoutParams(1, dp(height)));
    }

    private void space(LinearLayout parent, int width) {
        parent.addView(new View(this), new LinearLayout.LayoutParams(dp(width), 1));
    }

    private int dp(int i) {
        return Math.round(i * getResources().getDisplayMetrics().density);
    }

    private void message(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }
}
