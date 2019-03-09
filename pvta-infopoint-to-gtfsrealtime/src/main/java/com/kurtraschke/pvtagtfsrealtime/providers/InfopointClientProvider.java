package com.kurtraschke.pvtagtfsrealtime.providers;

import com.availtec.infopoint.client.InfopointClient;
import com.availtec.infopoint.client.InfopointClientException;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import java.net.URISyntaxException;
import java.net.URL;

public class InfopointClientProvider implements Provider<InfopointClient> {

    @Inject
    @Named("apiBaseUrl")
    private URL apiBaseUrl;

    @Override
    public InfopointClient get() {
        try {
            return new InfopointClient(apiBaseUrl);
        } catch (InfopointClientException | URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }
}
