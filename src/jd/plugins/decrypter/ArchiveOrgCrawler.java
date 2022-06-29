//jDownloader - Downloadmanager
//Copyright (C) 2009  JD-Team support@jdownloader.org
//
//This program is free software: you can redistribute it and/or modify
//it under the terms of the GNU General Public License as published by
//the Free Software Foundation, either version 3 of the License, or
//(at your option) any later version.
//
//This program is distributed in the hope that it will be useful,
//but WITHOUT ANY WARRANTY; without even the implied warranty of
//MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
//GNU General Public License for more details.
//
//You should have received a copy of the GNU General Public License
//along with this program.  If not, see <http://www.gnu.org/licenses/>.
package jd.plugins.decrypter;

import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.appwork.storage.JSonStorage;
import org.appwork.storage.TypeRef;
import org.appwork.utils.DebugMode;
import org.appwork.utils.Regex;
import org.appwork.utils.StringUtils;
import org.appwork.utils.encoding.URLEncode;
import org.appwork.utils.formatter.SizeFormatter;
import org.appwork.utils.parser.UrlQuery;
import org.jdownloader.plugins.components.config.ArchiveOrgConfig;
import org.jdownloader.plugins.config.PluginConfigInterface;
import org.jdownloader.plugins.config.PluginJsonConfig;
import org.jdownloader.scripting.JavaScriptEngineFactory;

import jd.PluginWrapper;
import jd.controlling.AccountController;
import jd.controlling.ProgressController;
import jd.http.Browser;
import jd.http.URLConnectionAdapter;
import jd.nutils.encoding.Encoding;
import jd.plugins.Account;
import jd.plugins.AccountRequiredException;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.FilePackage;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForDecrypt;
import jd.plugins.hoster.ArchiveOrg;

@DecrypterPlugin(revision = "$Revision$", interfaceVersion = 2, names = { "archive.org", "subdomain.archive.org" }, urls = { "https?://(?:www\\.)?archive\\.org/(?:details|download|stream|embed)/(?!copyrightrecords)@?.+", "https?://[^/]+\\.archive\\.org/view_archive\\.php\\?archive=[^\\&]+(?:\\&file=[^\\&]+)?" })
public class ArchiveOrgCrawler extends PluginForDecrypt {
    public ArchiveOrgCrawler(PluginWrapper wrapper) {
        super(wrapper);
    }

    private boolean isArchiveURL(final String url) throws MalformedURLException {
        if (url == null) {
            return false;
        } else {
            final UrlQuery query = UrlQuery.parse(url);
            return url.contains("view_archive.php") && query.get("file") == null;
        }
    }

    private final ArrayList<DownloadLink> decryptedLinks = new ArrayList<DownloadLink>();
    final Set<String>                     dups           = new HashSet<String>();
    private ArchiveOrg                    hostPlugin     = null;

    public ArrayList<DownloadLink> decryptIt(final CryptedLink param, ProgressController progress) throws Exception {
        br.setFollowRedirects(true);
        hostPlugin = (ArchiveOrg) getNewPluginForHostInstance("archive.org");
        param.setCryptedUrl(param.getCryptedUrl().replace("://www.", "://").replaceFirst("/(stream|embed)/", "/download/"));
        /*
         * 2020-08-26: Login might sometimes be required for book downloads.
         */
        final Account account = AccountController.getInstance().getValidAccount("archive.org");
        if (account != null) {
            hostPlugin.login(account, false);
        }
        URLConnectionAdapter con = null;
        boolean isArchiveContent = isArchiveURL(param.getCryptedUrl());
        if (isArchiveContent) {
            br.getPage(param.getCryptedUrl());
        } else {
            try {
                /* Check if we have a direct URL --> Host plugin */
                con = br.openGetConnection(param.getCryptedUrl());
                isArchiveContent = isArchiveURL(con.getURL().toString());
                /*
                 * 2020-03-04: E.g. directurls will redirect to subdomain e.g. ia800503.us.archive.org --> Sometimes the only way to differ
                 * between a file or expected html.
                 */
                final String host = Browser.getHost(con.getURL(), true);
                if (!isArchiveContent && (this.looksLikeDownloadableContent(con) || con.getLongContentLength() > br.getLoadLimit() || !host.equals("archive.org"))) {
                    // final DownloadLink fina = this.createDownloadlink(parameter.replace("archive.org", host_decrypted));
                    final DownloadLink dl = new DownloadLink(hostPlugin, null, "archive.org", param.getCryptedUrl(), true);
                    if (this.looksLikeDownloadableContent(con)) {
                        if (con.getCompleteContentLength() > 0) {
                            dl.setVerifiedFileSize(con.getCompleteContentLength());
                        }
                        dl.setFinalFileName(getFileNameFromHeader(con));
                        dl.setAvailable(true);
                    } else {
                        /* 2021-02-05: Either offline or account-only. Assume offline for now. */
                        dl.setAvailable(false);
                    }
                    decryptedLinks.add(dl);
                    return decryptedLinks;
                } else {
                    final int loadLimit = br.getLoadLimit();
                    try {
                        br.setLoadLimit(-1);
                        br.followConnection();
                    } finally {
                        br.setLoadLimit(loadLimit);
                    }
                }
            } finally {
                if (con != null) {
                    con.disconnect();
                }
            }
        }
        /*
         * All "account required" issues usually come with http error 403. See also ArchiveOrg host plugin errorhandling in function
         * "connectionErrorhandling".
         */
        if (br.containsHTML("(?i)>\\s*You must log in to view this content")) {
            /* 2021-02-24: <p class="theatre-title">You must log in to view this content</p> */
            throw new AccountRequiredException();
        } else if (br.containsHTML("(?i)>\\s*Item not available|>\\s*The item is not available due to issues with the item's content")) {
            throw new AccountRequiredException();
        } else if (br.getHttpConnection().getResponseCode() == 404) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        /*
         * Preview (= images of book pages) of books may be available along official download --> Only crawl book preview if no official
         * download is possible.
         */
        final boolean isOfficiallyDownloadable = br.containsHTML("class=\"download-button\"") && !br.containsHTML("class=\"download-lending-message\"");
        final boolean isBookPreviewAvailable = br.containsHTML("schema\\.org/Book");
        if (isBookPreviewAvailable && !isOfficiallyDownloadable) {
            return crawlBook(param, account);
        } else if (isArchiveContent) {
            return crawlArchiveContent();
        } else if (StringUtils.containsIgnoreCase(param.getCryptedUrl(), "/details/")) {
            return crawlDetails(param);
        } else {
            return crawlFiles(param);
        }
    }

    private ArrayList<DownloadLink> crawlFiles(final CryptedLink param) throws Exception {
        if (br.getHttpConnection().getResponseCode() == 404 || br.containsHTML(">The item is not available")) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        } else if (!br.containsHTML("\"/download/")) {
            logger.info("Maybe invalid link or nothing there to download: " + param.getCryptedUrl());
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        final boolean preferOriginal = PluginJsonConfig.get(ArchiveOrgConfig.class).isPreferOriginal();
        String subfolderPath = new Regex(param.getCryptedUrl(), "https?://[^/]+/download/(.*?)/?$").getMatch(0);
        subfolderPath = Encoding.urlDecode(subfolderPath, false);
        // final String fpName = br.getRegex("<h1>Index of [^<>\"]+/([^<>\"/]+)/?</h1>").getMatch(0);
        final String fpName = subfolderPath;
        String html = br.toString().replaceAll("(\\(\\s*<a.*?</a>\\s*\\))", "");
        final String[] htmls = new Regex(html, "<tr >(.*?)</tr>").getColumn(0);
        final String xmlURLs[] = br.getRegex("<a href\\s*=\\s*\"([^<>\"]+_files\\.xml)\"").getColumn(0);
        String xmlSource = null;
        if (xmlURLs != null && xmlURLs.length > 0) {
            for (String xmlURL : xmlURLs) {
                final Browser brc = br.cloneBrowser();
                brc.setFollowRedirects(true);
                xmlSource = brc.getPage(brc.getURL() + "/" + xmlURL);
                this.crawlXML(brc, subfolderPath);
            }
            return decryptedLinks;
        } else {
            /* Old/harder way */
            for (final String htmlsnippet : htmls) {
                String name = new Regex(htmlsnippet, "<a href=\"([^<>\"]+)\"").getMatch(0);
                final String[] rows = new Regex(htmlsnippet, "<td>(.*?)</td>").getColumn(0);
                if (name == null || rows.length < 3) {
                    /* Skip invalid items */
                    continue;
                }
                String filesize = rows[rows.length - 1];
                if (StringUtils.endsWithCaseInsensitive(name, "_files.xml") || StringUtils.endsWithCaseInsensitive(name, "_meta.sqlite") || StringUtils.endsWithCaseInsensitive(name, "_meta.xml") || StringUtils.endsWithCaseInsensitive(name, "_reviews.xml")) {
                    /* Skip invalid content */
                    continue;
                } else if (xmlSource != null && preferOriginal) {
                    /* Skip non-original content if user only wants original content. */
                    if (!new Regex(xmlSource, "<file name=\"" + Pattern.quote(name) + "\" source=\"original\"").matches()) {
                        continue;
                    }
                }
                if (filesize.equals("-")) {
                    /* Folder --> Goes back into decrypter */
                    final DownloadLink fina = createDownloadlink("https://archive.org/download/" + subfolderPath + "/" + name);
                    decryptedLinks.add(fina);
                } else {
                    /* File */
                    filesize += "b";
                    final String filename = Encoding.urlDecode(name, false);
                    final DownloadLink fina = createDownloadlink("https://archive.org/download/" + subfolderPath + "/" + name);
                    fina.setDownloadSize(SizeFormatter.getSize(filesize));
                    fina.setAvailable(true);
                    fina.setFinalFileName(filename);
                    if (xmlSource != null) {
                        final String sha1 = new Regex(xmlSource, "<file name=\"" + Pattern.quote(filename) + "\".*?<sha1>([a-f0-9]{40})</sha1>").getMatch(0);
                        if (sha1 != null) {
                            fina.setSha1Hash(sha1);
                        }
                        final String size = new Regex(xmlSource, "<file name=\"" + Pattern.quote(filename) + "\".*?<size>(\\d+)</size>").getMatch(0);
                        if (size != null) {
                            fina.setVerifiedFileSize(Long.parseLong(size));
                        }
                    }
                    fina.setProperty(DownloadLink.RELATIVE_DOWNLOAD_FOLDER_PATH, subfolderPath);
                    decryptedLinks.add(fina);
                }
            }
            /* 2020-03-04: Setting packagenames makes no sense anymore as packages will get split by subfolderpath. */
            final FilePackage fp = FilePackage.getInstance();
            if (fpName != null) {
                fp.setName(fpName);
                fp.addLinks(decryptedLinks);
            }
        }
        return decryptedLinks;
    }

    private ArrayList<DownloadLink> crawlDetails(final CryptedLink param) throws Exception {
        if (br.containsHTML("id=\"gamepadtext\"")) {
            /* 2020-09-29: Rare case: Download browser emulated games */
            final String subfolderPath = new Regex(param.getCryptedUrl(), "/details/([^/]+)").getMatch(0);
            br.getPage("https://archive.org/download/" + subfolderPath + "/" + subfolderPath + "_files.xml");
            this.crawlXML(this.br, subfolderPath);
            return this.decryptedLinks;
        }
        /** TODO: 2020-09-29: Consider taking the shortcut here to always use that XML straight away (?!) */
        int page = 2;
        do {
            if (br.containsHTML("This item is only available to logged in Internet Archive users")) {
                decryptedLinks.add(createDownloadlink(param.getCryptedUrl().replace("/details/", "/download/")));
                break;
            }
            final String showAll = br.getRegex("href=\"(/download/[^\"]*?)\">SHOW ALL").getMatch(0);
            if (showAll != null) {
                decryptedLinks.add(createDownloadlink(br.getURL(showAll).toString()));
                logger.info("Creating: " + br.getURL(showAll).toString());
                break;
            }
            final String[] details = br.getRegex("<div class=\"item-ia\".*? <a href=\"(/details/[^\"]*?)\" title").getColumn(0);
            if (details == null || details.length == 0) {
                break;
            }
            for (final String detail : details) {
                final DownloadLink link = createDownloadlink(br.getURL(detail).toString());
                decryptedLinks.add(link);
                if (isAbort()) {
                    break;
                } else {
                    distribute(link);
                }
            }
            br.getPage("?page=" + (page++));
        } while (!this.isAbort());
        return decryptedLinks;
    }

    private ArrayList<DownloadLink> crawlArchiveContent() throws Exception {
        /* 2020-09-07: Contents of a .zip/.rar file are also accessible and downloadable separately. */
        final String archiveName = new Regex(br.getURL(), ".*/([^/]+)$").getMatch(0);
        final FilePackage fp = FilePackage.getInstance();
        fp.setName(Encoding.htmlDecode(archiveName));
        final String[] htmls = br.getRegex("<tr><td>(.*?)</tr>").getColumn(0);
        for (final String html : htmls) {
            String url = new Regex(html, "(/download/[^\"\\']+)").getMatch(0);
            final String filesizeStr = new Regex(html, "id=\"size\">(\\d+)").getMatch(0);
            if (StringUtils.isEmpty(url)) {
                /* Skip invalid items */
                continue;
            }
            url = "https://archive.org" + url;
            final DownloadLink dl = this.createDownloadlink(url);
            if (filesizeStr != null) {
                dl.setDownloadSize(Long.parseLong(filesizeStr));
            }
            dl.setAvailable(true);
            dl._setFilePackage(fp);
            decryptedLinks.add(dl);
        }
        return decryptedLinks;
    }

    private ArrayList<DownloadLink> crawlBook(final CryptedLink param, final Account account) throws Exception {
        /* Crawl all pages of a book */
        final String bookID = new Regex(br.getURL(), "https?://[^/]+/(?:details|download)/([^/]+)").getMatch(0);
        if (bookID == null) {
            /* Developer mistake */
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        if (account != null && DebugMode.TRUE_IN_IDE_ELSE_FALSE) {
            /* Try to borrow book if account is available */
            final Browser brc = br.cloneBrowser();
            this.hostPlugin.borrowBook(brc, account, bookID);
            /* Refresh page */
            br.getPage(br.getURL());
        }
        String bookAjaxURL = br.getRegex("\\'([^\\'\"]+BookReaderJSIA\\.php\\?[^\\'\"]+)\\'").getMatch(0);
        if (bookAjaxURL == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        if (bookAjaxURL.contains(bookID) && !bookAjaxURL.endsWith(bookID)) {
            /* Correct URL */
            bookAjaxURL = new Regex(bookAjaxURL, "(.+" + Regex.escape(bookID) + ")").getMatch(0);
        }
        br.getPage(bookAjaxURL);
        if (br.getHttpConnection().getResponseCode() == 404) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        final Map<String, Object> root = JSonStorage.restoreFromString(br.toString(), TypeRef.HASHMAP);
        final Map<String, Object> data = (Map<String, Object>) root.get("data");
        final Map<String, Object> lendingInfo = (Map<String, Object>) data.get("lendingInfo");
        final long daysLeftOnLoan = ((Number) lendingInfo.get("daysLeftOnLoan")).longValue();
        final long secondsLeftOnLoan = ((Number) lendingInfo.get("secondsLeftOnLoan")).longValue();
        long loanedMillisecondsLeft = 0;
        if (daysLeftOnLoan > 0) {
            loanedMillisecondsLeft += daysLeftOnLoan * 24 * 60 * 60 * 1000;
        }
        if (secondsLeftOnLoan > 0) {
            loanedMillisecondsLeft += secondsLeftOnLoan * 1000;
        }
        final Map<String, Object> brOptions = (Map<String, Object>) data.get("brOptions");
        final String bookId = brOptions.get("bookId").toString();
        final String title = (String) brOptions.get("bookTitle");
        final List<Object> imagesO = (List<Object>) brOptions.get("data");
        final FilePackage fp = FilePackage.getInstance();
        fp.setName(title);
        long loanedUntilTimestamp = 0;
        /**
         * Borrowing books counts per session so if a user borrows a book via browser it won't be borrowed in JD even if user has added the
         * same account to JD.
         */
        boolean userHasBorrowedThisBook = false;
        if (loanedMillisecondsLeft > 0 && (Boolean) lendingInfo.get("userHasBorrowed")) {
            userHasBorrowedThisBook = true;
            loanedUntilTimestamp = System.currentTimeMillis() + loanedUntilTimestamp;
            logger.info("User has borrowed book which is currently being crawled: " + title);
        }
        for (final Object imageO : imagesO) {
            /*
             * Most of all objects will contain an array with 2 items --> Books always have two viewable pages. Exception = First page -->
             * Cover
             */
            final List<Object> pagesO = (List<Object>) imageO;
            for (final Object pageO : pagesO) {
                /* Grab "Preview"(???) version --> Usually "pageType":"NORMAL", "pageSide":"L", "viewable":true */
                final Map<String, Object> bookPage = (Map<String, Object>) pageO;
                final int pageNum = (int) JavaScriptEngineFactory.toLong(bookPage.get("leafNum"), -1);
                final String url = (String) bookPage.get("uri");
                if (StringUtils.isEmpty(url) || pageNum == -1) {
                    /* Skip invalid items (this should never happen) */
                    continue;
                }
                final DownloadLink dl = new DownloadLink(hostPlugin, null, "archive.org", url, true);
                dl.setName(pageNum + "_ " + title + ".jpg");
                if (userHasBorrowedThisBook) {
                    /* User has currently borrowed this book. */
                    dl.setProperty(ArchiveOrg.PROPERTY_BOOK_LOANED_UNTIL_TIMESTAMP, loanedUntilTimestamp);
                }
                /* Assume all are online & downloadable */
                dl.setAvailable(true);
                dl._setFilePackage(fp);
                dl.setProperty(ArchiveOrg.PROPERTY_BOOK_ID, bookID);
                /* Important! These URLs are not static! Make sure user cannot add the same pages multiple times! */
                dl.setLinkID(this.getHost() + "://" + bookId + pageNum);
                decryptedLinks.add(dl);
            }
        }
        return decryptedLinks;
    }

    private void crawlXML(final Browser br, final String root) {
        final boolean preferOriginal = PluginJsonConfig.get(ArchiveOrgConfig.class).isPreferOriginal();
        final boolean crawlArchiveView = PluginJsonConfig.get(ArchiveOrgConfig.class).isCrawlArchiveView();
        final String[] items = new Regex(br.toString(), "<file\\s*(.*?)\\s*</file>").getColumn(0);
        /*
         * 2020-03-04: Prefer crawling xml if possible as we then get all contents of that folder including contents of subfolders via only
         * one request!
         */
        for (final String item : items) {
            /* <old_version>true</old_version> */
            final boolean isOldVersion = item.contains("old_version");
            final boolean isOriginal = item.contains("source=\"original\"");
            final boolean isMetadata = item.contains("<format>Metadata</format>");
            final boolean isArchiveViewSupported = item.matches("(?i)(?s).*<format>\\s*(RAR|ZIP)\\s*</format>.*");
            String pathWithFilename = new Regex(item, "name=\"([^\"]+)").getMatch(0);
            final String filesizeStr = new Regex(item, "<size>(\\d+)</size>").getMatch(0);
            final String sha1hash = new Regex(item, "<sha1>([a-f0-9]+)</sha1>").getMatch(0);
            if (pathWithFilename == null) {
                continue;
            } else if (isOldVersion || isMetadata) {
                /* Skip old elements and metadata! They are invisible to the user anyways */
                continue;
            } else if (preferOriginal && !isOriginal) {
                /* Skip non-original content if user only wants original content. */
                continue;
            }
            if (Encoding.isHtmlEntityCoded(pathWithFilename)) {
                /* Will sometimes contain "&amp;" */
                pathWithFilename = Encoding.htmlOnlyDecode(pathWithFilename);
            }
            String pathEncoded;
            String pathWithoutFilename = null;
            String filename = null;
            /* Search filename and properly encode content-URL. */
            if (pathWithFilename.contains("/")) {
                final String[] urlParts = pathWithFilename.split("/");
                pathEncoded = "";
                pathWithoutFilename = "";
                int index = 0;
                for (final String urlPart : urlParts) {
                    final boolean isLastSegment = index >= urlParts.length - 1;
                    pathEncoded += URLEncode.encodeURIComponent(urlPart);
                    if (isLastSegment) {
                        filename = urlPart;
                    } else {
                        pathWithoutFilename += urlPart;
                        pathWithoutFilename += "/";
                        pathEncoded += "/";
                    }
                    index++;
                }
            } else {
                pathEncoded = URLEncode.encodeURIComponent(pathWithFilename);
                filename = pathWithFilename;
            }
            final String url = "https://archive.org/download/" + root + "/" + pathEncoded;
            if (dups.add(url)) {
                final DownloadLink downloadURL = createDownloadlink(url);
                downloadURL.setDownloadSize(SizeFormatter.getSize(filesizeStr));
                downloadURL.setAvailable(true);
                downloadURL.setFinalFileName(filename);
                // final String subfolderPathInName = new Regex(pathWithFilename, "(.+)/[^/]+$").getMatch(0);
                final String thisPath;
                if (pathWithoutFilename != null) {
                    thisPath = root + "/" + pathWithoutFilename;
                } else {
                    thisPath = root;
                }
                downloadURL.setProperty(DownloadLink.RELATIVE_DOWNLOAD_FOLDER_PATH, thisPath);
                final FilePackage fp = FilePackage.getInstance();
                fp.setName(thisPath);
                downloadURL._setFilePackage(fp);
                if (sha1hash != null) {
                    downloadURL.setSha1Hash(sha1hash);
                }
                decryptedLinks.add(downloadURL);
                if (crawlArchiveView && isArchiveViewSupported) {
                    final DownloadLink archiveViewURL = createDownloadlink(url + "/");
                    decryptedLinks.add(archiveViewURL);
                }
            }
        }
    }

    @Override
    protected boolean looksLikeDownloadableContent(final URLConnectionAdapter urlConnection) {
        return hostPlugin.looksLikeDownloadableContent(urlConnection);
    }

    /* NO OVERRIDE!! */
    public boolean hasCaptcha(CryptedLink link, jd.plugins.Account acc) {
        return false;
    }

    @Override
    public Class<? extends PluginConfigInterface> getConfigInterface() {
        return ArchiveOrgConfig.class;
    }
}