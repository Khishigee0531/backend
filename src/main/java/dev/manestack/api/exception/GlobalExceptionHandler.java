package dev.manestack.api.exception;

import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

import io.vertx.core.json.JsonObject;

@Provider
public class GlobalExceptionHandler {

    private static final Logger LOG = Logger.getLogger(GlobalExceptionHandler.class);

    @ServerExceptionMapper
    public RestResponse<JsonObject> mapException(Throwable throwable) {
        Response.Status status;
        String message;

        if (throwable instanceof NotFoundException) {
            status = Response.Status.NOT_FOUND;
            message = throwable.getMessage();
        } else if (throwable instanceof jakarta.ws.rs.NotAuthorizedException) {
            status = Response.Status.UNAUTHORIZED;
            message = "Invalid username or password";
        } else if (throwable instanceof IllegalArgumentException) {
            status = Response.Status.BAD_REQUEST;
            message = throwable.getMessage();
        } else if (throwable instanceof WebApplicationException webEx) {
            status = Response.Status.fromStatusCode(webEx.getResponse().getStatus());
            // Only expose message for client errors (4xx), not server errors (5xx)
            message = status.getStatusCode() < 500 ? throwable.getMessage() : "An error occurred";
        } else {
            LOG.errorv(throwable, "Unhandled exception: {0}", throwable.getMessage());
            status = Response.Status.INTERNAL_SERVER_ERROR;
            message = "An internal error occurred"; // Never leak internal details
        }

        return RestResponse.status(status, new JsonObject()
            .put("status", "FAILED")
            .put("errorCode", status.getStatusCode())
            .put("errorMessage", message)
        );
    }
}
