/*
 * Copyright 2014 JBoss Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.optaplanner.extension.vrpdatasetgenerator;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.HashMultiset;
import com.google.common.collect.Multiset;
import com.google.common.collect.Multisets;
import com.graphhopper.GHRequest;
import com.graphhopper.GHResponse;
import com.graphhopper.GraphHopper;
import com.graphhopper.PathWrapper;
import com.graphhopper.reader.osm.GraphHopperOSM;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.util.PointList;
import org.apache.commons.io.IOUtils;
import org.optaplanner.examples.common.app.LoggingMain;
import org.optaplanner.examples.vehiclerouting.domain.location.AirLocation;

/**
 * This is very quick and VERY DIRTY code.
 * Its results are also not that good.
 */
public class BelgiumHubSuggester extends LoggingMain {

    public static void main(String[] args) {
        new BelgiumHubSuggester().suggest();
    }

    private final GraphHopperOSM graphHopper;

    public BelgiumHubSuggester() {
        graphHopper = (GraphHopperOSM) new GraphHopperOSM().forServer();
        String osmPath = "local/osm/belgium-latest.osm.pbf";
        if (!new File(osmPath).exists()) {
            throw new IllegalStateException("The osmPath (" + osmPath + ") does not exist.\n" +
                    "Download the osm file from http://download.geofabrik.de/ first.");
        }
        graphHopper.setOSMFile(osmPath);
        graphHopper.setGraphHopperLocation("local/graphhopper");
        graphHopper.setEncodingManager(new EncodingManager("car"));
        graphHopper.importOrLoad();
        logger.info("GraphHopper loaded.");
    }

    public void suggest() {
        suggest(new File("data/raw/belgium-cities.csv"), 200, new File("data/raw/suggested-belgium-hubs.txt"), 30);
    }

    public void suggest(File locationFile, int locationListSize, File outputFile, int hubSize) {
        // WARNING: this code is VERY DIRTY.
        // It's JUST good enough to generate the hubs for Belgium once (because we only need to determine them once).
        // Further research to generate good hubs is needed.
        List<AirLocation> locationList = readAirLocationFile(locationFile);
        if (locationListSize > locationList.size()) {
            throw new IllegalArgumentException("The locationListSize (" + locationListSize
                    + ") is larger than the locationList size (" + locationList.size() + ").");
        }
        locationList = subselectLocationList(locationListSize, locationList);
        Map<Point, Point> fromPointMap = new LinkedHashMap<Point, Point>(locationListSize * 10);
        Map<Point, Point> toPointMap = new LinkedHashMap<Point, Point>(locationListSize * 10);
        int rowIndex = 0;
        double maxAirDistance = 0.0;
        for (AirLocation fromAirLocation : locationList) {
            for (AirLocation toAirLocation : locationList) {
                double airDistance = fromAirLocation.getAirDistanceDoubleTo(toAirLocation);
                if (airDistance > maxAirDistance) {
                    maxAirDistance = airDistance;
                }
            }
        }
        double airDistanceThreshold = maxAirDistance / 10.0;
        for (AirLocation fromAirLocation : locationList) {
            for (AirLocation toAirLocation : locationList) {
                double distance;
                if (fromAirLocation != toAirLocation) {
                    GHRequest request = new GHRequest(fromAirLocation.getLatitude(), fromAirLocation.getLongitude(),
                            toAirLocation.getLatitude(), toAirLocation.getLongitude())
                            .setVehicle("car");
                    GHResponse response = graphHopper.route(request);
                    if (response.hasErrors()) {
                        throw new IllegalStateException("GraphHopper gave " + response.getErrors().size()
                                + " errors. First error chained.",
                                response.getErrors().get(0)
                        );
                    }
                    // Distance should be in km, not meter
                    PathWrapper path = response.getBest();
                    distance = path.getDistance() / 1000.0;
                    if (distance == 0.0) {
                        throw new IllegalArgumentException("The fromAirLocation (" + fromAirLocation
                                + ") and toAirLocation (" + toAirLocation + ") are the same.");
                    }
                    PointList ghPointList = path.getPoints();
                    PointPart previousFromPointPart = null;
                    PointPart previousToPointPart = null;
                    double previousLatitude = Double.NaN;
                    double previousLongitude = Double.NaN;
                    for (int i = 0; i < ghPointList.size(); i++) {
                        double latitude = ghPointList.getLatitude(i);
                        double longitude = ghPointList.getLongitude(i);
                        if (latitude == previousLatitude && longitude == previousLongitude) {
                            continue;
                        }
                        if (calcAirDistance(latitude, longitude,
                                fromAirLocation.getLatitude(), fromAirLocation.getLongitude()) < airDistanceThreshold) {
                            Point fromPoint = new Point(latitude, longitude);
                            Point oldFromPoint = fromPointMap.get(fromPoint);
                            if (oldFromPoint == null) {
                                // Initialize fromPoint instance
                                fromPoint.pointPartMap = new LinkedHashMap<AirLocation, PointPart>();
                                fromPointMap.put(fromPoint, fromPoint);
                            } else {
                                // Reuse existing fromPoint instance
                                fromPoint = oldFromPoint;
                            }
                            PointPart fromPointPart = fromPoint.pointPartMap.get(fromAirLocation);
                            if (fromPointPart == null) {
                                fromPointPart = new PointPart(fromPoint, fromAirLocation);
                                fromPoint.pointPartMap.put(fromAirLocation, fromPointPart);
                                fromPointPart.previousPart = previousFromPointPart;
                            }
                            fromPointPart.count++;
                            previousFromPointPart = fromPointPart;
                        }
                        if (calcAirDistance(latitude, longitude,
                                toAirLocation.getLatitude(), toAirLocation.getLongitude()) < airDistanceThreshold) {
                            Point toPoint = new Point(latitude, longitude);
                            Point oldToPoint = toPointMap.get(toPoint);
                            if (oldToPoint == null) {
                                // Initialize toPoint instance
                                toPoint.pointPartMap = new LinkedHashMap<AirLocation, PointPart>();
                                toPointMap.put(toPoint, toPoint);
                            } else {
                                // Reuse existing toPoint instance
                                toPoint = oldToPoint;
                            }
                            // Basically do the same as fromPointPart, but while traversing in the other direction
                            PointPart toPointPart = toPoint.pointPartMap.get(toAirLocation);
                            boolean newToPointPart = false;
                            if (toPointPart == null) {
                                toPointPart = new PointPart(toPoint, toAirLocation);
                                toPoint.pointPartMap.put(toAirLocation, toPointPart);
                                newToPointPart = true;
                            }
                            if (previousToPointPart != null) {
                                previousToPointPart.previousPart = toPointPart;
                            }
                            toPointPart.count++;
                            if (newToPointPart) {
                                previousToPointPart = toPointPart;
                            } else {
                                previousToPointPart = null;
                            }
                        }
                        previousLatitude = latitude;
                        previousLongitude = longitude;
                    }
                }
            }
            logger.debug("  Finished routes for rowIndex {}/{}", rowIndex, locationList.size());
            rowIndex++;
        }
        Set<Point> hubPointList = new LinkedHashSet<Point>(20);
        extractFromHubs(new ArrayList<Point>(fromPointMap.values()), hubPointList, (hubSize + 1) / 2);
        extractFromHubs(new ArrayList<Point>(toPointMap.values()), hubPointList, hubSize / 2);
        logger.info("Writing hubs...");
        BufferedWriter vrpWriter = null;
        try {
            vrpWriter = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(outputFile), "UTF-8"));
            vrpWriter.write("HUB_COORD_SECTION\n");
            int id = 0;
            for (Point point : hubPointList) {
                vrpWriter.write("" + id + " " + point.latitude + " " + point.longitude + " " + id + "\n");
                id++;
            }
            vrpWriter.write("\n\nGOOGLE MAPS\n");
            for (Point point : hubPointList) {
                vrpWriter.write("" + id + "\t0\t" + point.latitude + "," + point.longitude + "\n");
                id++;
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not read the locationFile (" + locationFile.getName()
                    + ") or write the vrpOutputFile (" + outputFile.getName() + ").", e);
        } finally {
            IOUtils.closeQuietly(vrpWriter);
        }
        // Throw in google docs spreadsheet and use add-on Mapping Sheets to visualize.
    }

    private void extractFromHubs(List<Point> pointList, Set<Point> hubPointList, int hubSize) {
//        logger.info("Filtering points below threshold...");
//        fromPointMap = null;
//        int THRESHOLD = 10;
//        for (Iterator<Point> it = pointList.iterator(); it.hasNext(); ) {
//            Point point = it.next();
//            if (point.pointPartMap.values().size() < THRESHOLD) {
//                it.remove();
//                point.removed = true;
//            }
//        }
//        for (Point point : pointList) {
//            for (PointPart pointPart : point.pointPartMap.values()) {
//                PointPart previousPart = pointPart.previousPart;
//                while (previousPart != null && previousPart.point.removed) {
//                    previousPart = previousPart.previousPart;
//                }
//                pointPart.previousPart = previousPart;
//            }
//        }
        logger.info("Extracting hubs...");
        for (int i = 0; i < hubSize; i++) {
            logger.info("  {} / {} with remaining pointListSize ({})", i, hubSize, pointList.size());
            // Make the biggest merger of 2 big streams into 1 stream a hub.
            int maxCount = -1;
            Point maxCountPoint = null;
            for (Point point : pointList) {
                int count = 0;
                for (PointPart pointPart : point.pointPartMap.values()) {
                    count += pointPart.count;
                }
                if (count > maxCount) {
                    maxCount = count;
                    maxCountPoint = point;
                }
            }
            if (maxCountPoint == null) {
                throw new IllegalStateException("No maxCountPoint (" + maxCountPoint + ") found.");
            }
            maxCountPoint.hub = true;
            pointList.remove(maxCountPoint);
            hubPointList.add(maxCountPoint);
            // Remove trailing parts
            for (Iterator<Point> pointIt = pointList.iterator(); pointIt.hasNext(); ) {
                Point point = pointIt.next();
                for (Iterator<PointPart> partIt = point.pointPartMap.values().iterator(); partIt.hasNext(); ) {
                    PointPart pointPart = partIt.next();
                    if (pointPart.comesAfterHub()) {
                        partIt.remove();
                    }
                }
                if (point.pointPartMap.isEmpty()) {
                    point.removed = true;
                    pointIt.remove();
                }
            }
            // Subtract prefix parts
            for (PointPart pointPart : maxCountPoint.pointPartMap.values()) {
                PointPart ancestorPart = pointPart.previousPart;
                while (ancestorPart != null) {
                    ancestorPart.count -= pointPart.count;
//                    if (ancestorPart.count < 0) {
//                        throw new IllegalStateException("Impossible state"); // TODO FIXME Does happen! Probably because some paths hit the same point twice at different elevation
//                    }
                    if (ancestorPart.count <= 0) {
                        ancestorPart.point.pointPartMap.remove(ancestorPart.anchor);
                    }
                    ancestorPart = ancestorPart.previousPart;
                }
            }
        }
    }

    private List<AirLocation> subselectLocationList(double locationListSize, List<AirLocation> locationList) {
        double selectionDecrement = locationListSize / (double) locationList.size();
        double selection = locationListSize;
        int index = 1;
        List<AirLocation> newAirLocationList = new ArrayList<AirLocation>(locationList.size());
        for (AirLocation location : locationList) {
            double newSelection = selection - selectionDecrement;
            if ((int) newSelection < (int) selection) {
                newAirLocationList.add(location);
                index++;
            }
            selection = newSelection;
        }
        locationList = newAirLocationList;
        return locationList;
    }

    private List<AirLocation> readAirLocationFile(File locationFile) {
        List<AirLocation> locationList = new ArrayList<AirLocation>(3000);
        BufferedReader bufferedReader = null;
        long id = 0L;
        try {
            bufferedReader = new BufferedReader(new InputStreamReader(new FileInputStream(locationFile), "UTF-8"));
            for (String line = bufferedReader.readLine(); line != null; line = bufferedReader.readLine()) {
                String[] tokens = line.split(";");
                if (tokens.length != 5) {
                    throw new IllegalArgumentException("The line (" + line + ") does not have 5 tokens ("
                            + tokens.length + ").");
                }
                AirLocation location = new AirLocation();
                location.setId(id);
                id++;
                location.setLatitude(Double.parseDouble(tokens[2]));
                location.setLongitude(Double.parseDouble(tokens[3]));
                location.setName(tokens[4]);
                locationList.add(location);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not read the locationFile (" + locationFile + ").", e);
        } finally {
            IOUtils.closeQuietly(bufferedReader);
        }
        logger.info("Read {} cities.", locationList.size());
        return locationList;
    }

    public static double calcAirDistance(double lat1, double long1, double lat2, double long2) {
        double latitudeDifference = lat2 - lat1;
        double longitudeDifference = long2 - long1;
        return Math.sqrt(
                (latitudeDifference * latitudeDifference) + (longitudeDifference * longitudeDifference));
    }

    private static class Point {

        public final double latitude;
        public final double longitude;

        public Map<AirLocation, PointPart> pointPartMap;

        public boolean removed = false;
        public boolean hub = false;

        public Point(double latitude, double longitude) {
            this.latitude = latitude;
            this.longitude = longitude;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            Point point = (Point) o;
            if (Double.compare(point.latitude, latitude) != 0) {
                return false;
            }
            if (Double.compare(point.longitude, longitude) != 0) {
                return false;
            }
            return true;
        }

        @Override
        public int hashCode() {
            int result;
            long temp;
            temp = Double.doubleToLongBits(latitude);
            result = (int) (temp ^ (temp >>> 32));
            temp = Double.doubleToLongBits(longitude);
            result = 31 * result + (int) (temp ^ (temp >>> 32));
            return result;
        }

        @Override
        public String toString() {
            return "" + latitude + "," + longitude;
        }

    }

    private static class PointPart {

        public final Point point;
        public final AirLocation anchor;
        public PointPart previousPart;
        public int count = 0;

        public PointPart(Point point, AirLocation anchor) {
            this.point = point;
            this.anchor = anchor;
        }

        public boolean comesAfterHub() {
            PointPart ancestorPart = previousPart;
            while (true) {
                if (ancestorPart == null) {
                    return false;
                }
                if (ancestorPart.point.hub) {
                    return true;
                }
                ancestorPart = ancestorPart.previousPart;
            }
        }

        @Override
        public String toString() {
            return point + "-" + anchor.getName();
        }
    }

}
