package heliumevents;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URL;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Optional;
import java.util.Scanner;

import javax.annotation.PostConstruct;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.micronaut.context.annotation.Value;
import io.micronaut.core.io.ResourceResolver;
import io.micronaut.core.io.scan.ClassPathResourceLoader;
import jakarta.inject.Singleton;



@Singleton
public class HeliumApi {

    private static final Logger logger = LoggerFactory.getLogger(HeliumApi.class);

    private static final String HS_BASE_STAKEJOY = "https://helium-api.stakejoy.com";
    private static final String HS_BASE_HELIUM = "https://api.helium.io";

    @Value("${micronaut.application.USE_HELIUM_API:false}")
    boolean useHeliumApi;
    @Value("${micronaut.application.USE_STAKEJOY_API:false}")
    boolean useStakejoyApi;

    private String HS_BASE;

    private String HS_ACTIVITY_BASE;
    private String HS_ACTIVITY_CURSOR;
    private String HS_ACTIVITY_DATA;
    private String HS_DETAILS;

    private static final DateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss"); //2021-05-11T01:39:53Z

    @Value("${micronaut.application.USER_AGENT:heliumevents}")
    String USER_AGENT;

    @Value("${micronaut.application.INTERVAL:500}")
    long interval;

    private ClassPathResourceLoader loader = new ResourceResolver().getLoader(ClassPathResourceLoader.class).get();

    private JsonObject hotspotDetails;



    @PostConstruct
    public void config() throws IOException {

        int lineNum = (int) (Math.random() * 300);
        Optional<URL> resource = loader.getResource("classpath:useragents.txt");
        Scanner s = new Scanner(resource.get().openStream());
        for(int i=0; i < lineNum; i++) s.nextLine();
        USER_AGENT = s.nextLine();
        logger.debug("Using user agent {} '{}'", lineNum, USER_AGENT);

        if(useHeliumApi && useStakejoyApi) throw new IllegalStateException("Can't use both APIs - choose one");
        if( ! useHeliumApi && ! useStakejoyApi) useHeliumApi = true;

        if(useStakejoyApi) 
            HS_BASE = HS_BASE_STAKEJOY;
        else
            HS_BASE = HS_BASE_HELIUM;

        logger.info("Using {} API", useHeliumApi ? "Helium" : "Stakejoy");

        HS_ACTIVITY_BASE = HS_BASE+"/v1/hotspots/%s/activity";
        HS_ACTIVITY_CURSOR = HS_ACTIVITY_BASE + "?min_time=%s&max_time=%s";
        HS_ACTIVITY_DATA = HS_ACTIVITY_BASE + "?cursor=%s";
        HS_DETAILS = HS_BASE+"/v1/hotspots/%s";

    }

    private String sendRequest(String absoluteUrl) throws HeliumApiException {
        long start = System.currentTimeMillis();
        logger.debug("Calling {}", absoluteUrl);
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpGet get = new HttpGet(absoluteUrl);
            get.addHeader("User-Agent", USER_AGENT);
            get.addHeader("Content-Type", "application/json");
            get.addHeader("Accept", "application/json");
            get.addHeader("cache-control", "no-cache");
            get.addHeader("pragma", "no-cache");
            try (CloseableHttpResponse response = client.execute(get)) {
                if(response.getCode() != HttpStatus.SC_OK) throw new HeliumApiException("Bad code: "+response.getCode());
                String body = EntityUtils.toString(response.getEntity());
                if(logger.isTraceEnabled()) {
                    logger.trace("<- {}", body);
                }
                if(logger.isDebugEnabled()) {
                    logger.trace("Timing: Helium call took {}s", ((System.currentTimeMillis()-start)/1000));
                }
                return body;
            }
        }
        catch(IOException | ParseException e) {
            throw new HeliumApiException("Could not fetch "+absoluteUrl, e);
        }
    }


    public String getHotspotName(String hotspotAddress) throws HeliumApiException {
        if(null == hotspotDetails) initHotspotDetails(hotspotAddress);
        return hotspotDetails.get("data").getAsJsonObject().get("name").getAsString();
    }

    public DateTime getHotspotBirithday(String hotspotAddress) throws HeliumApiException {
        if(null == hotspotDetails) initHotspotDetails(hotspotAddress);
        String timestamp = hotspotDetails.get("data").getAsJsonObject().get("timestamp_added").getAsString();
        return DateTime.parse(timestamp); // "2021-09-20T11:22:46.000000Z"
    }

    private void initHotspotDetails(String hotspotAddress) throws HeliumApiException {
        String url = String.format(HS_DETAILS, hotspotAddress);
        String json = sendRequest(url);
        hotspotDetails = JsonParser.parseString(json).getAsJsonObject();
    }

    /**
     * Setup a cursor in the API to pull data from in subsequent methods. All the params are specified here - we
     * pull data back in the other methods
     * @param hotspotAddress
     * @param date
     * @return
     * @throws UnsupportedEncodingException
     * @throws IOException
     * @throws InterruptedException
     */
    JsonObject fetchHotspotActivityForDate(String hotspotAddress, DateTime date) throws HeliumApiException {
        // Build the URL
        String min_time = format.format(date.plusDays(-1).withTime(23, 59, 59, 0).toDate());

        DateTime endTimeDate = date.withTime(23, 59, 59, 0);
        if(endTimeDate.isAfter(new DateTime())) {
            logger.debug("Adjusting end time to [now]");
            endTimeDate = new DateTime().minusMinutes(1);
        }
        String max_time = format.format(endTimeDate.toDate());
        String url = String.format(HS_ACTIVITY_CURSOR, hotspotAddress, min_time, max_time);

        // Process the response and extract the cursor hash
        String json = sendRequest(url);


        JsonObject jsObj = JsonParser.parseString(json).getAsJsonObject();
        if(jsObj.has("error")) {
            throw new HeliumApiException(jsObj.get("error").getAsString());
        }

        return jsObj;
    }

    public JsonObject fetchTransactions(String hotspotAddress, String cursor, String hotspotName) throws HeliumApiException {
        // Fetch the data
        String url = String.format(HS_ACTIVITY_DATA, hotspotAddress, cursor);
        String json = sendRequest(url);

        // Process the transactions
        return JsonParser.parseString(json).getAsJsonObject();
    }
}