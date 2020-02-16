# oeclient

A Java-based client library that connects itself to openEASE, and that can ask queries that are answered by the knowledge base of openEASE.

A token is required to establish the connection.
The token can be obtained from your user page on the openEASE webpage.
The current implementation further requires manual download of
the SSL certificate used by the openEASE webpage.

This package further includes an example client that can be called from commandline:

    java -jar build/libs/oeclient-example.jar \
        --token="gSTV7ZC751JJDDylySKG04ApglviWnloWXNYm367f6ypo7VlvWPSi3rGmxYM9Bgw" \
        --ssl="/home/daniel/localhost.pem" \
        --host="https://localhost" \
        --query="member(A,[a,b])"
