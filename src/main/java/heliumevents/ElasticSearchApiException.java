package heliumevents;

public class ElasticSearchApiException extends Exception {

    public ElasticSearchApiException(String message) {
        super(message);
    }

    public ElasticSearchApiException(Throwable cause) {
        super(cause);
    }

    public ElasticSearchApiException(String message, Throwable cause) {
        super(message, cause);
    }

    
}
