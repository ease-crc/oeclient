package example.easeclient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.openease.client.BridgeClient;
import org.openease.client.EASEError;

import org.apache.commons.cli.*;

/**
 * This is an example for the usage of the API client for open-ease.org
 * 
 * @author Moritz Horstmann <mhorst@cs.uni-bremen.de>
 *
 */
public class ExampleClient {
    static String OE_HOST_DEFAULT  = "https://localhost";
    
    public static void main(String[] args)
    throws InterruptedException, EASEError, IOException, ParseException
    {
        Options options = new Options();

        Option token_option = new Option("t", "token", true, "openEASE authentication token");
        token_option.setRequired(true);
        options.addOption(token_option);

        Option ssl_option = new Option("s", "ssl", true, "openEASE SSL pem file");
        ssl_option.setRequired(false);
        options.addOption(ssl_option);

        Option host_option = new Option("h", "host", true, "openEASE host");
        host_option.setRequired(false);
        options.addOption(host_option);

        Option query_option = new Option("q", "query", true, "the query");
        query_option.setRequired(false);
        options.addOption(query_option);
        
        CommandLineParser parser = new DefaultParser();
        HelpFormatter formatter = new HelpFormatter();
        CommandLine cmd = parser.parse(options, args);
        // e.g. oe_token="E84O1GRNURm0yHsqnSRInjUVXzf58Pz6WIEiMhoSuuoLvtetUzj2idiIcHuKqACf"
        String oe_token = cmd.getOptionValue("token");
        // e.g. oe_pem="/home/daniel/localhost.pem"
        String oe_pem   = cmd.getOptionValue("ssl");
        String oe_host  = cmd.getOptionValue("host", OE_HOST_DEFAULT);
    
        // establish a connection
        BridgeClient client = new BridgeClient(oe_token, oe_host);
        client.setSSLCertificate(Files.newInputStream(Paths.get(oe_pem)));
        client.startContainer();
        client.connect();
        // send a query
        String query_string = cmd.getOptionValue("query", "member(A,[a,b,c])");
        String solution     = client.query_one(query_string);
        System.out.println(solution);
    }
}
