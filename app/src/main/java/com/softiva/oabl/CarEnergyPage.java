package com.softiva.oabl;

import android.content.Context;
import java.util.EnumMap;
import java.util.List;

/**
 * Everything the live Energy page shows, read by :energy in one request, so the page draws without
 * calling :energy on the UI thread. Method names match the link, repository and store calls it
 * replaces; anything that changes data still goes to the real objects.
 */
final class CarEnergyPage implements java.io.Serializable {
    private static final long serialVersionUID = 1L;
    private final EnumMap<CarSignal, Double> values = new EnumMap<>(CarSignal.class);
    private boolean car, fresh, charging;
    private long socAge;
    private CarEnergyTracker.Event drive, charge;
    private CarEnergySummary stops, total;
    private List<CarEnergyRepository.Item> items;
    private CarEnergyRepository.Item pending;
    /** A finished charge to prompt for now: the car is stationary and not charging. */
    CarEnergyRepository.Item prompt;
    private CarCharger site;
    private double siteRate = Double.NaN, homeRate = Double.NaN;
    private String currency = "";
    private EnergyModel.Profile profile;
    private List<EnergyModel.Charge> charges;
    EnergyPeriods.Period period;

    static CarEnergyPage capture(Context context, int tab) {
        if (!EnergyClient.owner(context)) return (CarEnergyPage) EnergyClient.call(context, "energy.page", tab);
        CarEnergyPage page = new CarEnergyPage();
        CarLinkController link = CarLinkController.get(context);
        EnergyStore store = EnergyStore.get(context);
        CarEnergyRepository repository = new CarEnergyRepository(store);
        page.car = link.expectsCarBattery(); page.fresh = link.hasFreshLive(); page.charging = link.liveCharging();
        page.socAge = link.liveAgeSeconds(CarSignal.SOC);
        for (CarSignal signal : CarSignal.values()) page.values.put(signal, link.liveValue(signal));
        page.values.put(CarSignal.SPEED, link.liveSpeed());
        page.charging = page.charging && !(page.liveValue(CarSignal.SPEED) > 1) && page.liveValue(CarSignal.PLUG) != 0;
        page.profile = store.profile(); page.homeRate = store.homeRate(); page.currency = store.currency(); page.charges = store.charges();
        page.pending = repository.pending(false);
        double speed = page.liveValue(CarSignal.SPEED);
        if (page.pending != null && speed >= 0 && speed <= 1 && !page.charging) page.prompt = repository.pending(true);
        if (tab == EnergyActivity.DRIVE) {
            page.drive = repository.latestDrive();
            page.stops = repository.currentStops(page.drive == null ? 0 : page.drive.start, page.drive == null ? Long.MAX_VALUE : page.drive.last);
            if (page.charging) {
                page.charge = repository.openCharge();
                if (page.charge != null) {
                    page.site = repository.charger(page.charge, false, 0, false);
                    if (page.site.id > 0 && !page.site.home) page.siteRate = repository.chargerRate(page.charge, false, page.site.id);
                }
            }
        } else {
            long now = System.currentTimeMillis();
            java.util.TimeZone zone = java.util.TimeZone.getDefault(); java.util.Locale locale = java.util.Locale.getDefault();
            page.period = tab == EnergyActivity.TODAY ? EnergyPeriods.today(now, zone, locale) : tab == EnergyActivity.WEEK ? EnergyPeriods.week(now, zone, locale)
                    : tab == EnergyActivity.MONTH ? EnergyPeriods.month(now, zone, locale) : new EnergyPeriods.Period(0, Long.MAX_VALUE, "All time");
            page.total = repository.summary(page.period.start, page.period.end);
            page.items = repository.events(page.period.start, page.period.end, tab == EnergyActivity.CHARGING ? "charge" : null, 100);
        }
        return page;
    }

    // ---- As CarLinkController ----
    boolean expectsCarBattery() { return car; }
    boolean hasFreshLive() { return fresh; }
    boolean liveCharging() { return charging; }
    long liveAgeSeconds(CarSignal signal) { return socAge; }
    double liveValue(CarSignal signal) { Double value = values.get(signal); return value == null ? Double.NaN : value; }
    double liveSoc() { return liveValue(CarSignal.SOC); }
    double liveRangeKm() { return liveValue(CarSignal.RANGE_EST); }

    // ---- As CarEnergyRepository (reads only) ----
    CarEnergyTracker.Event latestDrive() { return drive; }
    CarEnergyTracker.Event openCharge() { return charge; }
    CarEnergySummary currentStops(long from, long to) { return stops == null ? new CarEnergySummary() : stops; }
    CarEnergySummary summary(long from, long to) { return total == null ? new CarEnergySummary() : total; }
    List<CarEnergyRepository.Item> events(long from, long to, String kind, int limit) { return items == null ? java.util.Collections.emptyList() : items; }
    CarEnergyRepository.Item pending(boolean automatic) { return pending; }
    CarCharger charger(CarEnergyTracker.Event event, boolean home, long selected, boolean create) { return site == null ? new CarCharger() : site; }
    double chargerRate(CarEnergyTracker.Event event, boolean home, long selected) { return home ? homeRate : siteRate; }

    // ---- As EnergyStore (reads only) ----
    EnergyModel.Profile profile() { return profile; }
    double homeRate() { return homeRate; }
    String currency() { return currency; }
    List<EnergyModel.Charge> charges() { return charges; }
}
