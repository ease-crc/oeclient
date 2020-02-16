package org.openease.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

import org.json.JSONException;
import org.json.JSONObject;

import edu.wpi.rail.jrosbridge.Ros;
import edu.wpi.rail.jrosbridge.Service;
import edu.wpi.rail.jrosbridge.services.ServiceRequest;
import edu.wpi.rail.jrosbridge.services.ServiceResponse;

/**
 * A client rosbridge implementation for openEASE.
 * 
 * @author Moritz Horstmann <mhorst@cs.uni-bremen.de>
 * @maintainer Asil Kaan Bozcuoglu <asil@cs.uni-bremen.de>
 */
public class BridgeClient {

    private static final int REFRESH_TIMEOUT_MILLIES = 250000;
    private static final int BUF_SIZE = 4096;
    private static final String OPEN_EASE_HOST = "https://data.open-ease.org";
    private static SSLSocketFactory socketFactory = HttpsURLConnection.getDefaultSSLSocketFactory();
    private SSLContext sslContext = null;
    private EASERos ros = null;
    private final Timer refreshWatchdog = new Timer(true);
    private final String host;
    private final String apiToken;
    private Service prologQuery;
    private Service prologNext;
    private Service prologFinish;

    /**
     * Create a new client instance. Use the API token that is assigned to your user account.
     * 
     * @param apiToken
     */
    public BridgeClient(String apiToken) {
        this(apiToken, OPEN_EASE_HOST);
    }

    /**
     * Create a new client instance. Use the API token that is assigned to your user account.
     * 
     * @param apiToken
     */
    public BridgeClient(String apiToken, String host) {
        this.apiToken = apiToken;
        this.host = host;
    }


    public String getHost() {
        return host;
    }

    public String getApiToken() {
        return apiToken;
    }

    public Service getPrologQuery() {
        return prologQuery;
    }

    public Service getPrologNext() {
        return prologNext;
    }

    public Service getPrologFinish() {
        return prologFinish;
    }

    /**
     * Get one solution to a query.
     * The query is given as Prolog-encoded string.
     * 
     * @throws EASEError if obtaining a solution has failed.
     */
    public String query_one(String query_string) throws EASEError {
        // generate a random string as query id
        String query_id = UUID.randomUUID().toString();
        // create the query
        query_create(query_id,query_string);
        // call next() once
        String solution = query_next(query_id);
        // destroy the query
        query_finish(query_id);
        return solution;
    }
    
    /**
     * Create a new query. The query must be closed
     * once finished with it by calling query_finish().
     * Different solutions can be obtained by successively calling
     * query_next() until it returns an empty string.
     * 
     * @throws EASEError if the query creation failed
     */
    public void query_create(String query_id, String query_string) throws EASEError {
        String request_json = "{"+
            "\"id\": \"" + query_id + "\"," +
            "\"mode\": 1," +
            "\"query\": \"" + query_string + "\"" +
            "}";
        ServiceRequest  request  = new ServiceRequest(request_json);
        ServiceResponse response = prologQuery.callServiceAndWait(request);
        //
        JSONObject jsonObj = new JSONObject(response.toString());
        if(!jsonObj.getBoolean("ok")) {
            throw new EASEError("failed to create query");
        }
    }
    
    /**
     * Close a previously created query.
     */
    public void query_finish(String query_id) {
        String request_json = "{"+
            "\"id\": \"" + query_id + "\"" +
            "}";
        ServiceRequest request = new ServiceRequest(request_json);
        prologFinish.callServiceAndWait(request);
    }
    
    /**
     * Get one solution. Returns an empty string in case 
     * no more solutions can be found.
     * 
     * @throws EASEError if the query failed, or if the query id is invalid
     */
    public String query_next(String query_id) throws EASEError {
        String request_json = "{"+
            "\"id\": \"" + query_id + "\"" +
            "}";
        ServiceRequest  request  = new ServiceRequest(request_json);
        ServiceResponse response =  prologNext.callServiceAndWait(request);
        //
        JSONObject jsonObj = new JSONObject(response.toString());
        String solution = jsonObj.getString("solution");
        int status = jsonObj.getInt("status");
        if(status==1) {
            throw new EASEError("wrong query id");
        }
        else if(status==2) {
            throw new EASEError("query failed: " + solution);
        }
        return solution;
    }

    /**
     * Starts the container and authenticates with rosauth.
     * 
     * @throws EASEError
     */
    public void startContainer() throws EASEError {
        try {
            // Startup container with the webrob API and acquire URL
            JSONObject response = getJson(host + "/api/v1.0/start_container/" + apiToken);
            if (response.has("error")) {
                throw new EASEError(response.getString("error"));
            }
            // Use secure web socket if https is used.
            String protocol = host.startsWith("https") ? "wss:" : "ws:";
            // Setup jrosbridge with URL
            ros = new EASERos(protocol + response.getString("url"));
            refreshWatchdog.scheduleAtFixedRate(new RefreshTask(), REFRESH_TIMEOUT_MILLIES, REFRESH_TIMEOUT_MILLIES);
        } catch (JSONException e) {
            e.printStackTrace(System.out);
            throw new EASEError("invalid JSON retrieved from server", e);
        }
    }

    /**
     * Connects to the rosbridge. This also handles rosauth authentication.
     * 
     * @throws EASEError
     */
    public void connect() throws EASEError {
        if (sslContext != null) {
            // Use custom SSL server certificate for web socket implementation
            ros.setSSLContext(sslContext);
        }

        boolean isConnected = false;

        while(!isConnected) isConnected = ros.connectInternal();
        authenticate();
        prologQuery = new Service(ros, "/rosprolog/query", "json_prolog/PrologQuery");
        prologNext = new Service(ros, "/rosprolog/next_solution", "json_prolog/PrologNextSolution");
        prologFinish = new Service(ros, "/rosprolog/finish", "json_prolog/PrologFinish");
    }

    /**
     * @return The connected and authenticated ros instance.
     */
    public Ros getRos() {
        if (this.ros == null) {
            throw new IllegalStateException("Container is not started, call startContainer!");
        }
        return this.ros;
    }

    /**
     * Setup SSL clients to accept given X.509 server certificate ONLY
     * 
     * @param certStream
     *            InputStream of a X.509 compliant certificate (i.e. export from browser, crt/pem file)
     * @throws EASEError
     */
    public void setSSLCertificate(InputStream certStream) throws EASEError {
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            // Parse certificate from InputStream
            Certificate cert = cf.generateCertificate(certStream);
            KeyStore keyStore = KeyStore.getInstance("JKS");
            // Instantiate empty key store and set certificate for the openEASE host name
            keyStore.load(null);
            keyStore.setCertificateEntry(host.substring("https://".length()), cert);
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            // Convert the key store to a trust store
            tmf.init(keyStore);
            sslContext = SSLContext.getInstance("TLS");
            // Build a TLS context with the trust store, it will accept only SSL with the given certificate!
            sslContext.init(null, tmf.getTrustManagers(), null);
            socketFactory = sslContext.getSocketFactory();
        } catch (KeyManagementException | KeyStoreException | NoSuchAlgorithmException | CertificateException
                | IOException e) {
            e.printStackTrace(System.out);
            throw new EASEError("Error while setting own EASE server certificate", e);
        }
    }

    /**
     * Refresh the timeout for the users container. This prevents the container from being terminated automatically.
     */
    void refresh() {
        try {
            getJson(host + "/api/v1.0/refresh_by_token/" + apiToken);
        } catch (EASEError e) {
            e.printStackTrace(System.out);
            // Refresh failed. Too bad.
        }
    }

    /**
     * Authenticate with rosauth by using the auth token API
     * 
     * @throws EASEError
     */
    private void authenticate() throws EASEError {
        // Get auth token from the webrob API.
        JSONObject auth = getJson(host + "/api/v1.0/auth_by_token/" + apiToken);
        if (auth.has("error")) {
            throw new EASEError(auth.getString("error"));
        }
        // Send an auth request with the data from the aquired auth token
        ros.authenticate(auth.getString("mac"), auth.getString("client"), auth.getString("dest"),
                auth.getString("rand"), auth.getInt("t"), auth.getString("level"), auth.getInt("end"));
    }

    /**
     * Get a parsed JSON object from the given URL with a standard HTTP GET request
     */
    private static JSONObject getJson(String urlstring) throws EASEError {
        try {
            URL url = new URL(urlstring);
            HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
            // Set SSL factory to use custom custom SSL server certificate if provided
            conn.setSSLSocketFactory(socketFactory);
            // Return a parsed JSON object
            return new JSONObject(readStream(conn.getInputStream()));
        } catch (IOException e) {
            e.printStackTrace(System.out);
            throw new EASEError("communication error", e);
        }
    }

    private class RefreshTask extends TimerTask {

        int isWebSocketStillAlive; 
        final int alivenessCount = 56; // 14400000(websocket lifespan)/250000(refresh rate of containers)
        RefreshTask() {
            isWebSocketStillAlive = 0;
        }

        @Override
        public void run() {
           try {
              isWebSocketStillAlive++;
              refresh();
              if(isWebSocketStillAlive % alivenessCount == 0)
              {
                  connect();
                  isWebSocketStillAlive = 0;
              }
           } 
           catch (EASEError e) {
             e.printStackTrace(System.out);
           }
        }

    }

    /**
     * Copies all data from the input stream to a String and returns it.
     */
    private static String readStream(InputStream stream) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[BUF_SIZE];
        int read = 0;
        while ((read = stream.read(buf)) != -1) {
            baos.write(buf, 0, read);
        }
        return new String(baos.toByteArray(), StandardCharsets.UTF_8);
    }
}
