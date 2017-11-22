package io.vertx.rss.reader.http;

import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.FeedException;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.AsyncResult;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.shareddata.LocalMap;
import io.vertx.core.shareddata.SharedData;
import io.vertx.ext.web.ParsedHeaderValues;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.StaticHandler;
import io.vertx.ext.web.handler.sockjs.BridgeOptions;
import io.vertx.ext.web.handler.sockjs.PermittedOptions;
import io.vertx.ext.web.handler.sockjs.SockJSHandler;
import io.vertx.ext.web.handler.sockjs.SockJSHandlerOptions;
import io.vertx.rss.reader.db.DataBaseService;
import io.vertx.rss.reader.feed.FeedUtils;
import io.vertx.rss.reader.feed.Item;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class ReaderVerticle extends AbstractVerticle {

    private WebClient client;

    private DataBaseService dataBaseService = new DataBaseService();

    private SharedData sharedData;

    LocalMap<String, String> feeds;

    @Override
    public void start(Future<Void> startFuture) {
        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create());
        router.get("/").handler(rc -> {
            rc.response().end("Welcome");
        });
        router.post("/add").handler(this::addNewFeedHandler);
        router.get("/items/:name").handler(this::getFeedHandler);
        router.get("/all").handler(this::getAllFeedsHandler);
        router.get("/static/*").handler(StaticHandler.create());

        SockJSHandlerOptions options = new SockJSHandlerOptions().setHeartbeatInterval(2000);
        SockJSHandler sockJSHandler = SockJSHandler.create(vertx, options);
        PermittedOptions inboundPermitted = new PermittedOptions().setAddress("feed-address");
        PermittedOptions outboundPermitted = new PermittedOptions().setAddress("feed-address");
        sockJSHandler.bridge(new BridgeOptions().addInboundPermitted(inboundPermitted)
        .addOutboundPermitted(outboundPermitted));
        router.route("/eventbus/*").handler(sockJSHandler);

        vertx.createHttpServer()
                .requestHandler(router::accept)
                .listen(8080);

        sharedData = vertx.sharedData();
        feeds = sharedData.getLocalMap("feeds");


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

    private void getAllFeedsHandler(RoutingContext rc) {
        Set<String> feedNames = feeds.keySet();
        int size = feedNames.size();
        List<Future> futures = new ArrayList<>();
        for (String name : feedNames) {
            Future<List<Item>> future = Future.future();
            dataBaseService.findByFeed(name, future.completer());
            futures.add(future);
        }
        CompositeFuture.all(futures).setHandler(composite -> {
            if (composite.succeeded()) {
                System.out.println("get all feeds succeded");
                CompositeFuture result = composite.result();
                JsonArray response = new JsonArray();
                for (int index = 0; index < size; index++) {
                    List<Item> items = result.resultAt(index);
                    for(Item item : items) {
                        JsonObject jsonItem = new JsonObject()
                                .put("title", item.getTitle())
                                .put("link", item.getLink())
                                .put("desc", item.getDescription());
                        response.add(jsonItem);
                    }
                }
                rc.response().setStatusCode(200).end(response.encodePrettily());
            }
            else {
                System.err.println("Get all feeds failed " + composite.cause());
                rc.response().setStatusCode(500).end("Something went wrong");
            }
        });
    }

    private void getFeedHandler(RoutingContext rc) {
        String name = rc.pathParam("name");
        dataBaseService.findByFeed(name, list -> {
            if (list.succeeded()) {
                JsonArray result = new JsonArray();
                for (Item item : list.result()) {
                    JsonObject jsonItem = new JsonObject()
                            .put("title", item.getTitle())
                            .put("link", item.getLink())
                            .put("desc", item.getDescription());
                    result.add(jsonItem);
                }
                rc.response().setStatusCode(200).end(result.encodePrettily());
            }
            else {
                System.err.println("Find feed call failed " + list.cause());
                rc.response().setStatusCode(500).end();
            }
        });
    }

    private void addNewFeedHandler(RoutingContext rc) {
        if (validateRequest(rc)) {
            JsonObject requestBody = rc.getBodyAsJson();
            String endpoint = requestBody.getString("endpoint");
            String name = requestBody.getString("name");
            feeds.put(name, endpoint);
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
                                    .withFeed(name);
                            saveAndSendItem(item);
                        }

                        System.out.println("The feed will be read in another " + updateInterval);
                        //schedule job
                        vertx.setPeriodic((long) updateInterval * 1000, id -> {
                            client.getAbs(endpoint).send( response -> {
                                updateHandler(response, name);
                            });
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

    private void updateHandler(AsyncResult<HttpResponse<Buffer>> result, String name) {
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
               dataBaseService.findByLink(syndEntry.getLink(), ar-> {
                   if (ar.succeeded()) {
                       List<Item> found = ar.result();
                       if (found.isEmpty()) {
                           System.out.println("Putting new item in the db " + syndEntry.getLink());
                           Item item = new Item().withLink(syndEntry.getLink())
                                   .withTitle(syndEntry.getTitle())
                                   .withDescription(syndEntry.getDescription().getValue())
                                   .withFeed(name);
                           saveAndSendItem(item);
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

    private void saveAndSendItem(Item item) {
        Future<Boolean> insertFuture = Future.future();
        dataBaseService.insert(item , insertFuture.completer());
        insertFuture.setHandler(insertResult -> {
            if (insertResult.succeeded()) {
                vertx.eventBus().send("feed-address",
                        item.toJson());
            }
        });
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

}
