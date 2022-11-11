package wethinkcode.helpers;

import kong.unirest.Unirest;
import kong.unirest.UnirestException;

public final class Helpers {

    /**
     * Gets the URL of a specific service using an API call to the manager
     * @param from the service you're looking for
     * @return the URL of that server.
     */
    public static String getURL(String from, String managerURL){
        try {
            String URL = Unirest.get(managerURL + "/service/" + from).asString().getBody();
            return URL.replace("\"", "");
        } catch (UnirestException e){
            try {
                Thread.sleep(400);
            } catch (InterruptedException ex) {
                throw new RuntimeException(ex);
            }
            return getURL(from, managerURL);
        }
    }

}
