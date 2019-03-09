package com.availtec.infopoint.client;

import com.google.common.util.concurrent.RateLimiter;
import org.datacontract.schemas._2004._07.availtec_myavail_tids_datamanager.ArrayOfPublicMessage;
import org.datacontract.schemas._2004._07.availtec_myavail_tids_datamanager.ArrayOfRoute;
import org.datacontract.schemas._2004._07.availtec_myavail_tids_datamanager.ArrayOfVehicleLocation;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static java.net.http.HttpClient.Redirect.NORMAL;
import static java.net.http.HttpResponse.BodyHandlers.ofInputStream;

public class InfopointClient {

    private final Unmarshaller um;
    private final HttpClient client;
    private final RateLimiter rl;

    private final URI urlBase;

    public InfopointClient(URL urlBase) throws URISyntaxException, InfopointClientException {
        this(urlBase.toURI());
    }

    public InfopointClient(URI urlBase) throws InfopointClientException {
        try {
            JAXBContext jc = JAXBContext.newInstance(ArrayOfRoute.class, ArrayOfPublicMessage.class, ArrayOfVehicleLocation.class);
            this.um = jc.createUnmarshaller();
        } catch (JAXBException e) {
            throw new InfopointClientException(e);
        }

        this.client = HttpClient.newBuilder()
                .followRedirects(NORMAL)
                .build();

        this.rl = RateLimiter.create(2.0);

        this.urlBase = urlBase;
    }

    public ArrayOfRoute getAllRoutes() throws InfopointClientException {
        try {
            return get(ArrayOfRoute.class, "InfoPoint/rest/Routes/GetAllRoutes");
        } catch (Exception e) {
            throw new InfopointClientException(e);
        }
    }

    public ArrayOfPublicMessage getAllMessages() throws InfopointClientException {
        try {
            return get(ArrayOfPublicMessage.class, "InfoPoint/rest/PublicMessages/GetAllMessages");
        } catch (Exception e) {
            throw new InfopointClientException(e);
        }
    }

    public ArrayOfVehicleLocation getAllVehicles() throws InfopointClientException {
        try {
            return get(ArrayOfVehicleLocation.class, "InfoPoint/rest/Vehicles/GetAllVehicles");
        } catch (Exception e) {
            throw new InfopointClientException(e);
        }
    }

    private <T> T get(Class<T> contentClass, String endpoint) throws JAXBException, IOException, InterruptedException, URISyntaxException {
        rl.acquire();

        final URI uri = urlBase.resolve(endpoint);

        final HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .headers("Accept", "application/xml")
                .GET()
                .build();

        final HttpResponse<InputStream> response = client.send(request, ofInputStream());

        if (400 <= response.statusCode() && response.statusCode() < 600) {
            throw new RuntimeException(String.valueOf(response.statusCode()));
        }

        return contentClass.cast(um.unmarshal(response.body()));
    }

}

