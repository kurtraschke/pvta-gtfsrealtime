package com.availtec.infopoint.client;

public class InfopointClientException extends Exception {
    static final long serialVersionUID = 1L;

    public InfopointClientException() {
    }

    public InfopointClientException(final String message) {
        super(message);
    }

    public InfopointClientException(final String message, final Throwable cause) {
        super(message, cause);
    }

    public InfopointClientException(final Throwable cause) {
        super(cause);
    }

    public InfopointClientException(final String message, final Throwable cause, final boolean enableSuppression, final boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}
