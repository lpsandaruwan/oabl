package com.softiva.oabl;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** Coordinates from the address shapes Google Maps shares or redirects to. */
public class PlaceShareTest {
    @Test public void placePointWinsOverTheMapView() {
        String url = "https://www.google.com/maps/place/Galle+Face+Green/@6.9269,79.8430,17z/data=!3m1!4b1!4m6!3m5!1s0x0:0x0!8m2!3d6.9271!4d79.8456!16s";
        assertArrayEquals(new double[]{6.9271, 79.8456}, PlaceShareActivity.coordinates(url), 1e-9);
    }

    @Test public void mapViewCentreWhenThereIsNoPlace() {
        assertArrayEquals(new double[]{6.9269, 79.843}, PlaceShareActivity.coordinates("https://www.google.com/maps/@6.9269,79.8430,15z"), 1e-9);
    }

    @Test public void queryAndGeoForms() {
        assertArrayEquals(new double[]{7.2906, 80.6337}, PlaceShareActivity.coordinates("https://maps.google.com/?q=7.2906,80.6337"), 1e-9);
        assertArrayEquals(new double[]{7.2906, 80.6337}, PlaceShareActivity.coordinates("https://maps.google.com/maps?ll=7.2906%2C80.6337&z=16"), 1e-9);
        assertArrayEquals(new double[]{-33.8568, 151.2153}, PlaceShareActivity.coordinates("geo:-33.8568,151.2153?q=Opera"), 1e-9);
    }

    @Test public void shortLinkAloneHasNoCoordinates() {
        assertNull(PlaceShareActivity.coordinates("Galle Face Green\nColombo\nhttps://maps.app.goo.gl/AbCdEf"));
        assertNull(PlaceShareActivity.coordinates("https://maps.google.com/?q=0,0"));
    }
}
