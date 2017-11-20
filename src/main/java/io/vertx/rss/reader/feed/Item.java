package io.vertx.rss.reader.feed;

public class Item {

    private String link;

    private String description;

    private String title;

    private String feed;

    public String getLink() {
        return link;
    }

    public void setLink(String link) {
        this.link = link;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getFeed() {
        return feed;
    }

    public void setFeed(String feed){
        this.feed = feed;
    }

    public Item withTitle(String title) {
        setTitle(title);
        return this;
    }

    public Item withLink(String link) {
        setLink(link);
        return this;
    }

    public Item withDescription(String description) {
        setDescription(description);
        return this;
    }

    public Item withFeed(String feed) {
        setFeed(feed);
        return this;
    }
}
