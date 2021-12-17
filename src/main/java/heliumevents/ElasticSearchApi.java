package heliumevents;

import java.io.IOException;
import java.nio.charset.Charset;

import javax.annotation.PostConstruct;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpHead;
import org.apache.hc.client5.http.classic.methods.HttpPut;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;

@Singleton
public class ElasticSearchApi {

    private static final Logger logger = LoggerFactory.getLogger(ElasticSearchApi.class);

    private String elasticSearchUrlTemplate = "/%s/_doc/%s";

    private String BASE;

    @PostConstruct
    public void setup(@Value("${micronaut.application.ES_SERVER_ADDRESS}") String base_url) {
        logger.info("Using ElasticSearch at {}", base_url);
        BASE = base_url;
    }


    public void putDoc(String indexName, String docIdentifier, String docStr) throws ElasticSearchApiException {
        
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            String url = String.format(elasticSearchUrlTemplate, indexName, docIdentifier);
            HttpPut put = new HttpPut(BASE+url);
            put.setHeader("Content-Type", "application/json");
            put.setHeader("Accept", "application/json");
            put.setEntity(new StringEntity(docStr, Charset.forName("utf-8")));
            try(CloseableHttpResponse resp = client.execute(put)) {
                if(resp.getCode() != HttpStatus.SC_CREATED && resp.getCode() != HttpStatus.SC_OK) {
                    throw new ElasticSearchApiException(resp.getReasonPhrase() + ":"+resp.getCode() + "\nDetails\n" + EntityUtils.toString(resp.getEntity()));
                }
            }
        }
        catch(IOException | ParseException e) {
            throw new ElasticSearchApiException("Could not PUT "+indexName+":"+docIdentifier, e);
        }
    }

    public void putDocRaw(String relativeUrl, String docStr) throws ElasticSearchApiException {
        
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpPut put = new HttpPut(BASE+relativeUrl);
            put.setHeader("Content-Type", "application/json");
            put.setHeader("Accept", "application/json");
            put.setEntity(new StringEntity(docStr, Charset.forName("utf-8")));
            try(CloseableHttpResponse resp = client.execute(put)) {
                if(resp.getCode() != HttpStatus.SC_CREATED && resp.getCode() != HttpStatus.SC_OK) {
                    throw new ElasticSearchApiException(resp.getReasonPhrase() + ":"+resp.getCode());
                }
            }
        }
        catch(IOException e) {
            throw new ElasticSearchApiException("Could not PUT "+relativeUrl, e);
        }
    }

    public JsonObject getDoc(String indexName, String docIdentifier) throws ElasticSearchApiException {
        
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            String relativeUrl = String.format(elasticSearchUrlTemplate, indexName, docIdentifier);
            HttpGet get = new HttpGet(BASE+relativeUrl);
            try (CloseableHttpResponse response = client.execute(get)) {
                if(response.getCode() == HttpStatus.SC_OK) {
                    return JsonParser.parseString(EntityUtils.toString(response.getEntity())).getAsJsonObject().get("_source").getAsJsonObject();
                }
            }
        }
        catch(IOException | ParseException | JsonSyntaxException e) {
            throw new ElasticSearchApiException("Could not fetch "+indexName+":"+docIdentifier, e);
        }

        return null;
    }

    public String getRaw(String relativeUrl) throws ElasticSearchApiException {
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpGet get = new HttpGet(BASE+relativeUrl);
            try (CloseableHttpResponse response = client.execute(get)) {
                if(response.getCode() != HttpStatus.SC_OK) throw new ElasticSearchApiException("Bad code: "+response.getCode());
                return EntityUtils.toString(response.getEntity());
            }
        }
        catch(IOException | ParseException e) {
            throw new ElasticSearchApiException("Could not fetch "+relativeUrl, e);
        }
    }

    public boolean exists(String indexName, String docIdentifier) throws ElasticSearchApiException {
        
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            String relativeUrl = String.format(elasticSearchUrlTemplate, indexName, docIdentifier);
            HttpHead HEAD = new HttpHead(BASE+relativeUrl);
            try (CloseableHttpResponse response = client.execute(HEAD)) {
                return response.getCode() == 200;
            }
        }
        catch(IOException e) {
            throw new ElasticSearchApiException("Could not HEAD "+indexName+":"+docIdentifier, e);
        }
    }
}
