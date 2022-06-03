//    jDownloader - Downloadmanager
//    Copyright (C) 2012  JD-Team support@jdownloader.org
//
//    This program is free software: you can redistribute it and/or modify
//    it under the terms of the GNU General Public License as published by
//    the Free Software Foundation, either version 3 of the License, or
//    (at your option) any later version.
//
//    This program is distributed in the hope that it will be useful,
//    but WITHOUT ANY WARRANTY; without even the implied warranty of
//    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
//    GNU General Public License for more details.
//
//    You should have received a copy of the GNU General Public License
//    along with this program.  If not, see <http://www.gnu.org/licenses/>.
package jd.plugins.decrypter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import org.appwork.utils.parser.UrlQuery;
import org.jdownloader.plugins.controller.LazyPlugin;

import jd.PluginWrapper;
import jd.controlling.ProgressController;
import jd.http.Browser;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.FilePackage;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForDecrypt;
import jd.plugins.hoster.MotherLessCom;

@DecrypterPlugin(revision = "$Revision$", interfaceVersion = 3, names = {}, urls = {})
public class MotherLessComCrawler extends PluginForDecrypt {
    private String      fpName = null;
    private FilePackage fp;

    public MotherLessComCrawler(PluginWrapper wrapper) {
        super(wrapper);
    }

    public static List<String[]> getPluginDomains() {
        final List<String[]> ret = new ArrayList<String[]>();
        // each entry in List<String[]> will result in one PluginForDecrypt, Plugin.getHost() will return String[0]->main domain
        ret.add(new String[] { "motherless.com" });
        return ret;
    }

    public static String[] getAnnotationNames() {
        return buildAnnotationNames(getPluginDomains());
    }

    @Override
    public String[] siteSupportedNames() {
        return buildSupportedNames(getPluginDomains());
    }

    public static String[] getAnnotationUrls() {
        return buildAnnotationUrls(getPluginDomains());
    }

    public static String[] buildAnnotationUrls(final List<String[]> pluginDomains) {
        final List<String> ret = new ArrayList<String>();
        for (final String[] domains : pluginDomains) {
            String regex = "https?://(?:www\\.)?" + buildHostsPatternPart(domains) + "/[A-Z0-9]{6,9}(/[A-Z0-9]{7})?";
            regex += "|https?://(?:www\\.)?" + buildHostsPatternPart(domains) + "/g/[A-Za-z0-9\\-_]+/[A-Z0-9]{7}";
            regex += "|https?://(?:www\\.)?" + buildHostsPatternPart(domains) + "/f/[^/]+/(?:images|videos|galleries)";
            regex += "|https?://(?:www\\.)?" + buildHostsPatternPart(domains) + "/(?:m|u)/([^/\\?]+)";
            ret.add(regex);
        }
        return ret.toArray(new String[0]);
    }

    @Override
    public LazyPlugin.FEATURE[] getFeatures() {
        return new LazyPlugin.FEATURE[] { LazyPlugin.FEATURE.IMAGE_GALLERY, LazyPlugin.FEATURE.XXX };
    }

    // DEV NOTES:
    //
    // REGEX run down, allows the following
    // motherless.com/g/WHAT_EVER_HERE/UID
    // motherless.com/UID1/UID
    // motherless.com/UID
    //
    // - don't support: groups(/g[a-z]{1}/), images or videos they can have over 1000 pages
    // - supports spanning pages and galleries with submenu (MORE links).
    // - set same User-Agent from hoster plugin, making it harder to distinguish.
    // - Server issues can return many 503's in high load situations.
    // - Server also punishes user who downloads with too many connections. This is a linkchecking issue also, as grabs info from headers.
    // - To reduce server loads associated with linkchecking, I've set 'setAvailable(true) for greater than 5 pages.
    private final String TYPE_FAVOURITES_VIDEOS = "https?://[^/]+/f/[^/]+/videos";
    private final String TYPE_USER              = "https?://[^/]+//(m|u)/([^/]+)";
    private final String TYPE_GALLERY           = "https?://[^/]+/g/([A-Za-z0-9\\-_]+)/([A-Z0-9]{7})";
    private final String TYPE_FAVOURITES_ALL    = "https?://[^/]+/f/([^/]+)/(.+)";

    @SuppressWarnings("deprecation")
    public ArrayList<DownloadLink> decryptIt(final CryptedLink param, ProgressController progress) throws Exception {
        final ArrayList<DownloadLink> decryptedLinks = new ArrayList<DownloadLink>() {
            @Override
            public boolean add(DownloadLink e) {
                if (fp != null) {
                    fp.add(e);
                }
                distribute(e);
                return super.add(e);
            }
        };
        final MotherLessCom hostPlugin = (MotherLessCom) this.getNewPluginForHostInstance(this.getHost());
        br.setLoadLimit(br.getLoadLimit() * 5);
        br.setFollowRedirects(true);
        br.setAllowedResponseCodes(new int[] { 503 });
        br.getHeaders().put("User-Agent", MotherLessCom.ua);
        // alters 'domain/(g/name/)uid' by removing all but uid
        param.setCryptedUrl(param.getCryptedUrl().replaceAll("https?://[^/]+/g/[\\w\\-]+/", "https://" + this.getHost() + "/"));
        br.getPage(param.getCryptedUrl());
        /* First check if the item is offline or if it is a single media item. */
        final boolean subscriberPremiumOnly = MotherLessCom.isWatchSubscriberPremiumOnly(br);
        if (MotherLessCom.isDownloadPremiumOnly(br)) {
            final DownloadLink dl = createDownloadlink(param.getCryptedUrl());
            dl.setProperty(MotherLessCom.PROPERTY_TYPE, "registered");
            dl.setAvailableStatus(hostPlugin.parseFileInfoAndSetFilename(dl));
            decryptedLinks.add(dl);
            return decryptedLinks;
        } else if (br.containsHTML("(?i)class=\"red-pill-button rounded-corners-r5\">\\s*Reply\\s*</a>")) {
            logger.info("This is a forum link without any downloadable content: " + param.getCryptedUrl());
            return decryptedLinks;
        } else if (br.containsHTML(MotherLessCom.html_contentFriendsOnly)) {
            logger.warning("Unsupported format for " + param.getCryptedUrl());
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        // Common bug: It can happen that the texts that we use to differ between the kinds of links change so the crawler breaks down,
        // always check that first!
        if (subscriberPremiumOnly && br.containsHTML(MotherLessCom.html_contentSubscriberImage)) {
            final DownloadLink dl = createDownloadlink(param.getCryptedUrl());
            dl.setContentUrl(param.getCryptedUrl());
            dl.setProperty(MotherLessCom.PROPERTY_TYPE, "image");
            dl.setProperty(MotherLessCom.PROPERTY_ONLYREGISTERED, true);
            dl.setAvailableStatus(hostPlugin.parseFileInfoAndSetFilename(dl));
            decryptedLinks.add(dl);
            return decryptedLinks;
        } else if (subscriberPremiumOnly && br.containsHTML(MotherLessCom.html_contentSubscriberVideo)) {
            final DownloadLink dl = createDownloadlink(param.getCryptedUrl());
            dl.setContentUrl(param.getCryptedUrl());
            dl.setProperty(MotherLessCom.PROPERTY_TYPE, "video");
            dl.setProperty(MotherLessCom.PROPERTY_ONLYREGISTERED, true);
            dl.setAvailableStatus(hostPlugin.parseFileInfoAndSetFilename(dl));
            decryptedLinks.add(dl);
            return decryptedLinks;
        } else if (MotherLessCom.isOffline(br)) {
            // this can have text which could be contained in previous if statements... has to be last!
            final DownloadLink dl = createDownloadlink(param.getCryptedUrl());
            dl.setContentUrl(param.getCryptedUrl());
            dl.setProperty(MotherLessCom.PROPERTY_TYPE, "offline");
            dl.setAvailableStatus(hostPlugin.parseFileInfoAndSetFilename(dl));
            decryptedLinks.add(dl);
            return decryptedLinks;
        }
        if (param.getCryptedUrl().matches(TYPE_FAVOURITES_ALL)) {
            fpName = this.br.getRegex("<title>([^<>\"]*?)\\- MOTHERLESS\\.COM</title>").getMatch(0);
            crawlGallery(decryptedLinks, param, progress);
        } else {
            final String[] subGal = br.getRegex("(?i)<a href=\"(/[A-Z0-9]+)\" title=\"More [^ ]+ in this gallery\" class=\"pop plain more\">See More &raquo;</a>").getColumn(0);
            if (subGal.length != 0) {
                for (final String subuid : subGal) {
                    br.getPage(subuid);
                    crawlGallery(decryptedLinks, param, progress);
                }
                return decryptedLinks;
            }
            /* Check for single video, image or gallery */
            final String contentID = br.getRegex("__codename = '([A-Z0-9]+)'").getMatch(0);
            if (isVideo(br)) {
                if (contentID == null) {
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
                final DownloadLink dl = createDownloadlink(br.getURL("/" + contentID).toString());
                dl.setContentUrl(param.getCryptedUrl());
                dl.setProperty(MotherLessCom.PROPERTY_TYPE, "video");
                dl.setAvailableStatus(hostPlugin.parseFileInfoAndSetFilename(dl));
                decryptedLinks.add(dl);
            } else if (isImage(br)) {
                if (contentID == null) {
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
                final DownloadLink dl = createDownloadlink(br.getURL("/" + contentID).toString());
                dl.setContentUrl(param.getCryptedUrl());
                dl.setProperty(MotherLessCom.PROPERTY_TYPE, "image");
                dl.setAvailableStatus(hostPlugin.parseFileInfoAndSetFilename(dl));
                decryptedLinks.add(dl);
            } else {
                /* Assume we have some type of gallery */
                crawlGallery(decryptedLinks, param, progress);
            }
        }
        return decryptedLinks;
    }

    public static final boolean isVideo(final Browser br) {
        if (br.containsHTML("(mediatype\\s*=\\s*'video'|" + MotherLessCom.html_notOnlineYet + ")")) {
            return true;
        } else {
            return false;
        }
    }

    public static final boolean isImage(final Browser br) {
        if (br.containsHTML("mediatype\\s*=\\s*'image")) {
            return true;
        } else {
            return false;
        }
    }

    // finds the uid within the grouping
    private String formLink(String singlelink) {
        if (singlelink.startsWith("/")) {
            singlelink = "https://" + this.getHost() + singlelink;
        }
        String ID = new Regex(singlelink, "https?://[^/]+/[A-Z0-9]+/([A-Z0-9]+)").getMatch(0);
        if (ID != null) {
            singlelink = "https://" + this.getHost() + "/" + ID;
        }
        return singlelink;
    }

    /** Supports all kinds of multiple page urls that motherless.com has. */
    @SuppressWarnings("deprecation")
    private void crawlGallery(ArrayList<DownloadLink> ret, final CryptedLink param, ProgressController progress) throws IOException {
        String relative_path = null;
        final HashSet<String> dupes = new HashSet<String>();
        if (param.getCryptedUrl().matches(TYPE_FAVOURITES_ALL)) {
            final String username = new Regex(param.getCryptedUrl(), TYPE_FAVOURITES_ALL).getMatch(0);
            /* images or videos */
            final String media_type = new Regex(param.getCryptedUrl(), TYPE_FAVOURITES_ALL).getMatch(1);
            fpName = "Favorites of " + username + " - " + media_type;
            relative_path = username + "/" + media_type;
        } else {
            if (fpName == null) {
                fpName = br.getRegex("<title>MOTHERLESS\\.COM - Go Ahead She Isn't Looking!\\s*:(.*?)</title>").getMatch(0);
                if (fpName == null) {
                    fpName = br.getRegex("<div class=\"member-bio-username\">.*?'s Gallery &bull; (.*?)</div>").getMatch(0);
                    if (fpName == null) {
                        fpName = br.getRegex("<title>([^<>\"]*?) -\\s*MOTHERLESS\\.COM</title>").getMatch(0);
                    }
                }
            }
            if (fpName == null) {
                /* Try to fallback to ID */
                fpName = new Regex(param.getCryptedUrl(), "https?://[^/]+/(.+)").getMatch(0);
            }
        }
        if (fpName != null) {
            fpName = fpName.trim();
            fp = FilePackage.getInstance();
            fp.setName(fpName);
        }
        String GM = br.getRegex("<a href=\"(/GM\\w+)\"").getMatch(0); // Gallery Mixed
        if (GM != null) {
            br.getPage(GM);
        }
        /* Find number of pages to walk through */
        final String[] pageURLs = br.getRegex("<a href=\"([^\"]+page=\\d+[^\"]*)\"").getColumn(0);
        int maxPage = 1;
        for (final String pageURL : pageURLs) {
            final UrlQuery query = UrlQuery.parse(pageURL);
            final int page = Integer.parseInt(query.get("page"));
            if (page > maxPage) {
                maxPage = page;
            }
        }
        final HashSet<String> pages = new HashSet<String>();
        logger.info("Found " + maxPage + " page(s), crawling now...");
        int page = 1;
        while (true) {
            if (param.getCryptedUrl().contains("/galleries")) {
                /*
                 * 2020-01-21: Add all favorite galleries of a user. These will go back into the decrypter to crawl the picture/video
                 * content.
                 */
                /* Do not set packagename here. Each gallery should go into a separate package. */
                fp = null;
                final String[] galleries = br.getRegex("data-gallery-codename=\"([A-Z0-9]+)").getColumn(0);
                if (galleries == null || galleries.length == 0) {
                    return;
                }
                for (final String galleryID : galleries) {
                    ret.add(this.createDownloadlink("https://" + this.getHost() + "/G" + galleryID));
                }
            } else {
                final String[] videolinks = br.getRegex("<a href=\"(?:https?://[^/]+)?(/[^\"]+)\" class=\"img-container\" target=\"_self\">\\s*<span class=\"currently-playing-icon\"").getColumn(0);
                if (videolinks != null && videolinks.length != 0) {
                    for (String singlelink : videolinks) {
                        final String contentID = new Regex(singlelink, "/([A-Z0-9]+$)").getMatch(0);
                        if (!dupes.add(contentID)) {
                            continue;
                        } else if (contentID != null) {
                            singlelink = "https://" + this.getHost() + "/" + contentID;
                        }
                        singlelink = formLink(singlelink);
                        final DownloadLink dl = createDownloadlink(singlelink);
                        dl.setContentUrl(singlelink);
                        dl.setProperty(MotherLessCom.PROPERTY_TYPE, "video");
                        dl.setAvailable(true);
                        if (relative_path != null) {
                            dl.setProperty(DownloadLink.RELATIVE_DOWNLOAD_FOLDER_PATH, relative_path);
                        }
                        final String title = regexMediaTitle(br, contentID);
                        if (title != null) {
                            dl.setProperty(MotherLessCom.PROPERTY_TITLE, Encoding.htmlDecode(title).trim());
                        }
                        MotherLessCom.setFilename(dl);
                        ret.add(dl);
                    }
                }
                final String[] picturelinks = br.getRegex("<a href=\"(?:https?://[^/]+)?(?:/g/[^/]+)?(/[a-zA-Z0-9]+){1,2}\"[^>]*class=\"img-container\"").getColumn(0);
                if (picturelinks != null && picturelinks.length != 0) {
                    for (String singlelink : picturelinks) {
                        final String contentID = new Regex(singlelink, "/([A-Z0-9]+$)").getMatch(0);
                        if (!dupes.add(contentID)) {
                            /* Already added as video content --> Skip */
                            continue;
                        }
                        singlelink = formLink(singlelink);
                        final DownloadLink dl = createDownloadlink(singlelink);
                        dl.setContentUrl(singlelink);
                        dl.setProperty(MotherLessCom.PROPERTY_TYPE, "image");
                        dl.setAvailable(true);
                        if (relative_path != null) {
                            dl.setProperty(DownloadLink.RELATIVE_DOWNLOAD_FOLDER_PATH, relative_path);
                        }
                        final String title = regexMediaTitle(br, contentID);
                        if (title != null) {
                            dl.setProperty(MotherLessCom.PROPERTY_TITLE, Encoding.htmlDecode(title).trim());
                        }
                        MotherLessCom.setFilename(dl);
                        ret.add(dl);
                    }
                }
                if (picturelinks.length == 0 && videolinks.length == 0) {
                    logger.warning("Decrypter failed for link: " + param.getCryptedUrl());
                    return;
                }
            }
            logger.info("Crawled page " + page + "/" + maxPage + " | Results so far: " + ret.size());
            String nextPageURL = br.getRegex("(?i)<a href=\"(/[^<>\"]+\\?page=\\d+)\"[^>]+>NEXT").getMatch(0);
            if (nextPageURL == null) {
                nextPageURL = br.getRegex("<a href=\"(/[^<>\"]+\\?page=\\d+)\"[^>]+rel\\s*=\\s*\"next\"").getMatch(0);
            }
            if (nextPageURL == null) {
                /* 2022-05-30 */
                nextPageURL = br.getRegex("link rel=\"next\" href=\"([^\"]+)\"").getMatch(0);
            }
            if (page == maxPage) {
                logger.info("Stopping because: Reached last page");
                break;
            } else if (nextPageURL == null) {
                logger.info("Stopping because: Failed to find nextPage");
                break;
            } else if (!pages.add(nextPageURL)) {
                /* Fail-safe */
                logger.info("Stopping because: Already crawled current nextPage");
                break;
            } else if (this.isAbort()) {
                logger.info("Stopping because: Aborted by user");
                break;
            } else {
                /* Double-check that we're accessing the expected page */
                final UrlQuery nextPageQuery = UrlQuery.parse(nextPageURL);
                final String nextPageNumberStr = nextPageQuery.get("page");
                if (nextPageNumberStr == null || Integer.parseInt(nextPageNumberStr) != (page + 1)) {
                    /* This should never happen */
                    logger.warning("Stopping because: Unexpected nextPage value: " + nextPageNumberStr);
                    break;
                }
                br.getPage(nextPageURL);
                page++;
            }
        }
    }

    private static String regexMediaTitle(final Browser br, final String contentID) {
        return br.getRegex("<a href=\"[^\"]+[^/]+/" + contentID + "\" title=\"([^\"]+)\"").getMatch(0);
    }

    public boolean hasCaptcha(CryptedLink link, jd.plugins.Account acc) {
        return false;
    }

    @Override
    public int getMaxConcurrentProcessingInstances() {
        /* 2020-01-21: Avoid overloading the website */
        return 1;
    }
}