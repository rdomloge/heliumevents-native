package heliumevents;

import javax.annotation.PostConstruct;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.micronaut.context.annotation.Value;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientException;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.http.client.exceptions.ReadTimeoutException;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

@Singleton
public class ElasticSearchApi {

    private static final Logger logger = LoggerFactory.getLogger(ElasticSearchApi.class);

    @Client("${micronaut.application.ES_SERVER_ADDRESS}") @Inject HttpClient httpClient;

    private String elasticSearchUrlTemplate = "/%s/_doc/%s";

    @PostConstruct
    public void setup(@Value("${micronaut.application.ES_SERVER_ADDRESS}") String base_url) {
        logger.info("Using ElasticSearch at {}", base_url);
    }


    public void postDoc(String indexName, String docIdentifier, String docStr) throws ElasticSearchApiException {
        try {
            // Post the document to ElasticSearch
            String url = String.format(elasticSearchUrlTemplate, indexName, docIdentifier);
            HttpRequest<?> req = HttpRequest.PUT(url, docStr).contentType(MediaType.APPLICATION_JSON_TYPE);
            httpClient.exchange(req);
        }
        catch(HttpClientResponseException e) {
            throw new ElasticSearchApiException("Could not fetch "+indexName+":"+docIdentifier, e);
        }
    }

    public void postDocRaw(String relativeUrl, String docStr) throws ElasticSearchApiException {
        try {
            // Post the document to ElasticSearch
            HttpRequest<?> req = HttpRequest.PUT(relativeUrl, docStr).contentType(MediaType.APPLICATION_JSON_TYPE);
            httpClient.exchange(req);
        }
        catch(HttpClientResponseException e) {
            throw new ElasticSearchApiException("Could not fetch "+relativeUrl, e);
        }
    }

    public JsonObject getDoc(String indexName, String docIdentifier) throws ElasticSearchApiException {
        try {
            HttpResponse<String> response = 
                httpClient.toBlocking().exchange(String.format(elasticSearchUrlTemplate, indexName, docIdentifier), String.class);

            if(response.getStatus() == HttpStatus.OK) {
                return JsonParser.parseString(response.body()).getAsJsonObject().get("_source").getAsJsonObject();
            }
        }
        catch(HttpClientResponseException e) {
            throw new ElasticSearchApiException("Could not fetch "+indexName+":"+docIdentifier, e);
        }

        return null;
    }

    public HttpResponse<String> getRaw(String relativeUrl) throws ElasticSearchApiException {
        try {
            return httpClient.toBlocking().exchange(relativeUrl, String.class);
        }
        catch(HttpClientResponseException e) {
            throw new ElasticSearchApiException("Could not fetch "+relativeUrl, e);
        }
    }

    public boolean exists(String indexName, String docIdentifier) {
        try {
            HttpRequest<?> req = HttpRequest.HEAD(String.format(elasticSearchUrlTemplate, indexName, docIdentifier));
            HttpResponse<Object> resp = httpClient.toBlocking().exchange(req);
            return resp.status() == HttpStatus.OK;
        }
        catch(HttpClientResponseException e) {
            return false;
        }
    }
}
