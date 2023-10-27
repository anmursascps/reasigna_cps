package com.project.reasigna.controllers;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import org.apache.commons.io.FileUtils;
import org.json.JSONObject;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;

import com.project.reasigna.models.*;
import com.project.reasigna.repository.*;
import com.project.reasigna.utils.Utils;

@RestController
@RequestMapping("/api/upload")
@CrossOrigin(origins = "*", maxAge = 3600)
public class GtfsController {

    @Autowired
    private GtfsRepository gtfsRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private AgencyRepository agencyRepository;

    @Autowired
    private RoutesRepository routesRepository;

    @Autowired
    private StopsRepository stopsRepository;

    @Autowired
    private CalendarRepository calendarRepository;

    @Autowired
    private ShapesRepository shapesRepository;

    @Autowired
    private CalendarDatesRepository calendarDatesRepository;

    @Autowired
    private TripsRepository tripsRepository;

    @Autowired
    private StopTimesRepository stopTimesRepository;

    public DateFormat format = new SimpleDateFormat("yyyyMMdd");
    public String splitter = ",(?=(?:[^\\\"]*\\\"[^\\\"]*\\\")*[^\\\"]*$)";
    public static final String UTF8_BOM = "\uFEFF";

    private static String removeUTF8BOM(String s) {
        if (s.startsWith(UTF8_BOM)) {
            s = s.substring(1);
        }
        return s;
    }

    @PostMapping("/validate_gtfs")
    public ResponseEntity<String> validateGtfs(@RequestParam("file") MultipartFile zipInputStream)
            throws IOException {
        try {

            File tempFile = File.createTempFile(zipInputStream.getOriginalFilename(), ".zip");
            zipInputStream.transferTo(tempFile);

            // Get the full path of the zip file
            String fullPath = tempFile.getParentFile().getAbsolutePath();

            String docker_run = "docker run --rm -v";
            String volume = fullPath + ":/work";
            String image = " ghcr.io/mobilitydata/gtfs-validator:latest ";
            String input = " -i \"/work/" + tempFile.getName();
            String output = "\" -o /work/output -n -p";

            String command = docker_run + volume + image + input + output;

            System.out.println(command);

            Process process = Runtime.getRuntime().exec(command);

            // Output of the process
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            BufferedReader readerError = new BufferedReader(new InputStreamReader(process.getErrorStream()));

            String line;
            while ((line = readerError.readLine()) != null) {
                System.out.println(line);
            }
            while ((line = reader.readLine()) != null) {
                System.out.println(line);
            }

            // Wait until the report.json file is created
            process.waitFor();

            // Now in the outpit folder there is a json file with the validation results
            // Read the json file and retrieve the results
            String jsonPath = fullPath + "/output/report.json";
            File jsonFile = new File(jsonPath);
            String jsonContent = FileUtils.readFileToString(jsonFile, "UTF-8");
            // Remove the folder output
            File notice_schema = new File(fullPath + "/output/notice_schema.json");
            // Retrieve all the object names from the json file
            String notice_String = FileUtils.readFileToString(notice_schema, "UTF-8");
            JSONObject jsonObject = new JSONObject(notice_String);

            // Now in the jsonContent theres an array of jsonObject named "notices" each
            // notice has a "code" so i need to add the description and shortSummary to each
            JSONObject jsonContentObject = new JSONObject(jsonContent);
            for (int i = 0; i < jsonContentObject.getJSONArray("notices").length(); i++) {
                JSONObject notice = jsonContentObject.getJSONArray("notices").getJSONObject(i);
                String code = notice.getString("code");
                JSONObject object = jsonObject.getJSONObject(code);
                notice.put("shortSummary", object.has("shortSummary") ? object.getString("shortSummary") : "");
                notice.put("description", object.has("description") ? object.getString("description") : "");
                notice.put("url", "https://gtfs-validator.mobilitydata.org/rules.html#" + code);
            }

            // Now put the jsonContentObject back to the jsonContent
            jsonContent = jsonContentObject.toString();

            // Add to the jsonContent a new pair with an "OK" to know that the process was
            // successfull

            // If theres any error named "missing_required_file" then the process was not a
            // success, so put a new pair with an "ERROR" to know that the process was not
            // successfull
            for (int i = 0; i < jsonContentObject.getJSONArray("notices").length(); i++) {
                JSONObject notice = jsonContentObject.getJSONArray("notices").getJSONObject(i);
                String code = notice.getString("code");
                if (code.equals("missing_required_file") || code.equals("missing_calendar_and_calendar_date_files")) {
                    jsonContentObject.put("OK", false);
                    break;
                }
            }

            // If theres no error named "missing_required_file" then the process was a
            // success, so put a new pair with an "OK" to know that the process was success
            if (!jsonContentObject.has("OK")) {
                jsonContentObject.put("OK", true);
            }

            // Now put the jsonContentObject back to the jsonContent
            jsonContent = jsonContentObject.toString();

            // Delete the temp file
            FileUtils.deleteDirectory(new File(fullPath + "/output"));
            tempFile.delete();

            process.destroy();

            return ResponseEntity.status(HttpStatus.OK).body(jsonContent.toString());

        } catch (Exception e) {

            System.out.println(e);
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error processing the zip file");
        }
    }

    @PostMapping("/save_gtfs/{id}")
    public ResponseEntity<String> processGtfsZip2(@RequestParam("file") MultipartFile zipInputStream,
            @PathVariable Long id, @RequestParam("nombre") String gtfsName)
            throws IOException, ParseException {
        long startTime = System.currentTimeMillis(); // get start time
        Gtfs gtfs = new Gtfs();
        try (ZipInputStream zipStream = new ZipInputStream(zipInputStream.getInputStream())) {

            File tempFile = File.createTempFile(zipInputStream.getOriginalFilename(),
                    ".zip");
            zipInputStream.transferTo(tempFile);
            ZipFile zipFile = new ZipFile(tempFile);

            // Get the fullpath of the zip file
            zipFile.close();

            Map<String, InputStream> fileStreams = new HashMap<>();
            ZipEntry entry;
            while ((entry = zipStream.getNextEntry()) != null) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buffer = new byte[1024];
                int len;
                while ((len = zipStream.read(buffer)) > -1) {
                    baos.write(buffer, 0, len);
                }
                baos.flush();

                InputStream is = new ByteArrayInputStream(baos.toByteArray());

                String fileName = entry.getName();

                fileStreams.put(fileName, is);
            }
            Project project = projectRepository.findById(id).get();
            gtfs = new Gtfs(gtfsName, project);
            // try {
            gtfsRepository.save(gtfs);

            // Process files in specific order
            processAgency(fileStreams.get("agency.txt"), gtfs);
            // If it has calendar.txt then process it, if it has calendar_dates.txt then
            // process it and if have both then process both
            if (fileStreams.containsKey("calendar.txt") &&
                    fileStreams.containsKey("calendar_dates.txt")) {
                processCalendar(fileStreams.get("calendar.txt"), gtfs);
                processCalendarDates(fileStreams.get("calendar_dates.txt"), gtfs);
            } else {
                if (fileStreams.containsKey("calendar.txt")) {
                    processCalendar(fileStreams.get("calendar.txt"), gtfs);
                }
                if (fileStreams.containsKey("calendar_dates.txt")) {
                    processCalendarDates(fileStreams.get("calendar_dates.txt"), gtfs);
                }
            }
            if (fileStreams.containsKey("shapes.txt")) {
                processShapes(fileStreams.get("shapes.txt"), gtfs);
            }
            processRoutes(fileStreams.get("routes.txt"), gtfs);
            processStops(fileStreams.get("stops.txt"), gtfs);
            processTrips(fileStreams.get("trips.txt"), gtfs);
            processStopTimes(fileStreams.get("stop_times.txt"), gtfs);

            System.out.println("Done processing zip file");
            long endTime = System.currentTimeMillis(); // get end time
            long duration = endTime - startTime; // calculate duration
            System.out.println("processAgency took " + duration + " milliseconds to execute");
            return new ResponseEntity<>("Done processing zip file/" + gtfs.getId(), HttpStatus.OK);
        } catch (Exception e) {
            System.out.println(e.getMessage());
            System.out.println("Error processing zip file");
            e.printStackTrace();
            List<Stops> stops = stopsRepository.findByGtfs(gtfs);
            List<StopTimes> stopTimes = stopTimesRepository.findByGtfs(gtfs);
            List<Trips> trips = tripsRepository.findByGtfs(gtfs);
            List<Shapes> shapes = shapesRepository.findByGtfs(gtfs);
            List<CalendarDates> calendarDates = calendarDatesRepository.findByGtfs(gtfs);
            List<Calendar> calendars = calendarRepository.findByGtfs(gtfs);
            List<Routes> routes = routesRepository.findByGtfs(gtfs);
            List<Agency> agencies = agencyRepository.findByGtfs(gtfs);

            stopTimesRepository.deleteAll(stopTimes);
            tripsRepository.deleteAll(trips);
            shapesRepository.deleteAll(shapes);
            calendarDatesRepository.deleteAll(calendarDates);
            calendarRepository.deleteAll(calendars);
            stopsRepository.deleteAll(stops);
            routesRepository.deleteAll(routes);
            agencyRepository.deleteAll(agencies);

            gtfsRepository.delete(gtfs);
            long endTime = System.currentTimeMillis(); // get end time
            long duration = endTime - startTime; // calculate duration
            System.out.println("Saving GTFS took " + duration + " milliseconds to execute");

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error processing zip file");
        }
    }

    @GetMapping("{id}")
    public java.util.List<Gtfs> getGtfsByProjectId(@PathVariable Long id) {
        return gtfsRepository.findByProjectId(id);
    }

    @GetMapping("/gtfs/{id}")
    public ResponseEntity<Object> getGtfsById(@PathVariable Long id) {

        // Retrieve the gtfs and all the data related to it
        Gtfs gtfs = gtfsRepository.findById(id).orElse(null);

        if (gtfs == null) {
            return ResponseEntity.notFound().build();
        }

        List<Agency> agencies = agencyRepository.findByGtfs(gtfs);
        List<Routes> routes = routesRepository.findByGtfs(gtfs);
        // List<Stops> stops = stopsRepository.findByGtfs(gtfs);
        // List<Calendar> calendars = calendarRepository.findByGtfs(gtfs);
        // List<CalendarDates> calendarDates = calendarDatesRepository.findByGtfs(gtfs);
        // List<Shapes> shapes = shapesRepository.findByGtfs(gtfs);
        // List<Trips> trips = tripsRepository.findByGtfs(gtfs);
        // List<StopTimes> stopTimes = stopTimesRepository.findByGtfs(gtfs);

        Map<String, Object> response = new HashMap<>();
        response.put("gtfs", gtfs);
        response.put("agencies", agencies);
        response.put("routes", routes);

        // response.put("stops", stops);
        // response.put("calendars", calendars);
        // response.put("calendarDates", calendarDates);
        // response.put("shapes", shapes);
        // for (Shapes shape : shapes) {
        // shape.setLinestring(null);
        // }
        // response.put("trips", trips);

        // // Trips has a list of routes, calendar and shapes, delete them from the
        // // response
        // for (Trips trip : trips) {
        // trip.setRoutes(null);
        // trip.setCalendar(null);
        // trip.setShapes(null);
        // }

        // response.put("stopTimes", stopTimes);

        return ResponseEntity.ok(response);
    }

    @PostMapping("/new")
    public ResponseEntity<Gtfs> createNewGtfs(@RequestBody Gtfs gtfs) {
        System.out.println("Creating new gtfs");
        try {
            Project p = projectRepository.findById(gtfs.getProject().getId()).get();
            gtfs.setProject(p);
            gtfsRepository.save(gtfs);
            return new ResponseEntity<>(gtfs, HttpStatus.OK);
        } catch (Exception e) {
            System.out.println(e);
            return new ResponseEntity<>(null, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @DeleteMapping("/delete/{id}")
    public ResponseEntity<String> deleteGtfs(@PathVariable Long id) {
        System.out.println("Deleting gtfs");
        System.out.println(id);

        Gtfs gtfs = gtfsRepository.findById(id).orElse(null);
        if (gtfs == null) {
            return new ResponseEntity<>("Gtfs not found", HttpStatus.NOT_FOUND);
        }

        List<Stops> stops = stopsRepository.findByGtfs(gtfs);
        System.out.println(stops.size() + " stops found");

        List<StopTimes> stopTimes = stopTimesRepository.findByGtfs(gtfs);
        System.out.println(stopTimes.size() + " stopTimes found");

        List<Trips> trips = tripsRepository.findByGtfs(gtfs);
        System.out.println(trips.size() + " trips found");

        List<Shapes> shapes = shapesRepository.findByGtfs(gtfs);
        System.out.println(shapes.size() + " shapes found");

        List<CalendarDates> calendarDates = calendarDatesRepository.findByGtfs(gtfs);
        System.out.println(calendarDates.size() + " calendarDates found");

        List<Calendar> calendars = calendarRepository.findByGtfs(gtfs);
        System.out.println(calendars.size() + " calendars found");

        List<Routes> routes = routesRepository.findByGtfs(gtfs);
        System.out.println(routes.size() + " routes found");

        List<Agency> agencies = agencyRepository.findByGtfs(gtfs);
        System.out.println(agencies.size() + " agencies found");

        int batchSize = 100000;

        for (int i = 0; i < stopTimes.size(); i += batchSize) {
            List<StopTimes> batch = stopTimes.subList(i, Math.min(i + batchSize, stopTimes.size()));
            stopTimesRepository.deleteAll(batch);
            System.out.println(batch.size() + " stopTimes deleted");
        }

        for (int i = 0; i < stops.size(); i += batchSize) {
            List<Stops> batch = stops.subList(i, Math.min(i + batchSize, stops.size()));
            stopsRepository.deleteAll(batch);
            System.out.println(batch.size() + " stops deleted");
        }

        for (int i = 0; i < shapes.size(); i += batchSize) {
            List<Shapes> batch = shapes.subList(i, Math.min(i + batchSize, shapes.size()));
            shapesRepository.deleteAll(batch);
            System.out.println(batch.size() + " shapes deleted");
        }

        for (int i = 0; i < calendarDates.size(); i += batchSize) {
            List<CalendarDates> batch = calendarDates.subList(i, Math.min(i + batchSize, calendarDates.size()));
            calendarDatesRepository.deleteAll(batch);
            System.out.println(batch.size() + " calendarDates deleted");
        }

        for (int i = 0; i < calendars.size(); i += batchSize) {
            List<Calendar> batch = calendars.subList(i, Math.min(i + batchSize, calendars.size()));
            calendarRepository.deleteAll(batch);
            System.out.println(batch.size() + " calendars deleted");
        }

        for (int i = 0; i < routes.size(); i += batchSize) {
            List<Routes> batch = routes.subList(i, Math.min(i + batchSize, routes.size()));
            routesRepository.deleteAll(batch);
            System.out.println(batch.size() + " routes deleted");
        }

        for (int i = 0; i < agencies.size(); i += batchSize) {
            List<Agency> batch = agencies.subList(i, Math.min(i + batchSize, agencies.size()));
            agencyRepository.deleteAll(batch);
            System.out.println(batch.size() + " agencies deleted");
        }

        for (int i = 0; i < trips.size(); i += batchSize) {
            List<Trips> batch = trips.subList(i, Math.min(i + batchSize, trips.size()));
            tripsRepository.deleteAll(batch);
            // tripsRepository.deleteBy
            System.out.println(batch.size() + " trips deleted");
        }

        gtfsRepository.delete(gtfs);

        return new ResponseEntity<>("Gtfs deleted", HttpStatus.OK);
    }

    public void processAgency(InputStream agencyStream, Gtfs g) throws IOException {
        String[] required_columns = { "agency_id", "agency_name", "agency_url",
                "agency_timezone" };

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(agencyStream))) {
            String[] header = reader.readLine().split(splitter);
            for (int i = 0; i < header.length; i++) {
                header[i] = header[i].trim();
            }
            // iterate over the headers and remove utf8bom char
            for (int i = 0; i < header.length; i++) {
                header[i] = removeUTF8BOM(header[i]);
            }
            List<Agency> agencies = new ArrayList<>();

            if (Utils.isValid(header, required_columns)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] values = line.split(splitter);
                    Agency agency = new Agency();
                    agency.setGtfs(g);
                    agency.setAgency_id(values[Arrays.asList(header).indexOf("agency_id")]);
                    agency.setAgency_name(values[Arrays.asList(header).indexOf("agency_name")]);
                    agency.setAgency_url(values[Arrays.asList(header).indexOf("agency_url")]);
                    agency.setAgency_timezone(values[Arrays.asList(header).indexOf("agency_timezone")]);

                    agencies.add(agency);
                }
                System.out.println("Saving agencies...");
                agencyRepository.saveAllAndFlush(agencies);
                System.out.println(agencies.size() + " agencies saved");
                System.out.println("==============");
            } else {
                System.out.println("Headers are not valid");
            }
        }
    }

    public void processCalendar(InputStream calendarStream, Gtfs g) throws IOException,
            ParseException {
        String[] required_columns = { "service_id", "monday", "tuesday", "wednesday",
                "thursday", "friday", "saturday",
                "sunday", "start_date", "end_date" };

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(calendarStream))) {
            String[] header = reader.readLine().split(splitter);
            // Skip header
            for (int i = 0; i < header.length; i++) {
                header[i] = header[i].trim();
            }
            // iterate over the headers and remove utf8bom char
            for (int i = 0; i < header.length; i++) {
                header[i] = removeUTF8BOM(header[i]);
            }

            if (Utils.isValid(header, required_columns)) {

                Map<String, Integer> columnIndexMap = new HashMap<>();
                for (int i = 0; i < header.length; i++) {
                    columnIndexMap.put(header[i], i);
                }

                List<Calendar> calendars = reader.lines()
                        .map(line -> line.split(splitter))
                        .map(values -> {
                            for (int i = 0; i < values.length; i++) {
                                values[i] = values[i].trim();
                            }
                            format.setTimeZone(TimeZone.getTimeZone("UTC"));
                            Calendar calendar = new Calendar();
                            try {
                                java.util.Date start_dateUtil = format
                                        .parse(values[Arrays.asList(header).indexOf("start_date")]);
                                java.sql.Date start_date = new java.sql.Date(start_dateUtil.getTime());
                                java.util.Date end_dateUtil;
                                end_dateUtil = format
                                        .parse(values[Arrays.asList(header).indexOf("end_date")]);
                                java.sql.Date end_date = new java.sql.Date(end_dateUtil.getTime());
                                calendar.setStart_date(start_date);
                                calendar.setEnd_date(end_date);
                            } catch (ParseException e) {
                                e.printStackTrace();
                            }

                            calendar.setServiceId(values[columnIndexMap.get("service_id")]);
                            calendar.setMonday(Integer.parseInt(values[columnIndexMap.get("monday")]) == 1);
                            calendar.setTuesday(Integer.parseInt(values[columnIndexMap.get("tuesday")]) == 1);
                            calendar.setWednesday(Integer.parseInt(values[columnIndexMap.get("wednesday")]) == 1);
                            calendar.setThursday(Integer.parseInt(values[columnIndexMap.get("thursday")]) == 1);
                            calendar.setFriday(Integer.parseInt(values[columnIndexMap.get("friday")]) == 1);
                            calendar.setSaturday(Integer.parseInt(values[columnIndexMap.get("saturday")]) == 1);
                            calendar.setSunday(Integer.parseInt(values[columnIndexMap.get("sunday")]) == 1);
                            calendar.setGtfs(g);
                            return calendar;
                        })
                        .collect(Collectors.toList());
                System.out.println("Saving calendars...");
                calendarRepository.saveAll(calendars);
                System.out.println(calendars.size() + " calendars saved");
                System.out.println("==============");
            } else {
                System.out.println("Headers are not valid");
            }
        }
    }

    public void processCalendarDates(InputStream calendarDatesStream, Gtfs g) throws IOException, ParseException {
        String[] required_columns = { "service_id", "date", "exception_type" };

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(calendarDatesStream))) {
            String[] header = reader.readLine().split(splitter);
            // Skip header
            for (int i = 0; i < header.length; i++) {
                header[i] = header[i].trim();
            }
            // iterate over the headers and remove utf8bom char
            for (int i = 0; i < header.length; i++) {
                header[i] = removeUTF8BOM(header[i]);
            }

            if (Utils.isValid(header, required_columns)) {

                String line;
                List<CalendarDates> calendarDatesList = new ArrayList<>();
                java.text.DateFormat dateFormat = new SimpleDateFormat("yyyyMMdd");
                while ((line = reader.readLine()) != null) {
                    String[] values = line.split(splitter);

                    java.util.Date tradeDateUtil = dateFormat.parse(values[Arrays.asList(header).indexOf("date")]);
                    java.sql.Date tradeDate = new java.sql.Date(tradeDateUtil.getTime());

                    // Create a Shapes object and set its properties
                    CalendarDates calendarDates = new CalendarDates();
                    calendarDates.setService_id(values[Arrays.asList(header).indexOf("service_id")]);
                    calendarDates.setDate(tradeDate);
                    calendarDates.setException_type(
                            Integer.parseInt(values[Arrays.asList(header).indexOf("exception_type")]));
                    calendarDates.setGtfs(g);

                    calendarDatesList.add(calendarDates);
                }
                System.out.println("Saving calendar_dates...");
                calendarDatesRepository.saveAll(calendarDatesList);
                System.out.println(calendarDatesList.size() + " calendar_dates saved");
                System.out.println("==============");
            }
        }
    }

    public void processRoutes(InputStream routesStream, Gtfs g) throws IOException {
        String[] required_columns = { "route_id", "route_short_name", "route_type" };

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(routesStream))) {
            String[] header = reader.readLine().split(splitter);
            for (int i = 0; i < header.length; i++) {
                header[i] = header[i].trim();
            }
            // iterate over the headers and remove utf8bom char
            for (int i = 0; i < header.length; i++) {
                header[i] = removeUTF8BOM(header[i]);
            }
            List<Routes> routes = new ArrayList<>();

            if (Utils.isValid(header, required_columns)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] values = line.split(splitter);
                    // trim the values
                    for (int i = 0; i < values.length; i++) {
                        values[i] = values[i].trim();
                    }

                    String agency_id = Arrays.asList(header).contains("agency_id")
                            ? values[Arrays.asList(header).indexOf("agency_id")]
                            : null;

                    Agency agency = agency_id == null ? agencyRepository.findByGtfsId(g)
                            : agencyRepository.findByAgencyIdAndGtfs(agency_id, g.getId());

                    Routes route = new Routes();
                    route.setAgency_id(agency.getAgency_id());
                    route.setAgency(agency);
                    route.setRouteId(values[Arrays.asList(header).indexOf("route_id")]);
                    route.setRoute_short_name(values[Arrays.asList(header).indexOf("route_short_name")]);
                    route.setRoute_type(values[Arrays.asList(header).indexOf("route_type")]);
                    route.setGtfs(g);

                    routes.add(route);
                }
                System.out.println("Saving routes...");
                routesRepository.saveAllAndFlush(routes);
                System.out.println(routes.size() + " routes saved");
                System.out.println("==============");
            }
        }
    }

    public void processShapes(InputStream shapesStream, Gtfs g) throws IOException {
        String[] required_columns = { "shape_id", "shape_pt_lat", "shape_pt_lon", "shape_pt_sequence" };

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(shapesStream))) {
            String[] header = reader.readLine().split(splitter);
            for (int i = 0; i < header.length; i++) {
                header[i] = header[i].trim();
            }
            // iterate over the headers and remove utf8bom char
            for (int i = 0; i < header.length; i++) {
                header[i] = removeUTF8BOM(header[i]);
            }

            List<Shapes> shapesList = new ArrayList<>();
            if (Utils.isValid(header, required_columns)) {
                String line;
                Map<String, List<Coordinate>> shapeCoordinatesMap = new HashMap<>();
                while ((line = reader.readLine()) != null) {
                    String[] values = line.split(splitter);
                    String shapeId = values[Arrays.asList(header).indexOf("shape_id")];
                    Double shapePtLat = Double.parseDouble(values[Arrays.asList(header).indexOf("shape_pt_lat")]);
                    Double shapePtLon = Double.parseDouble(values[Arrays.asList(header).indexOf("shape_pt_lon")]);

                    // Create a coordinate with the lat and lon
                    Coordinate coordinate = new Coordinate(shapePtLon, shapePtLat);

                    // Add the coordinate to the shape coordinates map
                    shapeCoordinatesMap.computeIfAbsent(shapeId, k -> new ArrayList<>()).add(coordinate);
                }

                // Create LineStrings for each shape
                for (Map.Entry<String, List<Coordinate>> entry : shapeCoordinatesMap.entrySet()) {
                    String shapeId = entry.getKey();
                    List<Coordinate> coordinates = entry.getValue().stream()
                            .sorted((c1, c2) -> Double.compare(c1.z, c2.z))
                            .collect(Collectors.toList());

                    // Create a LineString with the coordinates
                    GeometryFactory geometryFactory = new GeometryFactory();
                    org.locationtech.jts.geom.LineString lineString = geometryFactory
                            .createLineString(coordinates.toArray(new Coordinate[0]));

                    // Create a Shapes object and set its properties
                    Shapes shape = new Shapes();
                    shape.setShapeId(shapeId);
                    shape.setGtfs(g);
                    shape.setLinestring(lineString);
                    shapesList.add(shape);
                }
                System.out.println("Saving shapes...");
                shapesRepository.saveAllAndFlush(shapesList);
                System.out.println(shapesList.size() + " shapes saved");
                System.out.println("==============");
            }
        }
    }

    public void processStops(InputStream stopsStream, Gtfs g) throws IOException {
        String[] required_columns = { "stop_id", "stop_name", "stop_lat", "stop_lon"
        };

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stopsStream))) {
            String[] header = reader.readLine().split(splitter);
            for (int i = 0; i < header.length; i++) {
                header[i] = header[i].trim();
            }
            // iterate over the headers and remove utf8bom char
            for (int i = 0; i < header.length; i++) {
                header[i] = removeUTF8BOM(header[i]);
            }

            List<Stops> stopsList = new ArrayList<>();
            if (Utils.isValid(header, required_columns)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // System.out.println(line);
                    String[] values = line.split(splitter);

                    // Create a Stops object and set its properties
                    Stops stop = new Stops();

                    stop.setStopId(values[Arrays.asList(header).indexOf("stop_id")]);
                    stop.setStop_name(values[Arrays.asList(header).indexOf("stop_name")]);
                    stop.setStop_lat(Double.parseDouble(values[Arrays.asList(header).indexOf("stop_lat")]));
                    stop.setStop_lon(Double.parseDouble(values[Arrays.asList(header).indexOf("stop_lon")]));
                    stop.setGtfs(g);

                    stopsList.add(stop);
                }
                System.out.println("Saving stops...");
                stopsRepository.saveAllAndFlush(stopsList);
                System.out.println(stopsList.size() + " stops saved");
                System.out.println("==============");
            }
        }
    }

    public void processTrips(InputStream tripsStream, Gtfs g) throws IOException {
        String[] required_columns = { "route_id", "service_id", "trip_id" };

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(tripsStream))) {
            String[] header = reader.readLine().split(splitter);
            Map<String, Integer> columnMap = new HashMap<>();
            for (int i = 0; i < header.length; i++) {
                header[i] = removeUTF8BOM(header[i]);
                header[i] = header[i].trim();
                columnMap.put(header[i], i);
            }

            List<Trips> tripsList = new ArrayList<>();
            if (Utils.isValid(header, required_columns)) {
                Map<String, Routes> routesMap = new HashMap<>();
                routesRepository.findByGtfs(g).forEach(routes -> routesMap.put(routes.getRouteId(), routes));
                Map<String, Calendar> calendarMap = new HashMap<>();
                calendarRepository.findByGtfs(g)
                        .forEach(calendar -> calendarMap.put(calendar.getServiceId(), calendar));
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] values = line.split(splitter);
                    for (int i = 0; i < values.length; i++) {
                        values[i] = values[i].trim();
                    }
                    Trips trips = new Trips();
                    trips.setTripId(values[Arrays.asList(header).indexOf("trip_id")]);
                    Routes r = routesMap.get(values[columnMap.get("route_id")]);
                    Calendar c = calendarMap.get(values[columnMap.get("service_id")]);
                    try {
                        Shapes s = shapesRepository
                                .findByshapeIdAndGtfs(values[Arrays.asList(header).indexOf("shape_id")], g);
                        trips.setShapes(s);
                    } catch (Exception e) {
                        System.out.println("No shape found");
                    }
                    // Check if has trip_headsign and set it
                    if (Arrays.asList(header).contains("trip_headsign")) {
                        trips.setTripHeadsign(values[Arrays.asList(header).indexOf("trip_headsign")]);
                    }
                    trips.setRoutes(r);
                    trips.setCalendar(c);

                    trips.setGtfs(g);
                    tripsList.add(trips);
                }
                System.out.println("Saving trips...");
                tripsRepository.saveAllAndFlush(tripsList);
                System.out.println(tripsList.size() + " trips saved");
                System.out.println("==============");
            }
        }

    }

    public void processStopTimes(InputStream stopTimesStream, Gtfs g) throws IOException, ParseException {
        String[] required_columns = { "trip_id", "arrival_time", "departure_time", "stop_id", "stop_sequence" };

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stopTimesStream))) {
            String[] header = reader.readLine().split(splitter);
            Map<String, Integer> columnMap = new HashMap<>();
            for (int i = 0; i < header.length; i++) {
                header[i] = removeUTF8BOM(header[i]);
                header[i] = header[i].trim();
                columnMap.put(header[i], i);
            }
            List<StopTimes> stopTimesList = new LinkedList<>();
            if (Utils.isValid(header, required_columns)) {
                // Load all related objects into memory
                Map<String, Trips> tripsMap = new HashMap<>();
                tripsRepository.findByGtfs(g).forEach(trips -> tripsMap.put(trips.getTripId(), trips));
                Map<String, Stops> stopsMap = new HashMap<>();
                stopsRepository.findByGtfs(g).forEach(stops -> stopsMap.put(stops.getStopId(), stops));

                String line;
                while ((line = reader.readLine()) != null) {
                    try {
                        String[] values = line.split(splitter);

                        // Trim the values
                        for (int i = 0; i < values.length; i++) {
                            values[i] = values[i].trim();
                        }
                        // Retrieve related objects from memory
                        Trips t = tripsMap.get(values[columnMap.get("trip_id")]);
                        Stops s = stopsMap.get(values[columnMap.get("stop_id")]);

                        // Create a Shapes object and set its properties
                        StopTimes stopTimes = new StopTimes();

                        // Arrival time and departure time comes like this (10:45:00) and my database
                        DateFormat dateFormat = new SimpleDateFormat("HH:mm:ss");
                        java.util.Date arrival_timeUtil = dateFormat
                                .parse(values[Arrays.asList(header).indexOf("arrival_time")]);
                        java.sql.Time arrival_time = new java.sql.Time(arrival_timeUtil.getTime());
                        java.util.Date departure_timeUtil = dateFormat
                                .parse(values[Arrays.asList(header).indexOf("departure_time")]);
                        java.sql.Time departure_time = new java.sql.Time(departure_timeUtil.getTime());

                        stopTimes.setArrival_time(arrival_time);
                        stopTimes.setDeparture_time(departure_time);
                        stopTimes.setStops(s);
                        stopTimes.setTrips(t);
                        stopTimes
                                .setStop_sequence(
                                        Integer.parseInt(values[Arrays.asList(header).indexOf("stop_sequence")]));
                        stopTimes.setGtfs(g);
                        stopTimesList.add(stopTimes);
                    } catch (Exception e) {
                        // Catch any exceptions and continue to the next iteration
                        System.out.println("Error processing stop_times: " + e.getMessage());
                        continue;
                    }
                }
                System.out.println("Saving stop_times...");
                stopTimesRepository.saveAll(stopTimesList);
                System.out.println(stopTimesList.size() + " stop_times saved");
                System.out.println("==============");
            }
        }
    }

}
