package io.vertx.rss.reader;

import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.feed.synd.SyndFeedImpl;
import com.rometools.rome.io.FeedException;
import com.rometools.rome.io.SyndFeedInput;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;

import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.ParsedHeaderValues;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.handler.BodyHandler;
import org.jdom2.Attribute;
import org.jdom2.Element;
import org.xml.sax.InputSource;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;

public class ReaderVerticle extends AbstractVerticle {

    private WebClient client;

    @Override
    public void start(Future<Void> startFuture) {
        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create());
        router.get("/").handler(rc -> {
            rc.response().end("Welcome");
        });
        router.post("/add").handler(this::addNewFeedHandler);
        vertx.createHttpServer()
                .requestHandler(router::accept)
                .listen(8080);

        client = WebClient.create(vertx);
        startFuture.complete();
    }

    private void addNewFeedHandler(RoutingContext rc) {
        if (validateRequest(rc)) {
            JsonObject requestBody = rc.getBodyAsJson();
            String endpoint = requestBody.getString("endpoint");
            client.getAbs(endpoint).send( ar -> {
                if (ar.succeeded()) {
                    HttpResponse<Buffer> httpResponse = ar.result();
                    //parse feed
                    try {
                        SyndFeed syndFeed = feedFromResponse(httpResponse);
                        int updateInterval = getUpdateInterval(syndFeed);

                        List<SyndEntry> entries = syndFeed.getEntries();
                        for (SyndEntry syndEntry : entries) {
                            System.out.println("title:" + syndEntry.getTitle());
                            System.out.println("Description:" + syndEntry.getDescription().getValue());
                            System.out.println("Link:" + syndEntry.getLink());
                        }

                        System.out.println("The feed will be read in another " + updateInterval);
                        //schedule job
                        vertx.setPeriodic((long) updateInterval, id -> {
                            client.getAbs(endpoint).send(this::updateHandler);
                        });

                        rc.response().setStatusCode(200).end();
                    }
                    catch (FeedException  | NumberFormatException e) {
                        System.out.println("Feed format not as expected " + e);
                        rc.response().setStatusCode(400).end();
                    }
                    catch (Exception e) {
                        System.out.println("Unnexpected exception " + e);
                        rc.response().setStatusCode(500).end();
                    }
                }
                else {
                    System.out.println("the request to " + endpoint + " failed:" + ar.cause().getMessage());
                    rc.response().setStatusCode(500).end(ar.cause().getMessage());
                }
            });
        }
    }

    private boolean validateRequest(RoutingContext rc) {
        ParsedHeaderValues headerValues = rc.parsedHeaders();
        JsonObject response = new JsonObject();
        if (!headerValues.contentType().rawValue().contains("application/json")) {
            System.out.println(headerValues.contentType().rawValue());
            System.out.println(rc.request().getHeader("Content-Type"));
            response.put("message", "Bad content type: must be application/json");
            rc.response().putHeader("Content Type", "application/json")
                    .setStatusCode(400)
                    .end(response.encodePrettily());
            return false;
        }

        if (rc.getBody().length() == 0) {
            response.put("message", "Bad request empty request body");
            rc.response().putHeader("Content Type", "application/json")
                    .setStatusCode(400)
                    .end(response.encodePrettily());
            return false;
        }

        JsonObject requestBody = rc.getBodyAsJson();
        if ( requestBody.getString("name") == null || requestBody.getString("name").isEmpty()) {
            response.put("message", "Bad request body: name must be present");
            rc.response().putHeader("Content Type", "application/json")
                    .setStatusCode(400)
                    .end(response.encodePrettily());
            return false;
        }
        if (requestBody.getString("endpoint") == null || requestBody.getString("endpoint").isEmpty()) {
            response.put("message", "Bad request body endpoint must be present");
            rc.response().putHeader("Content Type", "application/json")
                    .setStatusCode(400)
                    .end(response.encodePrettily());
            return false;
        }
        return true;
    }

    private int getUpdateInterval(SyndFeed syndFeed) throws NumberFormatException {
        List<Element> makups = syndFeed.getForeignMarkup();
        String updatePeriod = "";
        int updateFrequency = 1;
        for (Element element : makups) {
            if (element.getName().equals("updatePeriod")) {
                updatePeriod = element.getValue();
            }
            if (element.getName().equals("updateFrequency")) {
                updateFrequency = Integer.parseInt(element.getValue());
            }
        }

        int updatePeriodInSeconds = getUpdatePeriodFromString(updatePeriod);
        return updatePeriodInSeconds/updateFrequency;
    }

    private int getUpdatePeriodFromString(String updatePeriod) throws NumberFormatException {
        switch (updatePeriod) {
            case  "hourly" :
                return 3600;
            case "daily":
                return 3600 * 24;
            case "weekly":
                return 3600 * 24 * 7;
            case "monthly":
                return 3600 * 24 * 7 * 4;
            case "yearly":
                return 3600 * 24 * 7 * 4 * 12;
            default:
                throw new NumberFormatException("the update Period is misformatted");
        }
    }

    private SyndFeed feedFromResponse(HttpResponse<Buffer> httpResponse) throws FeedException {
        InputStream is = new ByteArrayInputStream(httpResponse.body().getBytes());
        SyndFeedInput syndFeedInput = new SyndFeedInput();
        return syndFeedInput.build( new InputSource(is));
    }

    private void updateHandler(AsyncResult<HttpResponse<Buffer>> result) {
        System.out.println("Another update " + System.currentTimeMillis());
        if (result.succeeded()) {
            SyndFeed syndFeed = null;
            try {
                syndFeed = feedFromResponse(result.result());
            } catch (FeedException e) {
                e.printStackTrace();
            }

            List<SyndEntry> entries = syndFeed.getEntries();
            for (SyndEntry syndEntry : entries) {
                System.out.println("title:" + syndEntry.getTitle());
                System.out.println("Description:" + syndEntry.getDescription().getValue());
                System.out.println("Link:" + syndEntry.getLink());
            }
        }
        else {
            System.out.println("the request to update  failed:" + result.cause().getMessage());
        }
    }
}
