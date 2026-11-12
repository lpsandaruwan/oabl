package com.softiva.oabl;

import org.json.JSONObject;

/** Persistent reducer state; raw observations remain available independently. */
final class CarEnergyCodec {
    static JSONObject frame(CarEnergyTracker.Frame frame) throws Exception {
        JSONObject result = new JSONObject().put("time", frame.time).put("kw", frame.idleKw).put("run",frame.run);
        if(Double.isFinite(frame.latitude))result.put("latitude",frame.latitude);if(Double.isFinite(frame.longitude))result.put("longitude",frame.longitude);
        if (Double.isFinite(frame.capacity)) result.put("capacity", frame.capacity);
        JSONObject values = new JSONObject(), times = new JSONObject();
        for (CarSignal signal : frame.values.keySet()) { values.put(signal.key, frame.values.get(signal)); times.put(signal.key, frame.times.get(signal)); }
        return result.put("values", values).put("times", times);
    }
    static CarEnergyTracker.Frame frame(JSONObject data) throws Exception {
        CarEnergyTracker.Frame frame = new CarEnergyTracker.Frame(); frame.time = data.getLong("time");
        frame.idleKw = data.optDouble("kw", 0); frame.capacity = data.optDouble("capacity", Double.NaN);
        frame.run=data.optString("run","");frame.latitude=data.optDouble("latitude",Double.NaN);frame.longitude=data.optDouble("longitude",Double.NaN);
        JSONObject values = data.getJSONObject("values"), times = data.getJSONObject("times");
        for (CarSignal signal : CarSignal.values()) if (values.has(signal.key) && CarSignal.plausible(signal, values.getDouble(signal.key))) { frame.values.put(signal, values.getDouble(signal.key)); frame.times.put(signal, times.getLong(signal.key)); }
        return frame;
    }
    static JSONObject event(CarEnergyTracker.Event event) throws Exception {
        JSONObject data = new JSONObject().put("key", event.key).put("kind", event.kind).put("state", event.state)
                .put("start", event.start).put("end", event.end).put("last", event.last).put("km", event.km)
                .put("moving", event.movingSeconds).put("stopped", event.stoppedSeconds).put("model", event.modelKwh).put("peak",event.peakSpeed)
                .put("external_charge", event.externalCharge).put("inferred", event.inferred).put("gap", event.gap).put("off", event.offSeen).put("ac", event.acSeen).put("distance_source", event.distanceSource)
                .put("ac_seconds", event.acSeconds).put("regen_kwh", event.regenKwh);
        if (Double.isFinite(event.before)) data.put("before", event.before);
        if (Double.isFinite(event.after)) data.put("after", event.after);
        if (Double.isFinite(event.capacity)) data.put("capacity", event.capacity);
        data.put("gap_seconds",event.gapSeconds).put("gap_km",event.gapKm).put("gap_stop_seconds",event.gapStopSeconds)
                .put("charger_kwh",event.chargerKwh).put("covered_seconds",event.coveredSeconds).put("odometer_flag",event.odometerFlag).put("joinable",event.joinable)
                .put("temperature_sum",event.temperatureSum).put("temperature_seconds",event.temperatureSeconds);
        String[] keys={"trip_km","trip_kwh100","trip_c_km","trip_c_kwh100","range_start","latitude","longitude","trip_start_km","trip_start_kwh100"};
        double[] numbers={event.tripKm,event.tripKwh100,event.tripCKm,event.tripCKwh100,event.rangeStart,event.latitude,event.longitude,event.tripStartKm,event.tripStartKwh100};
        for(int i=0;i<keys.length;i++)if(Double.isFinite(numbers[i]))data.put(keys[i],numbers[i]);
        data.put("temperature_km",new org.json.JSONArray(event.temperatureKm)).put("temperature_points",new org.json.JSONArray(event.temperaturePoints));
        return data;
    }
    static CarEnergyTracker.Event event(JSONObject data) throws Exception {
        CarEnergyTracker.Event event = new CarEnergyTracker.Event(); event.key = data.getString("key"); event.kind = data.getString("kind"); event.state = data.getString("state");
        event.start = data.getLong("start"); event.end = data.getLong("end"); event.last = data.getLong("last");
        event.km = data.getDouble("km"); event.movingSeconds = data.getDouble("moving"); event.stoppedSeconds = data.getDouble("stopped"); event.modelKwh = data.getDouble("model");
        event.peakSpeed=data.optDouble("peak",0);
        event.before = data.optDouble("before", Double.NaN); event.after = data.optDouble("after", Double.NaN); event.capacity = data.optDouble("capacity", Double.NaN);
        event.inferred = data.optBoolean("inferred");
        // Legacy inferred rises have no stationary/plug proof and remain excluded from billing.
        event.externalCharge = data.optBoolean("external_charge", !event.inferred); event.gap = data.optBoolean("gap"); event.offSeen = data.optBoolean("off"); event.acSeen = data.optBoolean("ac"); event.distanceSource = data.optString("distance_source", "car odometer");
        event.acSeconds = data.optDouble("ac_seconds", 0); event.regenKwh = data.optDouble("regen_kwh", 0);
        event.gapSeconds=data.optDouble("gap_seconds",0);event.gapKm=data.optDouble("gap_km",0);event.gapStopSeconds=data.optDouble("gap_stop_seconds",0);
        event.chargerKwh=data.optDouble("charger_kwh",0);event.coveredSeconds=data.optDouble("covered_seconds",0);event.odometerFlag=data.optBoolean("odometer_flag");event.joinable=data.optBoolean("joinable");
        event.tripKm=data.optDouble("trip_km",Double.NaN);event.tripKwh100=data.optDouble("trip_kwh100",Double.NaN);
        event.tripCKm=data.optDouble("trip_c_km",Double.NaN);event.tripCKwh100=data.optDouble("trip_c_kwh100",Double.NaN);
        event.tripStartKm=data.optDouble("trip_start_km",Double.NaN);event.tripStartKwh100=data.optDouble("trip_start_kwh100",Double.NaN);
        event.rangeStart=data.optDouble("range_start",Double.NaN);event.latitude=data.optDouble("latitude",Double.NaN);event.longitude=data.optDouble("longitude",Double.NaN);
        event.temperatureSum=data.optDouble("temperature_sum",0);event.temperatureSeconds=data.optDouble("temperature_seconds",0);
        org.json.JSONArray km=data.optJSONArray("temperature_km"),points=data.optJSONArray("temperature_points");
        for(int i=0;i<6;i++){event.temperatureKm[i]=km==null?0:km.optDouble(i,0);event.temperaturePoints[i]=points==null?0:points.optDouble(i,0);}
        return event;
    }
    static String state(CarEnergyTracker.State state) throws Exception {
        JSONObject data = new JSONObject();
        if (state.last != null) data.put("last", frame(state.last));
        if (state.drive != null) data.put("drive", event(state.drive));
        if (state.stop != null) data.put("stop", event(state.stop));
        if (state.charge != null) data.put("charge", event(state.charge));
        if (state.plug != null) data.put("plug", event(state.plug));
        if (state.still != null) { data.put("still", event(state.still)); if (Double.isFinite(state.still.startOdometer)) data.put("still_odometer", state.still.startOdometer); }
        if (Double.isFinite(state.tripCKm)) data.put("trip_c_km", state.tripCKm);
        if (Double.isFinite(state.tripCKwh100)) data.put("trip_c_kwh100", state.tripCKwh100);
        if (state.slowingFor > 0) data.put("slowing_for", state.slowingFor);
        return data.toString();
    }
    static CarEnergyTracker.State state(String text) throws Exception {
        CarEnergyTracker.State state = new CarEnergyTracker.State(); if (text == null) return state;
        JSONObject data = new JSONObject(text);
        if (data.has("last")) state.last = frame(data.getJSONObject("last"));
        if (data.has("drive")) state.drive = event(data.getJSONObject("drive"));
        if (data.has("stop")) state.stop = event(data.getJSONObject("stop"));
        if (data.has("charge")) state.charge = event(data.getJSONObject("charge"));
        if (data.has("plug")) state.plug = event(data.getJSONObject("plug"));
        if (data.has("still")) { state.still = event(data.getJSONObject("still")); state.still.startOdometer = data.optDouble("still_odometer", Double.NaN); }
        state.tripCKm = data.optDouble("trip_c_km", Double.NaN); state.tripCKwh100 = data.optDouble("trip_c_kwh100", Double.NaN);
        state.slowingFor = data.optDouble("slowing_for", 0);
        return state;
    }
}
