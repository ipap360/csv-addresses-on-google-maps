package com.team360.utilities.csv2map;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.io.FileUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

public class CSVFile {

    // https://tools.ietf.org/html/rfc4180
    private static final CSVFormat CSV_FORMAT = CSVFormat
            .RFC4180
            .withFirstRecordAsHeader()
            .withDelimiter(",".charAt(0));

    private String data;
    private boolean hasHeaders;

    public CSVFile(String path, Charset encoding, boolean hasHeaders) throws IOException {
        byte[] encoded = Files.readAllBytes(Paths.get(path));
        this.data = new String(encoded, encoding);
        this.hasHeaders = hasHeaders;
    }

    public CSVFile(List<List<String>> lines, boolean hasHeaders) {
        StringBuilder sb = new StringBuilder();
        for (List<String> line : lines) {
            boolean first = true;
            for (String value : line) {
                if (!first) {
                    sb.append(',');
                }
                sb.append('"').append(escapeQuotes(value)).append('"');
                first = false;
            }
            sb.append("\n");
        }
        this.data = sb.toString();
        this.hasHeaders = hasHeaders;
    }

    private String escapeQuotes(String value) {
        String result = value;
        if (result.contains("\"")) {
            result = result.replace("\"", "\"\"");
        }
        return result;
    }

    public void toFile(String path, Charset encoding) throws IOException {
        FileUtils.writeStringToFile(new File(path), data, encoding);
    }

    public JSONObject toJSONObject() {

        JSONObject parsedResult = new JSONObject();

        JSONArray headersArr = new JSONArray();
        JSONArray valuesArr = new JSONArray();

        parsedResult.put("status", 0);
        parsedResult.put("headers", headersArr);
        parsedResult.put("values", valuesArr);

        Map<String, Integer> headerMap;

        try {
            CSVParser parser = CSVParser.parse(data, CSV_FORMAT);
            if (parser == null) {
                parsedResult.put("status", -1);
                return parsedResult;
            }

            try {

                List<CSVRecord> recordList = parser.getRecords();

                if (hasHeaders) {
                    headerMap = parser.getHeaderMap();

                    for (String key : headerMap.keySet()) {
                        int idx = headerMap.get(key);
                        headersArr.put(idx, key);
                    }
                    parsedResult.put("headers", headersArr);
                }

                JSONArray jsonArrayRecord;
                for (final CSVRecord record : recordList) {

                    jsonArrayRecord = new JSONArray();
                    String val;
                    for (int i = 0; i < record.size(); i++) {
                        val = record.get(i);
                        jsonArrayRecord.put(val);
                    }

                    valuesArr.put(jsonArrayRecord);
                }
                parsedResult.put("values", valuesArr);

            } finally {
                parser.close();
            }

        } catch (IOException | JSONException e) {
            parsedResult.put("status", -1);
            parsedResult.put("error", e.getMessage());
            e.printStackTrace();
        }

        return parsedResult;
    }

}
