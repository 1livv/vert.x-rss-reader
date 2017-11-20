package io.vertx.rss.reader.db;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.asyncsql.AsyncSQLClient;
import io.vertx.ext.asyncsql.MySQLClient;
import io.vertx.ext.sql.ResultSet;
import io.vertx.ext.sql.SQLConnection;
import io.vertx.rss.reader.feed.Item;

import java.util.ArrayList;
import java.util.List;


public class DataBaseService {

    private AsyncSQLClient dbClient;

    public static final String initStatement = "CREATE TABLE IF NOT EXISTS entries (title VARCHAR(100)," +
            " link VARCHAR(100), description VARCHAR(1000), feed VARCHAR(100), PRIMARY KEY(link))";

    private static final String insertStatement = "INSERT INTO entries VALUES(?, ?, ?, ?)";

    private static final String findByLinkStatement = "SELECT * FROM entries WHERE link=?";

    private static final String findByTitleStatement = "SELECT * FROM entries WHERE title=?";

    private static final String findByDescriptionStatement = "SELECT * FROM entries WHERE description=?";

    public void init (Handler<AsyncResult<Void>> handler, JsonObject config, Vertx vertx) {
        dbClient = MySQLClient.createNonShared(vertx, config);

        dbClient.getConnection(ar -> {
            if (ar.succeeded()) {
                System.out.println("connected to database");
                SQLConnection sqlConnection = ar.result();
                sqlConnection.execute(initStatement, init -> {
                    if (init.succeeded()) {
                        System.out.println("Succedded initializing the db");
                        handler.handle(Future.succeededFuture());
                    }
                    else {
                        System.out.println("Failed in initializing the db " + init.cause());
                        handler.handle(Future.failedFuture(init.cause()));
                    }
                });
            }
            else {
                System.err.println("could not get connection to  db " + ar.cause());
                handler.handle(Future.failedFuture(ar.cause()));
            }
        });
    }

    public void insert(Item item) {
        System.out.println("inserting");
        dbClient.getConnection(ar -> {
            if (ar.succeeded()) {
                System.out.println("got connection to the db");
                SQLConnection sqlConnection = ar.result();
                JsonArray params = new JsonArray().add(item.getTitle())
                        .add(item.getLink()).add(item.getDescription()).add(item.getFeed());
                sqlConnection.updateWithParams(insertStatement, params, insert -> {
                    if (insert.succeeded()) {
                        System.out.println("insert succeded for " + item.getLink());
                    }
                    else {
                        System.err.println("insert failed for " + item.getLink() + " " + ar.cause());
                    }
                });
                sqlConnection.close();;
            }
            else {
                System.err.println("Could not get connection for db");
            }
        });
    }

    public void findByLink(String link, Handler<AsyncResult<List<Item>>> handler) {
        System.out.println("Finding link " + link);
        find(findByLinkStatement, link, handler);
    }

    public void findByTitle(String title, Handler<AsyncResult<List<Item>>> handler) {
        System.out.println("Finding title " + title);
        find(findByTitleStatement, title, handler);
    }

    public void findByDescription(String description, Handler<AsyncResult<List<Item>>> handler) {
        System.out.println("Finding description " + description);
        find(findByDescriptionStatement, description, handler);
    }

    private void find(String statement, String param, Handler<AsyncResult<List<Item>>> handler) {
        System.out.println("finding " + param);
        dbClient.getConnection(ar -> {
            if (ar.succeeded()) {
                System.out.println("got connection to the db");
                SQLConnection sqlConnection = ar.result();
                JsonArray params = new JsonArray().add(param);
                sqlConnection.queryWithParams(statement, params, res -> {
                    if (res.succeeded()) {
                        ResultSet resultSet = res.result();
                        List<JsonArray> rows = resultSet.getResults();
                        List<Item> itemsFound = new ArrayList<Item>();
                        for (JsonArray row : rows) {
                            itemsFound.add(new Item().withTitle(row.getString(0))
                                    .withLink(row.getString(1)).withDescription(row.getString(2))
                                    .withFeed(row.getString(3)));
                        }
                        handler.handle(Future.succeededFuture(itemsFound));
                    }
                    else {
                        System.err.println("find failed for param " + param);
                        handler.handle(Future.failedFuture(ar.cause()));
                    }
                });
                sqlConnection.close();
            }
            else {
                System.err.println("Could not get connection for db");
            }
        });
    }
}
