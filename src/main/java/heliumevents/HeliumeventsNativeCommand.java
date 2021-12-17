package heliumevents;

import io.micronaut.configuration.picocli.PicocliRunner;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "heliumevents-native", description = "...",
        mixinStandardHelpOptions = true)
public class HeliumeventsNativeCommand implements Runnable {

    @Option(names = {"-v", "--verbose"}, description = "...")
    boolean verbose;

    @Inject
    Trawler trawler;

    public static void main(String[] args) throws Exception {

        PicocliRunner.run(HeliumeventsNativeCommand.class, args);
    }

    public void run() {
        
        try {
            trawler.trawl();
        } 
        catch (ElasticSearchApiException e) {
            System.out.println("Could not work with ES");
            e.printStackTrace();
        }
    }
}
