package se.besoksloggen;

import android.content.Context;
import android.location.Address;
import android.location.Geocoder;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

final class AddressLookup {
    private static final Locale SWEDISH = Locale.forLanguageTag("sv-SE");

    static List<Address> search(Context context, String query) {
        if (!Geocoder.isPresent()) return Collections.emptyList();
        try {
            List<Address> found = new Geocoder(context, SWEDISH).getFromLocationName(query, 5);
            return found == null ? Collections.emptyList() : found;
        } catch (IOException | RuntimeException e) {
            return Collections.emptyList();
        }
    }

    static Address reverse(Context context, double lat, double lon) {
        if (!Geocoder.isPresent()) return null;
        try {
            List<Address> found = new Geocoder(context, SWEDISH).getFromLocation(lat, lon, 1);
            return found == null || found.isEmpty() ? null : found.get(0);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    static String line(Address address) {
        if (address == null) return "";
        if (address.getMaxAddressLineIndex() >= 0 && address.getAddressLine(0) != null) {
            return address.getAddressLine(0);
        }
        String street = address.getThoroughfare();
        String town = address.getLocality();
        if (street == null) return town == null ? "" : town;
        return town == null || town.equalsIgnoreCase(street) ? street : street + ", " + town;
    }

    static String venue(Address address) {
        if (address == null) return "";
        String feature = address.getFeatureName();
        if (feature == null || feature.trim().isEmpty() || feature.length() < 3) return "";
        if (feature.equalsIgnoreCase(address.getThoroughfare())
                || feature.equalsIgnoreCase(address.getSubThoroughfare())
                || feature.equalsIgnoreCase(address.getLocality())
                || feature.equalsIgnoreCase(address.getPostalCode())
                || feature.equalsIgnoreCase(address.getSubLocality())
                || feature.equalsIgnoreCase(address.getAdminArea())
                || feature.equalsIgnoreCase(address.getSubAdminArea())
                || feature.equalsIgnoreCase(address.getCountryName())) return "";
        if (feature.matches(".*\\d.*")) return "";
        return feature;
    }

    static String category(String venue) {
        String name = venue.toLowerCase(SWEDISH);
        if (name.matches(".*\\b(pub|krog|cocktailbar|ölbar|sportbar|bar)\\b.*")) return VisitLogic.BAR;
        if (name.matches(".*\\b(restaurang|restaurant|pizzeria|bistro)\\b.*")) {
            return VisitLogic.RESTAURANT;
        }
        return VisitLogic.OTHER;
    }
}
