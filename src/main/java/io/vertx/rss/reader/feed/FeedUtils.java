package io.vertx.rss.reader.feed;

import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.FeedException;
import com.rometools.rome.io.SyndFeedInput;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.client.HttpResponse;
import org.jdom2.Element;
import org.xml.sax.InputSource;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;

public class FeedUtils {

    public static int getUpdateInterval(SyndFeed syndFeed) throws NumberFormatException {
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

        if (!updatePeriod.isEmpty()) {
            int updatePeriodInSeconds = getUpdatePeriodFromString(updatePeriod);
            return updatePeriodInSeconds / updateFrequency;
        }
        else {
            return 300;
        }
    }

    public static SyndFeed feedFromResponse(HttpResponse<Buffer> httpResponse) throws FeedException {
        InputStream is = new ByteArrayInputStream(httpResponse.body().getBytes());
        SyndFeedInput syndFeedInput = new SyndFeedInput();
        return syndFeedInput.build( new InputSource(is));
    }


    private static int getUpdatePeriodFromString(String updatePeriod) throws NumberFormatException {
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
}
