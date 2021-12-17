package heliumevents;

import java.math.BigDecimal;

import javax.annotation.PostConstruct;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.micronaut.context.annotation.Value;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

@Singleton
public class Trawler {

    private static final Logger logger = LoggerFactory.getLogger(Trawler.class);

    @Inject
    private HeliumApi heliumApi;

    @Inject
    private ElasticSearchApi esApi;

    @Value("${micronaut.application.HOTSPOT}")
    String hotspot;

    private String hotspotName;

    @PostConstruct
    void prep() throws ElasticSearchApiException {
        
        try {
            hotspotName = heliumApi.getHotspotName(hotspot);
        } 
        catch (HeliumApiException hex) {
            logger.error("Could not resolve hotspot name", hex);
            handleError(hex);
            return;
        }
        
        // check that the metadata index exists
        try {
            esApi.getRaw("/metadataindex/_doc/"+hotspotName);
        }
        catch(ElasticSearchApiException esex) {
            logger.info("Setting up metadata index");
            esApi.putDoc("metadataindex", hotspotName, "{ \"position\": 0 }");
        }

        // create mapping for timestamp in documents
        try {
            esApi.getRaw("/"+hotspotName+"/_mapping");
        }
        catch(ElasticSearchApiException esex) {
            createMapptings();
        }
    }

    private void createMapptings() throws ElasticSearchApiException {
        JsonObject payload = new JsonObject();
        JsonObject mappings = new JsonObject();
        payload.add("mappings", mappings);
        JsonObject properties = new JsonObject();
        mappings.add("properties", properties);

        JsonObject timeMapping = new JsonObject();
        timeMapping.addProperty("type", "date");
        properties.add("time", timeMapping);

        JsonObject challengeeLocationMapping = new JsonObject();
        challengeeLocationMapping.addProperty("type", "geo_point");
        properties.add("path.challengee_location", challengeeLocationMapping);

        logger.info("Setting up mappings for time (date field) and path.challengee_location (geo_point)");
        String json = new Gson().toJson(payload);
        esApi.putDocRaw("/" + hotspotName, json);
    }


    public void trawl() throws ElasticSearchApiException {
        try {
            DateTime hsBday = heliumApi.getHotspotBirithday(hotspot);
            DateTime latestTrawlCompleteDay = getLatestSuccessfulTrawlCompleteDay(hotspotName);
            if(null == latestTrawlCompleteDay) latestTrawlCompleteDay = hsBday;
            
            logger.info("Synching from {} for hotspot {}, born on {}", latestTrawlCompleteDay.toString("dd-MMM-yyyy"), hotspotName, hsBday.toString("dd-MMM-yyyy' 'hh:mm"));

            DateTime dateCursor = latestTrawlCompleteDay.withTime(0, 0, 0, 0);
            
            while(dateCursor.isBefore(new DateTime())) {
                logger.debug("Fetching events for {}", dateCursor.toString("dd-MMM-yyyy"));
                Stats stats = new Stats();
                int transactionCount = 0;
                JsonObject response = heliumApi.fetchHotspotActivityForDate(hotspot, dateCursor);
                if(response.has("data")) {
                    transactionCount += processData(response.getAsJsonArray("data"), stats);
                }

                while(response.has("cursor")) {
                    response = heliumApi.fetchTransactions(
                        hotspot, 
                        response.get("cursor").getAsString(),
                        hotspotName);
                    transactionCount += processData((JsonArray) response.get("data"), stats);
                }
                
                logger.info("{} processed, fetched {} transactions: {} new, {} already known", 
                    dateCursor.toString("dd-MMM-yyyy"), 
                    transactionCount,
                    stats.getNewDocs(),
                    stats.getDuplicateDocs());

                storeMetadata(hotspotName, dateCursor);
                dateCursor = dateCursor.plusDays(1);
            }
            logger.info("Synch complete");
        }
        catch(HeliumApiException hex) {
            handleError(hex);
        }
    }

    private void handleError(HeliumApiException hex) {
        logger.info("**************************");
        String message = hex.getCause() != null ? hex.getCause().getMessage() : hex.getMessage();
        logger.info("Critical error calling Helium API: {}", message);
        if(logger.isDebugEnabled()) {
            logger.error("Error details:\n", hex);
        }
    }

    private int processData(JsonArray transactions, Stats stats) throws ElasticSearchApiException {
        for(int i=0; i < transactions.size(); i++) {
            JsonObject doc = transactions.get(i).getAsJsonObject();
            patch(doc);

            String identifier = doc.get("hash").getAsString();
            if( ! esApi.exists(hotspotName, identifier)) {
                String docStr = new Gson().toJson(doc);
                logger.trace(docStr);
                esApi.putDoc(hotspotName, identifier, docStr);
                stats.incrementNewDocs();
            }
            else {
                stats.incrementDuplicateDocs();
            }
        }
        return transactions.size();
    }

    private static final String LAST_RUN_DATE = "lastRun";

    private void storeMetadata(String hotspotName, DateTime lastRunDate) throws ElasticSearchApiException {
        JsonObject metadata = new JsonObject();
        metadata.add(LAST_RUN_DATE, new JsonPrimitive(lastRunDate.getMillis()));
        metadata.add(LAST_RUN_DATE+"HumanReadable", new JsonPrimitive(lastRunDate.toString()));
        esApi.putDoc("metadataindex", hotspotName, metadata.toString());
    }

    private DateTime getLatestSuccessfulTrawlCompleteDay(String hotspotName) throws ElasticSearchApiException {
        JsonObject metadata = esApi.getDoc("metadataindex", hotspotName);
        if(null == metadata || ! metadata.has(LAST_RUN_DATE)) return null;
        return new DateTime(metadata.get(LAST_RUN_DATE).getAsLong());
    }

    private void patch(JsonObject heliumDoc) {
        // add zeros to the time to make it epoc millis instead of seconds
        long timeSeconds = heliumDoc.get("time").getAsLong();
        DateTime epoch = new DateTime(timeSeconds*1000);
        String esFormatTimestamp = epoch.toString("YYYY-MM-dd'T'HH:mm:ssZ");
        heliumDoc.add("time", new JsonPrimitive(esFormatTimestamp));

        if(heliumDoc.get("type").getAsString().equals("poc_receipts_v1")) patchChallengeeLocation(heliumDoc);
        if(heliumDoc.get("type").getAsString().equals("rewards_v2")) patchHNT(heliumDoc);
    }

    private void patchHNT(JsonObject heliumDoc) {
        JsonArray rewards = heliumDoc.get("rewards").getAsJsonArray();
        long totalBones = 0;
        for(int i=0; i < rewards.size(); i++) {
            totalBones += rewards.get(i).getAsJsonObject().get("amount").getAsLong();
        }
        heliumDoc.addProperty("totalBones", totalBones);
        heliumDoc.addProperty("totalHnt", new BigDecimal(totalBones).divide(new BigDecimal(100000000)));
    }

    private void patchChallengeeLocation(JsonObject heliumDoc) {
        JsonObject path = heliumDoc.get("path").getAsJsonArray().get(0).getAsJsonObject();
        float lon = -1; 
        float lat = -1;
        if(path.has("challengee_lon")) {
            lon = path.get("challengee_lon").getAsFloat();
        }
        if(path.has("challengee_lat")) {
            lat = path.get("challengee_lat").getAsFloat();
        }
        path.add("challengee_location", new JsonPrimitive(lat+","+lon));
    }

}
