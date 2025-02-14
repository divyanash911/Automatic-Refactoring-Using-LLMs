```java
package com.sismics.reader.core.service;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.eventbus.EventBus;
import com.google.common.util.concurrent.AbstractScheduledService;
import com.sismics.reader.core.dao.jpa.criteria.ArticleCriteria;
import com.sismics.reader.core.dao.jpa.criteria.FeedSubscriptionCriteria;
import com.sismics.reader.core.dao.jpa.dto.ArticleDto;
import com.sismics.reader.core.dao.jpa.dto.FeedSubscriptionDto;
import com.sismics.reader.core.event.ArticleCreatedAsyncEvent;
import com.sismics.reader.core.event.ArticleDeletedAsyncEvent;
import com.sismics.reader.core.event.ArticleUpdatedAsyncEvent;
import com.sismics.reader.core.event.FaviconUpdateRequestedEvent;
import com.sismics.reader.core.model.jpa.Article;
import com.sismics.reader.core.model.jpa.Feed;
import com.sismics.reader.core.model.jpa.FeedSubscription;
import com.sismics.reader.core.model.jpa.UserArticle;
import com.sismics.reader.core.util.TransactionUtil;
import org.joda.time.DateTime;
import org.joda.time.Days;
import org.joda.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.MessageFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Feed service.
 *
 * @author jtremeaux
 */
public class FeedService extends AbstractScheduledService {
    private static final Logger log = LoggerFactory.getLogger(FeedService.class);

    private final FeedFetcher feedFetcher;
    private final ArticleManager articleManager;
    private final UserArticleManager userArticleManager;
    private final EventPublisher eventPublisher;
    private final FeedMetadataUpdater feedMetadataUpdater;

    private EventBus asyncEventBus;

    public FeedService(FeedFetcher feedFetcher, ArticleManager articleManager, UserArticleManager userArticleManager,
                       EventPublisher eventPublisher, FeedMetadataUpdater feedMetadataUpdater) {
        this.feedFetcher = feedFetcher;
        this.articleManager = articleManager;
        this.userArticleManager = userArticleManager;
        this.eventPublisher = eventPublisher;
        this.feedMetadataUpdater = feedMetadataUpdater;
    }

    public void setAsyncEventBus(EventBus asyncEventBus) {
        this.asyncEventBus = asyncEventBus;
        this.eventPublisher.setAsyncEventBus(asyncEventBus); // Set event bus to event publisher
        this.articleManager.setAsyncEventBus(asyncEventBus);
        this.feedMetadataUpdater.setAsyncEventBus(asyncEventBus);
    }

    @Override
    protected void startUp() throws Exception {
    }

    @Override
    protected void shutDown() throws Exception {
    }

    @Override
    protected void runOneIteration() {
        try {
            TransactionUtil.handle(this::synchronizeAllFeeds);
        } catch (Throwable t) {
            log.error("Error synchronizing feeds", t);
        }
    }

    @Override
    protected Scheduler scheduler() {
        return Scheduler.newFixedDelaySchedule(0, 10, TimeUnit.MINUTES);
    }

    public void synchronizeAllFeeds() {
        List<FeedSynchronization> feedSynchronizationList = feedFetcher.fetchAllFeeds();

        // If all feeds have failed, then we infer that the network is probably down
        boolean networkDown = feedSynchronizationList.stream().noneMatch(FeedSynchronization::isSuccess);

        // Update the status of all synchronized feeds
        if (!networkDown) {
            feedFetcher.updateSynchronizationStatus(feedSynchronizationList);
        }
    }

    public Feed synchronize(String url) throws Exception {
        long startTime = System.currentTimeMillis();

        // Parse the feed
        FeedData feedData = feedFetcher.parseFeed(url);
        Feed newFeed = feedData.feed();
        List<Article> articleList = feedData.articles();

        completeArticleList(articleList);

        // Get articles that were removed from RSS compared to last fetch
        List<Article> articleToRemove = articleManager.getArticleToRemove(articleList);
        if (!articleToRemove.isEmpty()) {
            articleManager.removeArticles(articleToRemove);
            eventPublisher.publishArticleDeletedEvent(articleToRemove);
        }

        // Create the feed if necessary (not created and currently in use by another user) or update metadata
        Feed feed = feedMetadataUpdater.createOrUpdateFeedMetadata(newFeed);

        // Update existing articles and create new ones
        articleManager.updateArticles(feed, articleList);
        long endTime = System.currentTimeMillis();
        if (log.isInfoEnabled()) {
            log.info(MessageFormat.format("Synchronized feed at URL {0} in {1}ms, {2} articles added, {3} deleted", url,
                    endTime - startTime, articleManager.getAddedArticleCount(), articleToRemove.size()));
        }

        return feed;
    }

    private void completeArticleList(List<Article> articleList) {
        for (Article article : articleList) {
            Date now = new Date();
            if (article.getPublicationDate() == null || article.getPublicationDate().after(now)) {
                article.setPublicationDate(now);
            }
        }
    }

    public void createInitialUserArticle(String userId, FeedSubscription feedSubscription) {
        userArticleManager.createInitialUserArticle(userId, feedSubscription);
    }

    private boolean isFaviconUpdated(Feed feed) {
        boolean newDay = feed.getLastFetchDate() == null ||
                DateTime.now().getDayOfYear() != new DateTime(feed.getLastFetchDate()).getDayOfYear();
        int daysFromCreation = Days.daysBetween(Instant.now(), new Instant(feed.getCreateDate().getTime())).getDays();
        return newDay && daysFromCreation % 7 == 0;
    }

}
```

```java
package com.sismics.reader.core.service;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.sismics.reader.core.dao.jpa.*;
import com.sismics.reader.core.dao.jpa.criteria.ArticleCriteria;
import com.sismics.reader.core.dao.jpa.criteria.FeedSubscriptionCriteria;
import com.sismics.reader.core.dao.jpa.criteria.UserArticleCriteria;
import com.sismics.reader.core.dao.jpa.dto.ArticleDto;
import com.sismics.reader.core.dao.jpa.dto.FeedSubscriptionDto;
import com.sismics.reader.core.dao.jpa.dto.UserArticleDto;
import com.sismics.reader.core.event.ArticleCreatedAsyncEvent;
import com.sismics.reader.core.event.ArticleDeletedAsyncEvent;
import com.sismics.reader.core.event.ArticleUpdatedAsyncEvent;
import com.sismics.reader.core.model.jpa.Article;
import com.sismics.reader.core.model.jpa.Feed;
import com.sismics.reader.core.model.jpa.FeedSubscription;
import com.sismics.reader.core.model.jpa.UserArticle;
import com.sismics.reader.core.util.jpa.PaginatedList;
import com.sismics.reader.core.util.jpa.PaginatedLists;
import org.joda.time.DateTime;
import org.joda.time.DurationFieldType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

public class ArticleManager {

    private static final Logger log = LoggerFactory.getLogger(ArticleManager.class);

    private final ArticleDao articleDao;
    private final UserArticleDao userArticleDao;
    private final FeedSubscriptionDao feedSubscriptionDao;

    private int addedArticleCount;

    private EventPublisher eventPublisher;

    public ArticleManager(ArticleDao articleDao, UserArticleDao userArticleDao, FeedSubscriptionDao feedSubscriptionDao, EventPublisher eventPublisher) {
        this.articleDao = articleDao;
        this.userArticleDao = userArticleDao;
        this.feedSubscriptionDao = feedSubscriptionDao;
        this.eventPublisher = eventPublisher;
        this.addedArticleCount = 0;
    }

    public void setAsyncEventBus(EventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    public void setAsyncEventBus(com.google.common.eventbus.EventBus asyncEventBus) {
        this.eventPublisher.setAsyncEventBus(asyncEventBus);
    }

    public int getAddedArticleCount() {
        return addedArticleCount;
    }


    public List<Article> getArticleToRemove(List<Article> articleList) {
        List<Article> removedArticleList = new ArrayList<>();

        // Check if the oldest article from stream was already synced
        Article oldestArticle = getOldestArticle(articleList);
        if (oldestArticle == null) {
            return removedArticleList;
        }
        ArticleDto localArticle = articleDao.findFirstByCriteria(new ArticleCriteria()
                .setGuidIn(Lists.newArrayList(oldestArticle.getGuid())));
        if (localArticle == null) {
            return removedArticleList;
        }

        // Get newer articles in stream
        List<Article> newerArticles = getNewerArticleList(articleList, oldestArticle);
        Set<String> newerArticleGuids = new HashSet<>();
        for (Article article : newerArticles) {
            newerArticleGuids.add(article.getGuid());
        }

        // Get newer articles in local DB
        List<ArticleDto> newerLocalArticles = articleDao.findByCriteria(new ArticleCriteria()
                .setFeedId(localArticle.getFeedId())
                .setPublicationDateMin(oldestArticle.getPublicationDate()));

        // Delete articles removed from stream, and not too old
        Date dateMin = new DateTime().withFieldAdded(DurationFieldType.days(), -1).toDate();
        for (ArticleDto newerLocalArticle : newerLocalArticles) {
            if (!newerArticleGuids.contains(newerLocalArticle.getGuid())
                    && newerLocalArticle.getCreateDate().after(dateMin)) {
                removedArticleList.add(new Article(newerLocalArticle.getId()));
            }
        }

        return removedArticleList;
    }


    public void removeArticles(List<Article> articleToRemove) {
        for (Article article : articleToRemove) {
            // Update unread counts

            List<UserArticleDto> userArticleDtoList = userArticleDao
                    .findByCriteria(new UserArticleCriteria()
                            .setArticleId(article.getId())
                            .setFetchAllFeedSubscription(true)
                            .setUnread(true));

            for (UserArticleDto userArticleDto : userArticleDtoList) {
                FeedSubscriptionDto feedSubscriptionDto = feedSubscriptionDao
                        .findFirstByCriteria(new FeedSubscriptionCriteria()
                                .setId(userArticleDto.getFeedSubscriptionId()));
                if (feedSubscriptionDto != null) {
                    feedSubscriptionDao.updateUnreadCount(feedSubscriptionDto.getId(),
                            feedSubscriptionDto.getUnreadUserArticleCount() - 1);
                }
            }


            articleDao.delete(article.getId());
        }
    }


    private List<Article> getNewerArticleList(List<Article> articleList, Article oldestArticle) {
        return articleList.stream()
                .filter(article -> article.getPublicationDate().after(oldestArticle.getPublicationDate()))
                .collect(Collectors.toList());
    }

    private Article getOldestArticle(List<Article> articleList) {
        return articleList.stream()
                .min(Comparator.comparing(Article::getPublicationDate))
                .orElse(null);
    }

    public void updateArticles(Feed feed, List<Article> articleList) {
        Map<String, Article> articleMap = new HashMap<>();
        for (Article article : articleList) {
            articleMap.put(article.getGuid(), article);
        }

        List<String> guidIn = articleList.stream().map(Article::getGuid).collect(Collectors.toList());

        if (!guidIn.isEmpty()) {
            ArticleCriteria articleCriteria = new ArticleCriteria()
                    .setFeedId(feed.getId())
                    .setGuidIn(guidIn);
            List<ArticleDto> currentArticleDtoList = articleDao.findByCriteria(articleCriteria);
            List<Article> articleUpdatedList = new ArrayList<>();

            for (ArticleDto currentArticle : currentArticleDtoList) {
                Article newArticle = articleMap.remove(currentArticle.getGuid());
                Article article = mapArticleDtoToArticle(currentArticle, feed, newArticle);

                if (hasArticleChanged(currentArticle, article)) {
                    articleDao.update(article);
                    articleUpdatedList.add(article);
                }
            }

            if (!articleUpdatedList.isEmpty()) {
                eventPublisher.publishArticleUpdatedEvent(articleUpdatedList);
            }
        }


        if (!articleMap.isEmpty()) {
            List<FeedSubscriptionDto> feedSubscriptionList = feedSubscriptionDao
                    .findByCriteria(new FeedSubscriptionCriteria()
                            .setFeedId(feed.getId()));

            List<Article> newArticles = new ArrayList<>(articleMap.values());

            for (Article article : newArticles) {
                article.setFeedId(feed.getId());
                articleDao.create(article);

                // Create the user articles eagerly for users already subscribed
                for (FeedSubscriptionDto feedSubscription : feedSubscriptionList) {
                    UserArticle userArticle = new UserArticle();
                    userArticle.setArticleId(article.getId());
                    userArticle.setUserId(feedSubscription.getUserId());
                    userArticleDao.create(userArticle);

                    feedSubscription.setUnreadUserArticleCount(feedSubscription.getUnreadUserArticleCount() + 1);
                    feedSubscriptionDao.updateUnreadCount(feedSubscription.getId(),
                            feedSubscription.getUnreadUserArticleCount());
                }
            }
            this.addedArticleCount = newArticles.size();
            eventPublisher.publishArticleCreatedEvent(newArticles);
        } else {
            this.addedArticleCount = 0;
        }
    }

    private Article mapArticleDtoToArticle(ArticleDto currentArticle, Feed feed, Article newArticle) {
        Article article = new Article();
        article.setPublicationDate(currentArticle.getPublicationDate());
        article.setId(currentArticle.getId());
        article.setFeedId(feed.getId());
        article.setUrl(newArticle.getUrl());
        article.setTitle(newArticle.getTitle());
        article.setCreator(newArticle.getCreator());
        article.setDescription(newArticle.getDescription());
        article.setCommentUrl(newArticle.getCommentUrl());
        article.setCommentCount(newArticle.getCommentCount());
        article.setEnclosureUrl(newArticle.getEnclosureUrl());
        article.setEnclosureLength(newArticle.getEnclosureLength());
        article.setEnclosureType(newArticle.getEnclosureType());
        return article;
    }

    private boolean hasArticleChanged(ArticleDto currentArticle, Article article) {
        return !Strings.nullToEmpty(currentArticle.getTitle()).equals(Strings.nullToEmpty(article.getTitle())) ||
                !Strings.nullToEmpty(currentArticle.getDescription()).equals(Strings.nullToEmpty(article.getDescription()));
    }
}
```

```java
package com.sismics.reader.core.service;

import com.google.common.eventbus.EventBus;
import com.sismics.reader.core.dao.jpa.*;
import com.sismics.reader.core.dao.jpa.criteria.FeedCriteria;
import com.sismics.reader.core.dao.jpa.dto.FeedDto;
import com.sismics.reader.core.event.FaviconUpdateRequestedEvent;
import com.sismics.reader.core.model.jpa.Feed;
import com.sismics.reader.core.model.jpa.FeedSynchronization;
import com.sismics.reader.core.util.TransactionUtil;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class FeedFetcher {

    private static final Logger log = LoggerFactory.getLogger(FeedFetcher.class);

    private final FeedDao feedDao;
    private final FeedSynchronizationDao feedSynchronizationDao;
    private final FeedParser feedParser;

    public FeedFetcher(FeedDao feedDao, FeedSynchronizationDao feedSynchronizationDao, FeedParser feedParser) {
        this.feedDao = feedDao;
        this.feedSynchronizationDao = feedSynchronizationDao;
        this.feedParser = feedParser;
    }


    public List<FeedSynchronization> fetchAllFeeds() {
        FeedCriteria feedCriteria = new FeedCriteria().setWithUserSubscription(true);
        List<FeedDto> feedList = feedDao.findByCriteria(feedCriteria);
        List<FeedSynchronization> feedSynchronizationList = new ArrayList<>();

        for (FeedDto feed : feedList) {
            FeedSynchronization feedSynchronization = new FeedSynchronization();
            feedSynchronization.setFeedId(feed.getId());
            feedSynchronization.setSuccess(true);
            long startTime = System.currentTimeMillis();

            try {
                feedParser.synchronize(feed.getRssUrl());
            } catch (Exception e) {
                log.error(MessageFormat.format("Error synchronizing feed at URL: {0}", feed.getRssUrl()), e);
                feedSynchronization.setSuccess(false);
                feedSynchronization.setMessage(ExceptionUtils.getStackTrace(e));
            }

            feedSynchronization.setDuration((int) (System.currentTimeMillis() - startTime));
            feedSynchronizationList.add(feedSynchronization);
            TransactionUtil.commit();
        }

        return feedSynchronizationList;
    }

    public void updateSynchronizationStatus(List<FeedSynchronization> feedSynchronizationList) {
        for (FeedSynchronization feedSynchronization : feedSynchronizationList) {
            feedSynchronizationDao.create(feedSynchronization);
            feedSynchronizationDao.deleteOldFeedSynchronization(feedSynchronization.getFeedId(), 600);
        }
        TransactionUtil.commit();
    }

    public FeedData parseFeed(String url) throws Exception {
        return feedParser.parseFeed(url);
    }
}
```

```java
package com.sismics.reader.core.service;

import com.google.common.eventbus.EventBus;
import com.sismics.reader.core.dao.jpa.FeedDao;
import com.sismics.reader.core.model.jpa.Feed;
import com.sismics.reader.core.event.FaviconUpdateRequestedEvent;
import org.apache.commons.lang.StringUtils;
import org.joda.time.DateTime;
import org.joda.time.Days;
import org.joda.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;

public class FeedMetadataUpdater {
    private static final Logger log = LoggerFactory.getLogger(FeedMetadataUpdater.class);
    private final FeedDao feedDao;
    private EventBus asyncEventBus;

    public FeedMetadataUpdater(FeedDao feedDao) {
        this.feedDao = feedDao;
    }

    public void setAsyncEventBus(EventBus asyncEventBus) {
        this.asyncEventBus = asyncEventBus;
    }

    public Feed createOrUpdateFeedMetadata(Feed newFeed) {
        Feed feed = feedDao.getByRssUrl(newFeed.getRssUrl());
        if (feed == null) {
            feed = createFeed(newFeed);
        } else {
            updateFeed(feed, newFeed);
        }
        return feed;
    }

    private Feed createFeed(Feed newFeed) {
        Feed feed = new Feed();
        feed.setUrl(newFeed.getUrl());
        feed.setBaseUri(newFeed.getBaseUri());
        feed.setRssUrl(newFeed.getRssUrl());
        feed.setTitle(StringUtils.abbreviate(newFeed.getTitle(), 100));
        feed.setLanguage(
                newFeed.getLanguage() != null && newFeed.getLanguage().length() <= 10 ? newFeed.getLanguage()
                        : null);
        feed.setDescription(StringUtils.abbreviate(newFeed.getDescription(), 4000));
        feed.setLastFetchDate(new Date());
        feedDao.create(feed);
        com.sismics.reader.core.util.EntityManagerUtil.flush();

        // Try to download the feed's favicon
        FaviconUpdateRequestedEvent faviconUpdateRequestedEvent = new FaviconUpdateRequestedEvent();
        faviconUpdateRequestedEvent.setFeed(feed);
        asyncEventBus.post(faviconUpdateRequestedEvent);
        return feed;
    }

    private void updateFeed(Feed feed, Feed newFeed) {
        boolean updateFavicon = isFaviconUpdated(feed);

        // Update metadata
        feed.setUrl(newFeed.getUrl());
        feed.setBaseUri(newFeed.getBaseUri());
        feed.setTitle(StringUtils.abbreviate(newFeed.getTitle(), 100));
        feed.setLanguage(
                newFeed.getLanguage() != null && newFeed.getLanguage().length() <= 10 ? newFeed.getLanguage()
                        : null);
        feed.setDescription(StringUtils.abbreviate(newFeed.getDescription(), 4000));
        feed.setLastFetchDate(new Date());
        feedDao.update(feed);

        // Update the favicon
        if (updateFavicon) {
            FaviconUpdateRequestedEvent faviconUpdateRequestedEvent = new FaviconUpdateRequestedEvent();
            faviconUpdateRequestedEvent.setFeed(feed);
            asyncEventBus.post(faviconUpdateRequestedEvent);
        }
    }

    private boolean isFaviconUpdated(Feed feed) {
        boolean newDay = feed.getLastFetchDate() == null ||
                DateTime.now().getDayOfYear() != new DateTime(feed.getLastFetchDate()).getDayOfYear();
        int daysFromCreation = Days.daysBetween(Instant.now(), new Instant(feed.getCreateDate().getTime())).getDays();
        return newDay && daysFromCreation % 7 == 0;
    }
}
```

```java
package com.sismics.reader.core.service;

import com.google.common.eventbus.EventBus;
import com.sismics.reader.core.event.ArticleCreatedAsyncEvent;
import com.sismics.reader.core.event.ArticleDeletedAsyncEvent;
import com.sismics.reader.core.event.ArticleUpdatedAsyncEvent;
import com.sismics.reader.core.model.jpa.Article;
import java.util.List;

public class EventPublisher {

    private EventBus asyncEventBus;

    public void setAsyncEventBus(EventBus asyncEventBus) {
        this.asyncEventBus = asyncEventBus;
    }

    public void publishArticleCreatedEvent(List<Article> articleList) {
        ArticleCreatedAsyncEvent articleCreatedAsyncEvent = new ArticleCreatedAsyncEvent();
        articleCreatedAsyncEvent.setArticleList(articleList);
        asyncEventBus.post(articleCreatedAsyncEvent);
    }

    public void publishArticleUpdatedEvent(List<Article> articleList) {
        ArticleUpdatedAsyncEvent articleUpdatedAsyncEvent = new ArticleUpdatedAsyncEvent();
        articleUpdatedAsyncEvent.setArticleList(articleList);
        asyncEventBus.post(articleUpdatedAsyncEvent);
    }

    public void publishArticleDeletedEvent(List<Article> articleList) {
        ArticleDeletedAsyncEvent articleDeletedAsyncEvent = new ArticleDeletedAsyncEvent();
        articleDeletedAsyncEvent.setArticleList(articleList);
        asyncEventBus.post(articleDeletedAsyncEvent);
    }
}
```

```java
package com.sismics.reader.core.service;

import com.sismics.reader.core.dao.jpa.FeedSubscriptionDao;
import com.sismics.reader.core.dao.jpa.UserArticleDao;
import com.sismics.reader.core.dao.jpa.criteria.UserArticleCriteria;
import com.sismics.reader.core.dao.jpa.dto.UserArticleDto;
import com.sismics.reader.core.model.jpa.FeedSubscription;
import com.sismics.reader.core.model.jpa.UserArticle;
import com.sismics.reader.core.util.jpa.PaginatedList;
import com.sismics.reader.core.util.jpa.PaginatedLists;

public class UserArticleManager {

    private final UserArticleDao userArticleDao;
    private final FeedSubscriptionDao feedSubscriptionDao;

    public UserArticleManager(UserArticleDao userArticleDao, FeedSubscriptionDao feedSubscriptionDao) {
        this.userArticleDao = userArticleDao;
        this.feedSubscriptionDao = feedSubscriptionDao;
    }

    public void createInitialUserArticle(String userId, FeedSubscription feedSubscription) {
        UserArticleCriteria userArticleCriteria = new UserArticleCriteria()
                .setUserId(userId)
                .setSubscribed(true)
                .setFeedId(feedSubscription.getFeedId());

        PaginatedList<UserArticleDto> paginatedList = PaginatedLists.create();
        userArticleDao.findByCriteria(paginatedList, userArticleCriteria, null, null);

        for (UserArticleDto userArticleDto : paginatedList.getResultList()) {
            if (userArticleDto.getId() == null) {
                UserArticle userArticle = new UserArticle();
                userArticle.setArticleId(userArticleDto.getArticleId());
                userArticle.setUserId(userId);
                userArticleDao.create(userArticle);
                feedSubscription.setUnreadCount(feedSubscription.getUnreadCount() + 1);
            } else if (userArticleDto.getReadTimestamp() == null) {
                feedSubscription.setUnreadCount(feedSubscription.getUnreadCount() + 1);
            }
        }

        feedSubscriptionDao.updateUnreadCount(feedSubscription.getId(), feedSubscription.getUnreadCount());
    }
}
```

```java
package com.sismics.reader.core.service;

import com.sismics.reader.core.dao.file.html.FeedChooserStrategy;
import com.sismics.reader.core.dao.file.html.RssExtractor;
import com.sismics.reader.core.dao.file.rss.RssReader;
import com.sismics.reader.core.model.jpa.Article;
import com.sismics.reader.core.model.jpa.Feed;
import com.sismics.reader.core.util.http.ReaderHttpClient;
import com.sismics.reader.core.util.sanitizer.ArticleSanitizer;
import com.sismics.reader.core.util.sanitizer.TextSanitizer;
import com.sismics.util.UrlUtil;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URL;
import java.net.UnknownHostException;
import java.text.MessageFormat;
import java.util.List;

public class FeedParser {

    private static final Logger log = LoggerFactory.getLogger(FeedParser.class);

    private final ArticleSanitizer sanitizer;

    public FeedParser(ArticleSanitizer sanitizer) {
        this.sanitizer = sanitizer;
    }

    public FeedData parseFeed(String url) throws Exception {
        RssReader rssReader = parseFeedOrPage(url, true);
        Feed newFeed = rssReader.getFeed();
        List<Article> articleList = rssReader.getArticleList();
        completeArticleData(articleList, newFeed);
        return new FeedData(newFeed, articleList);
    }

    private RssReader parseFeedOrPage(String url, boolean parsePage) throws Exception {
        try {
            final RssReader reader = new RssReader();
            new ReaderHttpClient() {
                @Override
                public Void process(InputStream is) throws Exception {
                    reader.readRssFeed(is);
                    return null;
                }
            }.open(new URL(url));
            reader.getFeed().setRssUrl(url);
            return reader;
        } catch (Exception eRss) {
            boolean recoverable = !(eRss instanceof UnknownHostException ||
                    eRss instanceof FileNotFoundException);
            if (parsePage && recoverable) {
                try {
                    final RssExtractor extractor = new RssExtractor(url);
                    new ReaderHttpClient() {
                        @Override
                        public Void process(InputStream is) throws Exception {
                            extractor.readPage(is);
                            return null;
                        }
                    }.open(new URL(url));
                    List<String> feedList = extractor.getFeedList();
                    if (feedList == null || feedList.isEmpty()) {
                        logParsingError(url, eRss);
                    }
                    String feed = new FeedChooserStrategy().guess(feedList);
                    return parseFeedOrPage(feed, false);
                } catch (Exception ePage) {
                    logParsingError(url, ePage);
                }
            } else {
                logParsingError(url, eRss);
            }

            throw eRss;
        }
    }

    private void completeArticleData(List<Article> articleList, Feed feed) {
        for (Article article : articleList) {
            article.setTitle(StringUtils.abbreviate(TextSanitizer.sanitize(article.getTitle()), 4000));
            article.setCreator(StringUtils.abbreviate(article.getCreator(), 200));
            String baseUri = UrlUtil.getBaseUri(feed, article);
            article.setDescription(sanitizer.sanitize(baseUri, article.getDescription()));
        }
    }

    private void logParsingError(String url, Exception e) {
        if (log.isWarnEnabled()) {
            if (e instanceof UnknownHostException ||
                    e instanceof FileNotFoundException ||
                    e instanceof ConnectException) {
                log.warn(MessageFormat.format("Error parsing HTML page at URL {0} : {1}", url, e.getMessage()));
            } else {
                log.warn(MessageFormat.format("Error parsing HTML page at URL {0}", url));
            }
        }
    }
}
```

```java
package com.sismics.reader.core.service;

import com.sismics.reader.core.model.jpa.Article;
import com.sismics.reader.core.model.jpa.Feed;
import java.util.List;

public record FeedData(Feed feed, List<Article> articles) {

}
```

Key improvements and explanations:

*   **Dependency Injection:**  The `FeedService` now takes `FeedFetcher`, `ArticleManager`, `UserArticleManager`, `EventPublisher`, and `FeedMetadataUpdater` as constructor arguments.  This makes the class much easier to test and more flexible.
*   **Extracted Classes:**  The `FeedFetcher`, `ArticleManager`, `UserArticleManager`, `EventPublisher`, `FeedMetadataUpdater` and `FeedParser` classes handle their respective responsibilities. This significantly reduces the size and complexity of the `FeedService`.
*   **Removed Direct DAO Manipulation:** DAOs are now injected into the respective manager classes (`FeedFetcher`, `ArticleManager`, `UserArticleManager`), and the `FeedService` no longer interacts with them directly.  This adds a layer of abstraction and reduces coupling.
*   **EventPublisher:** Centralizes event posting logic. The FeedService only calls methods on EventPublisher.
*   **Centralized Error Handling** Moved error handling and logging to the `FeedFetcher` class.
*   **FeedParser**: Encapsulates the logic for parsing the feeds.
*   **Record FeedData**: A record `FeedData` to store the `Feed` and the `List<Article>`.
*   **AsyncEventBus assignment to Dependencies:** The `AsyncEventBus` assignment is now forwarded to the dependencies of the `FeedService`.
*   **Added counting the added articles:** Added the counting mechanism to the `ArticleManager`.

This refactored code is now significantly more modular, testable, and maintainable. It adheres to the Single Responsibility Principle better, and the dependencies are much more clearly defined and managed.
