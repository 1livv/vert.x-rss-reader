package io.vertx.rss.reader.http;

import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.FeedException;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.ParsedHeaderValues;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.rss.reader.db.DataBaseService;
import io.vertx.rss.reader.feed.FeedUtils;
import io.vertx.rss.reader.feed.Item;

import java.util.List;

public class ReaderVerticle extends AbstractVerticle {

    private WebClient client;

    DataBaseService dataBaseService = new DataBaseService();

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
        Future<Void> dbfuture = Future.future();
        JsonObject config = new JsonObject()
                .put("host", "localhost")
                .put("username", "root")
                .put("password", "password")
                .put("database", "rssfeed");

        dataBaseService.init(dbfuture.completer(), config, vertx);
        dbfuture.setHandler(ar -> {
            if (ar.succeeded()) {
                startFuture.complete();
            }
            else {
                System.out.println("could not get db from verticle " + ar.cause());
            }
        });
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
                        SyndFeed syndFeed = FeedUtils.feedFromResponse(httpResponse);
                        int updateInterval = FeedUtils.getUpdateInterval(syndFeed);

                        List<SyndEntry> entries = syndFeed.getEntries();
                        for (SyndEntry syndEntry : entries) {
                            Item item = new Item().withLink(syndEntry.getLink())
                                    .withTitle(syndEntry.getTitle())
                                    .withDescription(syndEntry.getDescription().getValue())
                                    .withFeed(endpoint);
                            dataBaseService.insert(item);
                        }

                        System.out.println("The feed will be read in another " + updateInterval);
                        //schedule job
                        vertx.setPeriodic((long) updateInterval * 1000, id -> {
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
    
    private void updateHandler(AsyncResult<HttpResponse<Buffer>> result) {
        System.out.println("Another update " + System.currentTimeMillis());
        if (result.succeeded()) {
            SyndFeed syndFeed = null;
            try {
                syndFeed = FeedUtils.feedFromResponse(result.result());
            } catch (FeedException e) {
                e.printStackTrace();
            }
            List<SyndEntry> entries = syndFeed.getEntries();
            for (SyndEntry syndEntry : entries) {
               dataBaseService.findByLink("asdaddsadasd", ar-> {
                   if (ar.succeeded()) {
                       List<Item> found = ar.result();
                       if (found.isEmpty()) {
                           System.out.println("Putting new item in the db " + syndEntry.getLink());
                           Item item = new Item().withLink(syndEntry.getLink())
                                   .withTitle(syndEntry.getTitle())
                                   .withDescription(syndEntry.getDescription().getValue())
                                   .withFeed("");
                           dataBaseService.insert(item);
                       }
                       else {
                           System.err.println("Entry " + syndEntry.getLink() + "already exists");
                       }
                   }
               });
            }
        }
        else {
            System.out.println("the request to update  failed:" + result.cause().getMessage());
        }
    }
}
