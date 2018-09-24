package com.team360.utilities.csv2map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.maps.GeoApiContext;
import com.google.maps.GeocodingApi;
import com.google.maps.PlacesApi;
import com.google.maps.errors.OverQueryLimitException;
import com.google.maps.model.GeocodingResult;
import com.google.maps.model.LatLng;
import com.google.maps.model.PlacesSearchResult;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class Main {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    public static void main(String[] args) {
        try {
            if (args.length != 2) {
                throw new RuntimeException("Please provide proper parameters (0: GOOGLE_API_KEY, 1: filename to read)");
            }

            // https://developers.google.com/maps/documentation/geocoding/get-api-key
            final String GOOGLE_API_KEY = args[0];;

            String filename = args[1];
            CSVFile in = new CSVFile(filename, UTF8, true);

            // convert csv to json
            JSONObject o = in.toJSONObject();
            JSONArray values = o.optJSONArray("values");
            if (values.length() == 0) {
                return;
            }

            JSONArray failed = new JSONArray();
            JSONArray output = new JSONArray();
            for (int i = 0; i < values.length(); i++) {

                JSONArray element = values.getJSONArray(i);
                JSONObject entry = new JSONObject();

                entry.put("id", element.optString(2).trim());
                entry.put("name", element.optString(3).trim());
                entry.put("address", element.optString(7).trim());
                entry.put("postal_code", element.optString(6).trim());
                entry.put("area", element.optString(8).trim());

                if (StringUtils.isAllBlank(entry.optString("address"))) {
                    failed.put(entry);
                    System.out.println("skipping search for " + entry.optString("name") + " (no address)");
                    continue;
                }
                output.put(entry);
            }

            GeoApiContext context = new GeoApiContext.Builder().apiKey(GOOGLE_API_KEY).build();
            appendCoordinates(context, output, failed);

            String basename = FilenameUtils.getBaseName(filename);

            // write json
            String jsonFileName = basename + ".out.json";
            FileUtils.writeStringToFile(new File(jsonFileName), output.toString(4), UTF8);

            String errorFileName = basename + ".errors.json";
            FileUtils.writeStringToFile(new File(errorFileName), failed.toString(4), UTF8);

            // write jsonp
            String content = "wrappedJSON(" + output.toString() + ");";
            String jsonpFileName = basename + ".jsonp";
            FileUtils.writeStringToFile(new File(jsonpFileName), content, UTF8);

            // write html
            byte[] encoded = Files.readAllBytes(Paths.get("map.html.template"));
            String html = new String(encoded, UTF8);

            html = html.replace("{{GOOGLE_API_KEY}}", GOOGLE_API_KEY);
            html = html.replace("{{JSONP_FILENAME}}", jsonpFileName);

            String mapFileName = basename + ".map.html";
            FileUtils.writeStringToFile(new File(mapFileName), html, UTF8);

            CSVFile out = new CSVFile(json2list(output), true);
            CSVFile errors = new CSVFile(json2list(failed), true);

            // write csv
            String csvFileName1 = FilenameUtils.getBaseName(filename) + ".out.csv";
            out.toFile(csvFileName1, UTF8);

            String csvFileName2 = FilenameUtils.getBaseName(filename) + ".errors.csv";
            errors.toFile(csvFileName2, UTF8);

        } catch (Exception e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    static void appendCoordinates(GeoApiContext context, JSONArray array, JSONArray errors) {

        Gson gson = new GsonBuilder().setPrettyPrinting().create();

        int geocoding_requests = 0;
        int place_requests = 0;

        JSONArray output = new JSONArray();

        for (int i = 0; i < array.length(); i++) {

            JSONObject entry = array.optJSONObject(i);

            String name = entry.optString("name");
            String postalCode = entry.optString("postal_code");
            String address = entry.optString("address");
            String area = entry.optString("area");

            // will try formatting the address query in several ways (q1, q2, q3) in order to increase the result accuracy
            // q1 format: <address>, <postal code>, <area>, <country>
            // q2 format: <address>, <country> (used if bad data in postal code or area prevent q1 from working properly)
            // q3 format: <name>, <country> (uses a different query - sometimes you can lookup sth at google using only its name)
            String q1 = address, q2 = address, q3 = name;

            if (StringUtils.isNotBlank(postalCode)) {
                q1 += ", " + postalCode;
            } else if (StringUtils.isNotBlank(area)) {
                q1 += ", " + area;
            }

            q1 += ", Greece";
            q2 += ", Greece";
            q3 += ", Greece";

            GeocodingResult[] results;
            LatLng location = null;
            String googleAddress = "";

            try {
                // geocoding_requests is counting total requests (there is a threshold set by Google per Day?Minute?Second?)
                geocoding_requests++;
//				System.out.println(" " + q1 + " (" + geocoding_requests + ")");
                results = GeocodingApi.geocode(context, q1).await();
                if (results.length == 0) { // 1st attempt returned with no results... lets try the 2nd if it is different
                    if (!q1.equals(q2)) {
                        geocoding_requests++;
//						System.out.println("retry geocode..." + q2 + " (" + geocoding_requests + ")");
                        results = GeocodingApi.geocode(context, q2).await();
                    }
                    if (results.length == 0) {
                        place_requests++;
//						System.out.println("try for place... " + entry.optString("name") + " (" + place_requests + ")");
                        PlacesSearchResult[] places = PlacesApi.textSearchQuery(context, q3).await().results;
                        if (places.length > 0) {
                            location = places[0].geometry.location;
                            googleAddress = places[0].formattedAddress;
                        } else {
//							System.out.println("-----> NOT FOUND!");
                        }
                    } else {
                        location = results[0].geometry.location;
                        googleAddress = results[0].formattedAddress;
                    }
                } else {
                    location = results[0].geometry.location;
                    googleAddress = results[0].formattedAddress;
                }

                entry.put("google_address", googleAddress);
                JSONObject locationJSON;

                if (location != null) {
                    locationJSON = new JSONObject(gson.toJson(location));
                    entry.put("location", locationJSON);
                    output.put(entry);
                } else {
                    errors.put(entry);
                }
            } catch (OverQueryLimitException e0) {
                e0.printStackTrace();
                break;
            } catch (Exception e1) {
                e1.printStackTrace();
            }
        }

        // mutate original object
        array = new JSONArray(output.toString());
    }

    static ArrayList<List<String>> json2list(JSONArray array) {

        ArrayList<List<String>> file = new ArrayList();

        ArrayList<String> line;

        line = new ArrayList();

        line.add("ID");
        line.add("Name");
        line.add("Address");
        line.add("Postal Code");
        line.add("Area");
        line.add("Google Address");
        line.add("Latitude");
        line.add("Longitude");

        file.add(line);

        for (int i = 0; i < array.length(); i++) {

            JSONObject entry = array.optJSONObject(i);

            line = new ArrayList<String>();

            line.add(entry.optString("id"));
            line.add(entry.optString("name"));
            line.add(entry.optString("address"));
            line.add(entry.optString("postal_code"));
            line.add(entry.optString("area"));
            line.add(entry.optString("google_address"));

            JSONObject locationJSON = entry.optJSONObject("location");
            if (locationJSON == null) {
                locationJSON = new JSONObject();
            }

            line.add(locationJSON.optString("lat"));
            line.add(locationJSON.optString("lng"));

            file.add(line);
        }

        return file;
    }

}
