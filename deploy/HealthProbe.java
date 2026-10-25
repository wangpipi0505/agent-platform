import java.net.*;

/** Container probe only; no credentials and no response-body logging. */
public final class HealthProbe {
    public static void main(String[] args) throws Exception {
        var connection = (HttpURLConnection) URI.create(args[0]).toURL().openConnection();
        connection.setConnectTimeout(2000);
        connection.setReadTimeout(2000);
        int status;
        try {
            status = connection.getResponseCode();
        } finally {
            connection.disconnect();
        }
        if (status != 200) System.exit(1);
    }
}
