package com.automation.rater;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * The address of a case's location, looked up at run time: a real street address inside the case's ZIP, County and State
 * (test data), with its city and coordinates. The ZIP's centre is found first ("zip, State, USA"), then the nearest
 * addresses around it (reverse lookup) until one lies in the same ZIP, State and County and has a house number.
 * A test data "Request:LocationFullAddress" is used as given (only its coordinates are looked up).
 * When no address is found the location falls back to "COUNTY, ST zip, United States" at the ZIP centre.
 * Results are cached in a file (rater.geocode.cache), so each location is looked up once; the service (rater.geocode.url /
 * rater.geocode.reverseUrl, default OpenStreetMap Nominatim) allows one request a second.
 */
@Slf4j
public class RaterGeocoder {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** Reverse lookups around the ZIP centre: the centre, then 8 points about 400 m away. */
    private static final double[][] AROUND = {{0, 0}, {0.004, 0}, {-0.004, 0}, {0, 0.004}, {0, -0.004},
        {0.004, 0.004}, {-0.004, -0.004}, {0.004, -0.004}, {-0.004, 0.004}};

    /**
     * A resolved location: LocationFullAddress, Latitude, Longitude, the street / city of a fetched address and the County
     * to send (the spelling of the Rater's county table for the subline; "" when no table is loaded or no county could be resolved).
     */
    public record Location(String address, String latitude, String longitude, String street, String city, String county) {
        Location(String address, String latitude, String longitude) {
            this(address, latitude, longitude, "", "", "");
        }
    }

    /** A fetched address as cached: street ("4298 Division Street"), city, ZIP, coordinates and the lookup's county. */
    record Address(String street, String city, String zip, String latitude, String longitude, String county) {}

    private final Function<String, Optional<String[]>> lookup;
    private final Function<double[], Optional<Map<String, String>>> reverse;
    private final Path cacheFile;
    private final Map<String, String[]> cache = new LinkedHashMap<>();
    private final Map<String, Address> addresses = new LinkedHashMap<>();
    /** The Rater's county table: "STATE|SUBLINE" (or "STATE|*") -> counties as spelled there. Empty = counties not checked. */
    private final Map<String, java.util.List<String>> countyTable = new LinkedHashMap<>();

    public RaterGeocoder(RaterSettings settings) {
        this(settings, new Throttle(Long.parseLong(settings.get("rater.geocode.delayMs", "1100"))));
    }

    private RaterGeocoder(RaterSettings settings, Throttle throttle) {
        // rater.geocode.online=false: no lookups at all - every case's address comes from the test data
        this(online(settings) ? nominatim(settings.get("rater.geocode.url", "https://nominatim.openstreetmap.org/search"), throttle)
                : query -> Optional.empty(),
            online(settings) ? nominatimReverse(settings.get("rater.geocode.reverseUrl", "https://nominatim.openstreetmap.org/reverse"),
                throttle) : point -> Optional.empty(),
            settings.path("rater.geocode.cache").toString().isEmpty() ? null : settings.path("rater.geocode.cache"));
    }

    private static boolean online(RaterSettings settings) {
        return Boolean.parseBoolean(settings.get("rater.geocode.online", "true"));
    }

    /** With any lookup (query text -> {lat, lon}) and no address lookup; used by tests. cacheFile may be null. */
    RaterGeocoder(Function<String, Optional<String[]>> lookup, Path cacheFile) {
        this(lookup, point -> Optional.empty(), cacheFile);
    }

    /** With any lookup and reverse lookup ({lat, lon} -> address parts); used by tests. cacheFile may be null. */
    RaterGeocoder(Function<String, Optional<String[]>> lookup, Function<double[], Optional<Map<String, String>>> reverse,
                  Path cacheFile) {
        this.lookup = lookup;
        this.reverse = reverse;
        this.cacheFile = cacheFile;
        readCache();
    }

    /** The Rater's county table ({@link #tableKey} -> counties) that the County sent must come from. */
    public synchronized RaterGeocoder withCountyTable(Map<String, java.util.List<String>> table) {
        countyTable.clear();
        countyTable.putAll(table);
        return this;
    }

    /** As {@link #withCountyTable} for a table without sublines: state -> counties. Used by tests. */
    synchronized RaterGeocoder withStateCounties(Map<String, java.util.List<String>> table) {
        countyTable.clear();
        table.forEach((state, counties) -> countyTable.put(tableKey(state, "*"), counties));
        return this;
    }

    /** Key of the county table: "VIRGINIA|DP1_BUILDERS_RISK" for State "Virginia" and Subline "DP1 Builders Risk". */
    static String tableKey(String state, String subline) {
        String sub = subline == null || subline.isBlank() ? "*" : subline.trim().toUpperCase(Locale.ROOT).replaceAll("[\\s_]+", "_");
        return stateKey(state) + "|" + sub;
    }

    /** The counties of a state for a configuration (its own subline rows; else every county listed for the state). */
    private java.util.List<String> counties(String state, String configurationId) {
        java.util.List<String> own = countyTable.get(tableKey(state, configurationId));
        if (own != null) return own;
        String prefix = stateKey(state) + "|";
        java.util.List<String> all = new java.util.ArrayList<>();
        countyTable.forEach((key, list) -> {
            if (key.startsWith(prefix)) list.stream().filter(c -> !all.contains(c)).forEach(all::add);
        });
        return all;
    }

    /**
     * The county table's spelling of the test data county for this state and configuration, or null when the county is not
     * in the table (no table loaded: the county as it is).
     */
    public synchronized String knownCounty(String county, String stateName, String configurationId) {
        String state = stateName == null ? "" : stateName.replace('_', ' ').trim();
        java.util.List<String> allowed = counties(state, configurationId);
        return allowed.isEmpty() ? county : tableCounty(county, allowed);
    }

    /** As {@link #locate(String, String, String, String, String, String)} without a configuration (any subline's counties). */
    public Location locate(String fullAddress, String county, String stateName, String stateCode, String zip) {
        return locate(fullAddress, county, stateName, stateCode, zip, null);
    }

    /**
     * The location of a case. With a given full address: that address and its coordinates. Otherwise a fetched street
     * address inside the ZIP, County and State, sent as "4298 Division Street, CHINCOTEAGUE, VA 23336, United States";
     * when none is found, "COUNTY, ST zip, United States" at the ZIP centre (empty coordinates if even that fails).
     */
    public synchronized Location locate(String fullAddress, String county, String stateName, String stateCode, String zip,
                                        String configurationId) {
        String zip5 = zip == null || zip.isBlank() ? "" : String.format("%5s", zip.trim()).replace(' ', '0');
        String state = stateName == null ? "" : stateName.replace('_', ' ').trim();
        if (fullAddress != null && !fullAddress.isBlank()) {
            String[] c = coordinates(fullAddress.trim());
            return new Location(fullAddress.trim(), c[0], c[1]);
        }
        // County: the county table's spelling of the test data county for the subline; else the county of a real address
        // in the ZIP
        java.util.List<String> allowed = counties(state, configurationId);
        String tableCounty = tableCounty(county, allowed);
        if (tableCounty == null && allowed.size() == 1) tableCounty = allowed.get(0);
        // The address must lie in the county sent; when it is still unknown only the State and ZIP are checked
        String checkCounty = tableCounty != null && allowed.size() > 1 ? tableCounty : (allowed.isEmpty() ? county : null);
        String key = String.join("|", state, checkCounty == null ? "*" : checkCounty, zip5);
        Address found = addresses.get(key);
        if (found == null && !zip5.isEmpty()) {
            found = fetchAddress(checkCounty, state, zip5);
            if (found != null) {                 // a failed lookup is not cached: the next run tries again
                addresses.put(key, found);
                writeCache();
                log.info("Address for {} {} {}: {}, {} ({}, {})", nz(county), state, zip5, found.street(), found.city(),
                    found.latitude(), found.longitude());
            } else {
                log.warn("No street address found in {} {} {} - the ZIP centre is used", nz(county), state, zip5);
            }
        }
        if (tableCounty == null && !allowed.isEmpty()) {
            tableCounty = found == null ? null : tableCounty(firstNonBlank(found.county(), found.city()), allowed);
            if (tableCounty == null) {
                log.warn("County '{}' ({}) is not in the Rater's county table and no table county was found for ZIP {}"
                    + " - the test data county is sent", nz(county), state, zip5);
            } else {
                log.info("County '{}' ({} {}) is not in the Rater's county table - '{}' (county of the ZIP) is sent",
                    nz(county), state, zip5, tableCounty);
            }
        }
        String sendCounty = tableCounty == null ? "" : tableCounty;
        if (found != null) {
            String full = found.street() + ", " + found.city().toUpperCase(Locale.ROOT) + ", " + nz(stateCode) + " " + found.zip()
                + ", United States";
            return new Location(full, found.latitude(), found.longitude(), found.street(), found.city(), sendCounty);
        }
        String[] c = coordinates(zip5 + ", " + state + ", USA");
        String address = String.join(", ", sendCounty.isEmpty() ? nz(county) : sendCounty, (nz(stateCode) + " " + zip5).trim())
            + ", United States";
        return new Location(address, c[0], c[1], "", "", sendCounty);
    }

    /**
     * The table's spelling of a county ("VIRGINIA BEACH CITY" -> "VIRGINIA BEACH (CITY)", "SAINT JOHNS" -> "ST. JOHNS",
     * "PRINCE GEORGES" -> "PRINCE GEORGE'S", "Miami-Dade County" -> "MIAMI-DADE"), or null when it is not in the list.
     * A county listed in two spellings keeps the given one when it is one of them. "RICHMOND CITY" and "RICHMOND" stay
     * different counties.
     */
    static String tableCounty(String county, java.util.List<String> allowed) {
        String k = countyKey(county);
        if (k.isEmpty()) return null;
        for (String a : allowed) if (a.equalsIgnoreCase(nz(county))) return a;
        for (String a : allowed) if (countyKey(a).equals(k)) return a;
        return null;
    }

    /** Comparison key of a county name: upper case, "(CITY)" = "CITY", "SAINT" = "ST", no "COUNTY" / "PARISH", no punctuation. */
    static String countyKey(String county) {
        if (county == null) return "";
        String c = county.toUpperCase(Locale.ROOT).replace("(CITY)", "CITY").replace("SAINT ", "ST ").replace("'", "")
            .replaceAll("[^A-Z0-9 ]", " ");
        c = (" " + c + " ").replace(" COUNTY ", " ").replace(" PARISH ", " ");
        return c.replaceAll("\\s+", " ").trim();
    }

    private static String stateKey(String state) {
        return state == null ? "" : state.replace('_', ' ').trim().toUpperCase(Locale.ROOT);
    }

    /** Coordinates of a query (cached), or empty strings when not found. */
    private String[] coordinates(String query) {
        String[] c = cache.get(query);
        if (c != null) return c;
        Optional<String[]> found = lookup.apply(query);
        if (found.isEmpty()) {
            log.warn("No coordinates found for '{}' - Latitude / Longitude sent empty", query);
            return new String[]{"", ""};
        }
        cache.put(query, found.get());
        writeCache();
        log.info("Geocoded '{}' -> {}, {}", query, found.get()[0], found.get()[1]);
        return found.get();
    }

    /**
     * The nearest address around the ZIP centre that lies in the same ZIP, State and County, preferring one with a house
     * number and a city; null when there is none (or no reverse lookup). Without any city the county's name is used.
     */
    private Address fetchAddress(String county, String state, String zip5) {
        String[] centre = coordinates(zip5 + ", " + state + ", USA");
        if (centre[0].isEmpty()) return null;
        double lat = Double.parseDouble(centre[0]);
        double lon = Double.parseDouble(centre[1]);
        Address best = null;
        int bestScore = -1;
        for (double[] d : AROUND) {
            Optional<Map<String, String>> parts = reverse.apply(new double[]{lat + d[0], lon + d[1]});
            if (parts.isEmpty() || !matches(parts.get(), county, state, zip5)) continue;
            Map<String, String> a = parts.get();
            String road = nz(a.get("road"));
            if (road.isEmpty()) continue;
            String city = firstNonBlank(a.get("city"), a.get("town"), a.get("village"), a.get("hamlet"), a.get("municipality"),
                a.get("suburb"), cityOfCounty(county));
            String house = nz(a.get("house_number"));
            int score = (house.isEmpty() ? 0 : 2) + (city.isEmpty() ? 0 : 1);
            if (score > bestScore) {
                best = new Address(house.isEmpty() ? road : house + " " + road, city, zip5, round(a.get("lat")), round(a.get("lon")),
                    firstNonBlank(a.get("county"), ""));
                bestScore = score;
            }
            if (score == 3) break;
        }
        if (best != null && best.city().isEmpty()) {
            String name = normaliseCounty(county).toLowerCase(Locale.ROOT);
            String countyName = java.util.Arrays.stream(name.split(" ")).filter(w -> !w.isEmpty())
                .map(w -> Character.toUpperCase(w.charAt(0)) + w.substring(1)).collect(java.util.stream.Collectors.joining(" "));
            best = new Address(best.street(), countyName, best.zip(), best.latitude(), best.longitude(), best.county());
        }
        return best;
    }

    /** True when the reverse lookup's address lies in the case's State and ZIP, and in its County where the lookup has one. */
    static boolean matches(Map<String, String> a, String county, String state, String zip5) {
        String code = StateCodes.of(state);
        if (code == null || !code.equals(StateCodes.of(a.get("state")))) return false;
        String postcode = nz(a.get("postcode"));
        if (!postcode.startsWith(zip5)) return false;
        String testCounty = normaliseCounty(county);
        if (testCounty.isEmpty() || nz(county).toUpperCase(Locale.ROOT).contains("PLANNING REGION")) return true;   // State + ZIP only
        String found = normaliseCounty(a.get("county"));
        // Virginia's independent cities have no county in the lookup: the city is checked instead
        if (found.isEmpty()) found = normaliseCounty(firstNonBlank(a.get("city"), a.get("town"), ""));
        return found.isEmpty() || found.equals(testCounty);
    }

    /** "Miami-Dade County" / "MIAMI-DADE" -> "MIAMI DADE"; "Saint Tammany Parish" -> "ST TAMMANY"; "Hampton city" -> "HAMPTON". */
    static String normaliseCounty(String county) {
        if (county == null) return "";
        String c = county.toUpperCase(Locale.ROOT).replace("SAINT ", "ST ").replaceAll("[^A-Z0-9 ]", " ");
        c = (" " + c + " ").replace(" COUNTY ", " ").replace(" PARISH ", " ").replace(" CITY OF ", " ")
            .replace(" BOROUGH ", " ").replaceAll(" CITY $", " ").replaceAll(" CITY ", " ");
        return c.replaceAll("\\s+", " ").trim();
    }

    /** The city name of a Virginia independent city county ("HAMPTON CITY" -> "Hampton"), else "". */
    private static String cityOfCounty(String county) {
        String c = nz(county);
        if (!c.toUpperCase(Locale.ROOT).endsWith(" CITY")) return "";
        String name = c.substring(0, c.length() - 5).toLowerCase(Locale.ROOT);
        return name.isEmpty() ? "" : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v.trim();
        return "";
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }

    /** One request per delayMs at most, shared by the search and the reverse lookup (Nominatim's usage policy). */
    static final class Throttle {
        private final long delayMs;
        private long last;

        Throttle(long delayMs) {
            this.delayMs = delayMs;
        }

        synchronized void await() throws InterruptedException {
            long wait = last + delayMs - System.currentTimeMillis();
            if (wait > 0) Thread.sleep(wait);
            last = System.currentTimeMillis();
        }
    }

    /** OpenStreetMap Nominatim free-text search (first US result). */
    static Function<String, Optional<String[]>> nominatim(String url, Throttle throttle) {
        return query -> get(url + "?format=jsonv2&limit=1&countrycodes=us&q=" + URLEncoder.encode(query, StandardCharsets.UTF_8),
            throttle, "'" + query + "'").map(body -> body.path(0)).filter(first -> !first.isMissingNode())
            .map(first -> new String[]{round(first.path("lat").asText()), round(first.path("lon").asText())});
    }

    /** OpenStreetMap Nominatim reverse lookup: the nearest address of a point, as its parts (road, house_number, postcode ...). */
    static Function<double[], Optional<Map<String, String>>> nominatimReverse(String url, Throttle throttle) {
        return point -> get(url + "?format=jsonv2&zoom=18&addressdetails=1&lat=" + point[0] + "&lon=" + point[1],
            throttle, point[0] + "," + point[1]).filter(body -> body.has("address")).map(body -> {
                Map<String, String> parts = new LinkedHashMap<>();
                body.path("address").fields().forEachRemaining(e -> parts.put(e.getKey(), e.getValue().asText()));
                parts.put("lat", body.path("lat").asText());
                parts.put("lon", body.path("lon").asText());
                return parts;
            });
    }

    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

    private static Optional<JsonNode> get(String uri, Throttle throttle, String what) {
        try {
            throttle.await();
            HttpRequest request = HttpRequest.newBuilder(URI.create(uri))
                .header("User-Agent", "RT-API-Automation/1.0 (rater validation)")
                .timeout(Duration.ofSeconds(30)).GET().build();
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 ? Optional.of(MAPPER.readTree(response.body())) : Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Geocoding {} failed: {}", what, e.getMessage());
            return Optional.empty();
        }
    }

    /** 6 decimals (about 0.1 m), as plain text. */
    static String round(String value) {
        return new java.math.BigDecimal(value).setScale(6, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    /** The cache file holds query -> [lat, lon] and "State|County|ZIP" -> {street, city, zip, latitude, longitude, county}. */
    private void readCache() {
        if (cacheFile == null || !Files.isRegularFile(cacheFile)) return;
        try {
            for (Iterator<Map.Entry<String, JsonNode>> it = MAPPER.readTree(cacheFile.toFile()).fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                JsonNode v = e.getValue();
                if (v.isArray()) {
                    cache.put(e.getKey(), new String[]{v.path(0).asText(), v.path(1).asText()});
                } else if (v.isObject()) {
                    addresses.put(e.getKey(), new Address(v.path("street").asText(), v.path("city").asText(), v.path("zip").asText(),
                        v.path("latitude").asText(), v.path("longitude").asText(), v.path("county").asText()));
                }
            }
        } catch (IOException e) {
            log.warn("Geocode cache {} not read: {}", cacheFile, e.getMessage());
        }
    }

    private void writeCache() {
        if (cacheFile == null) return;
        try {
            ObjectNode root = MAPPER.createObjectNode();
            cache.forEach((query, c) -> root.putArray(query).add(c[0]).add(c[1]));
            addresses.forEach((key, a) -> root.putObject(key).put("street", a.street()).put("city", a.city()).put("zip", a.zip())
                .put("latitude", a.latitude()).put("longitude", a.longitude()).put("county", a.county()));
            if (cacheFile.getParent() != null) Files.createDirectories(cacheFile.getParent());
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(cacheFile.toFile(), root);
        } catch (IOException e) {
            log.warn("Geocode cache {} not written: {}", cacheFile, e.getMessage());
        }
    }
}