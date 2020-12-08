//  jDownloader - Downloadmanager
//  Copyright (C) 2013  JD-Team support@jdownloader.org
//
//  This program is free software: you can redistribute it and/or modify
//  it under the terms of the GNU General Public License as published by
//  the Free Software Foundation, either version 3 of the License, or
//  (at your option) any later version.
//
//  This program is distributed in the hope that it will be useful,
//  but WITHOUT ANY WARRANTY; without even the implied warranty of
//  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
//  GNU General Public License for more details.
//
//  You should have received a copy of the GNU General Public License
//  along with this program.  If not, see <http://www.gnu.org/licenses/>.
package jd.plugins.hoster;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.appwork.storage.JSonStorage;
import org.appwork.storage.TypeRef;
import org.appwork.utils.DebugMode;
import org.appwork.utils.StringUtils;
import org.appwork.utils.formatter.SizeFormatter;
import org.appwork.utils.parser.UrlQuery;
import org.jdownloader.captcha.v2.challenge.recaptcha.v2.CaptchaHelperHostPluginRecaptchaV2;
import org.jdownloader.plugins.components.config.GoogleConfig;
import org.jdownloader.plugins.components.config.GoogleConfig.PreferredQuality;
import org.jdownloader.plugins.components.google.GoogleHelper;
import org.jdownloader.plugins.components.youtube.YoutubeHelper;
import org.jdownloader.plugins.components.youtube.YoutubeStreamData;
import org.jdownloader.plugins.config.PluginConfigInterface;
import org.jdownloader.plugins.config.PluginJsonConfig;
import org.jdownloader.scripting.JavaScriptEngineFactory;

import jd.PluginWrapper;
import jd.controlling.AccountController;
import jd.http.Browser;
import jd.http.URLConnectionAdapter;
import jd.nutils.encoding.Encoding;
import jd.nutils.encoding.HTMLEntities;
import jd.parser.Regex;
import jd.parser.html.Form;
import jd.plugins.Account;
import jd.plugins.Account.AccountType;
import jd.plugins.AccountInfo;
import jd.plugins.AccountUnavailableException;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;
import jd.plugins.components.UserAgents;

@HostPlugin(revision = "$Revision$", interfaceVersion = 3, names = { "drive.google.com" }, urls = { "https?://(?:www\\.)?(?:docs|drive)\\.google\\.com/(?:(?:leaf|open|uc)\\?([^<>\"/]+)?id=[A-Za-z0-9\\-_]+|(?:a/[a-zA-z0-9\\.]+/)?(?:file|document)/d/[A-Za-z0-9\\-_]+)|https?://video\\.google\\.com/get_player\\?docid=[A-Za-z0-9\\-_]+" })
public class GoogleDrive extends PluginForHost {
    public GoogleDrive(PluginWrapper wrapper) {
        super(wrapper);
        this.enablePremium("https://accounts.google.com/signup");
    }

    @Override
    public String getAGBLink() {
        return "https://support.google.com/drive/answer/2450387?hl=en-GB";
    }

    @Override
    public String[] siteSupportedNames() {
        return new String[] { "drive.google.com", "docs.google.com", "googledrive" };
    }

    @Override
    public String rewriteHost(final String host) {
        if (host == null || host.equalsIgnoreCase("docs.google.com")) {
            return this.getHost();
        } else {
            return super.rewriteHost(host);
        }
    }

    @Override
    public boolean isSpeedLimited(DownloadLink link, Account account) {
        return false;
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return FREE_MAXDOWNLOADS;
    }

    public void correctDownloadLink(final DownloadLink link) throws PluginException {
        final String id = getFID(link);
        if (id == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        } else {
            link.setPluginPatternMatcher("https://drive.google.com/file/d/" + id);
        }
    }

    @Override
    public String getLinkID(final DownloadLink link) {
        final String id = getFID(link);
        if (id != null) {
            return getHost().concat("://".concat(id));
        } else {
            return super.getLinkID(link);
        }
    }

    private static final String NOCHUNKS                      = "NOCHUNKS";
    private boolean             privatefile                   = false;
    private boolean             fileHasReachedServersideQuota = false;
    private boolean             specialError403               = false;
    /* Connection stuff */
    // private static final boolean FREE_RESUME = true;
    // private static final int FREE_MAXCHUNKS = 0;
    private static final int    FREE_MAXDOWNLOADS             = 20;
    private static Object       CAPTCHA_LOCK                  = new Object();
    public static final String  API_BASE                      = "https://www.googleapis.com/drive/v3";

    @SuppressWarnings("deprecation")
    private String getFID(final DownloadLink link) {
        // known url formats
        // https://docs.google.com/file/d/0B4AYQ5odYn-pVnJ0Z2V4d1E5UWc/preview?pli=1
        // can't dl these particular links, same with document/doc, presentation/present and view
        // https://docs.google.com/uc?id=0B4AYQ5odYn-pVnJ0Z2V4d1E5UWc&export=download
        // https://docs.google.com/leaf?id=0B_QJaGmmPrqeZjJkZDFmYzEtMTYzMS00N2Y2LWI2NDUtMjQ1ZjhlZDhmYmY3
        // https://docs.google.com/open?id=0B9Z2XD2XD2iQNmxzWjd1UTdDdnc
        // https://video.google.com/get_player?docid=0B2vAVBc_577958658756vEo2eUk
        if (link == null) {
            return null;
        } else {
            String id = new Regex(link.getDownloadURL(), "/(?:file|document)/d/([a-zA-Z0-9\\-_]+)").getMatch(0);
            if (id == null) {
                id = new Regex(link.getDownloadURL(), "video\\.google\\.com/get_player\\?docid=([A-Za-z0-9\\-_]+)").getMatch(0);
                if (id == null) {
                    id = new Regex(link.getDownloadURL(), "(?!rev)id=([a-zA-Z0-9\\-_]+)").getMatch(0);
                }
            }
            return id;
        }
    }

    /**
     * Contains the quality modifier of the last chosen quality. This property gets reset on reset DownloadLink to ensure that a user cannot
     * change the quality and then resume the started download with another URL.
     */
    private static final String PROPERTY_USED_QUALITY = "USED_QUALITY";
    /* Packagizer property */
    public static final String  PROPERTY_ROOT_DIR     = "root_dir";
    public String               agent                 = null;
    private boolean             isStreamable          = false;
    private String              dllink                = null;

    /** Only call this if the user is not logged in! */
    public Browser prepBrowser(Browser pbr) {
        // used within the decrypter also, leave public
        // language determined by the accept-language
        // user-agent required to use new ones otherwise blocks with javascript notice.
        if (pbr == null) {
            pbr = new Browser();
        }
        if (agent == null) {
            agent = UserAgents.stringUserAgent();
        }
        pbr.getHeaders().put("User-Agent", agent);
        pbr.getHeaders().put("Accept-Language", "en-gb, en;q=0.9");
        pbr.setCustomCharset("utf-8");
        pbr.setFollowRedirects(true);
        pbr.setAllowedResponseCodes(new int[] { 429 });
        return pbr;
    }

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws Exception {
        return requestFileInformation(link, false);
    }

    private AvailableStatus requestFileInformation(final DownloadLink link, final boolean isDownload) throws Exception {
        /* TODO: Decide whether to use website- or API here. */
        if (useAPI()) {
            return this.requestFileInformationAPI(link, isDownload);
        } else {
            return this.requestFileInformationWebsite(link, null, isDownload);
        }
    }

    private AvailableStatus requestFileInformationAPI(final DownloadLink link, final boolean isDownload) throws Exception {
        final String fid = this.getFID(link);
        if (fid == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        final UrlQuery queryFile = new UrlQuery();
        queryFile.appendEncoded("fileId", fid);
        queryFile.add("supportsAllDrives", "true");
        queryFile.appendEncoded("fields", getFieldsAPI());
        queryFile.appendEncoded("key", getAPIKey());
        br.getPage(jd.plugins.hoster.GoogleDrive.API_BASE + "/files/" + fid + "?" + queryFile.toString());
        /* TODO: 2020-12-07: Check offline detection */
        if (br.getHttpConnection().getResponseCode() == 404) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        } else {
            final Map<String, Object> entries = JSonStorage.restoreFromString(br.toString(), TypeRef.HASHMAP);
            parseFileInfoAPI(link, entries);
            return AvailableStatus.TRUE;
        }
    }

    public static final String getFieldsAPI() {
        return "kind,mimeType,id,name,size,description,md5Checksum";
    }

    public static final boolean useAPI() {
        final boolean reallyUseAPI = false;
        return reallyUseAPI && DebugMode.TRUE_IN_IDE_ELSE_FALSE;
    }

    public static final String getAPIKey() {
        return "YourApiKeyHere";
        // return "";
    }

    public static void parseFileInfoAPI(final DownloadLink link, final Map<String, Object> entries) {
        final String filename = (String) entries.get("name");
        final String md5Checksum = (String) entries.get("md5Checksum");
        final long fileSize = JavaScriptEngineFactory.toLong(entries.get("size"), 0);
        final String description = (String) entries.get("description");
        if (!StringUtils.isEmpty(filename)) {
            link.setFinalFileName(filename);
        }
        if (fileSize > 0) {
            link.setDownloadSize(fileSize);
            link.setVerifiedFileSize(fileSize);
        }
        link.setAvailable(true);
        if (!StringUtils.isEmpty("md5Checksum")) {
            link.setMD5Hash(md5Checksum);
        }
        if (!StringUtils.isEmpty(description) && StringUtils.isEmpty(link.getComment())) {
            link.setComment(description);
        }
    }

    private AvailableStatus requestFileInformationWebsite(final DownloadLink link, Account account, final boolean isDownload) throws Exception {
        this.br = new Browser();
        privatefile = false;
        fileHasReachedServersideQuota = false;
        /* Prefer given account vs random account */
        if (account == null) {
            account = AccountController.getInstance().getValidAccount(this.getHost());
        }
        if (account != null) {
            login(br, account, false);
        } else {
            prepBrowser(br);
        }
        if (getFID(link) == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        String filename = null;
        String filesizeStr = null;
        /* 2020-12-01: Only for testing! */
        final boolean allowExperimentalLinkcheck = false;
        if (DebugMode.TRUE_IN_IDE_ELSE_FALSE && allowExperimentalLinkcheck) {
            final Browser br2 = br.cloneBrowser();
            br2.getHeaders().put("X-Drive-First-Party", "DriveViewer");
            /* 2020-12-01: authuser=0 also for logged-in users! */
            br2.postPage("https://drive.google.com/uc?id=" + this.getFID(link) + "&authuser=0&export=download", "");
            if (br2.getHttpConnection().getResponseCode() == 404) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            final String json = br2.getRegex(".*(\\{.+\\})$").getMatch(0);
            final Map<String, Object> entries = JSonStorage.restoreFromString(json, TypeRef.HASHMAP);
            /* 2020-12-01: E.g. "SCAN_CLEAN" or "TOO_LARGE" or "QUOTA_EXCEEDED" */
            // final String disposition = (String) entries.get("disposition");
            /* 2020-12-01: E.g. "OK" or "WARNING" or "ERROR" */
            final String scanResult = (String) entries.get("scanResult");
            filename = (String) entries.get("fileName");
            final Object filesizeO = entries.get("sizeBytes");
            if (!StringUtils.isEmpty(filename)) {
                link.setFinalFileName(filename);
            }
            if (filesizeO != null && filesizeO instanceof Number) {
                final long filesize = ((Number) filesizeO).longValue();
                if (filesize > 0) {
                    link.setDownloadSize(filesize);
                    link.setVerifiedFileSize(filesize);
                }
            }
            if (scanResult.equalsIgnoreCase("error")) {
                /* Assume that this has happened: {"disposition":"QUOTA_EXCEEDED","scanResult":"ERROR"} */
                fileHasReachedServersideQuota = true;
                if (link.isNameSet()) {
                    return AvailableStatus.TRUE;
                } else {
                    logger.info("Continue to try to find filename");
                }
            } else {
                this.dllink = (String) entries.get("downloadUrl");
                return AvailableStatus.TRUE;
            }
        }
        {
            /*
             * 2020-09-14: Check for possible direct download first. This will also get around Googles "IP/ISP captcha-blocks" (see code
             * below).
             */
            URLConnectionAdapter con = null;
            try {
                con = br.openGetConnection(constructDownloadUrl(link));
                if (con.getResponseCode() == 403) {
                    /*
                     * 2020-09-14: E.g. "Sorry[...] but your computer or network may be sending automated queries"[2020-09-14: Retry with
                     * active Google account can 'fix' this.] or rights-issue ...
                     */
                    specialError403 = true;
                    /*
                     * Do not throw exception here! Continue so fallback handling below can run through and e.g. at least find the filename!
                     */
                    // throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 403");
                    br.followConnection();
                } else if (con.getResponseCode() == 404) {
                    /* 2020-09-14: File should be offline */
                    throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                } else if (con.isContentDisposition()) {
                    logger.info("Direct download active");
                    String fileName = getFileNameFromHeader(con);
                    if (!StringUtils.isEmpty(fileName)) {
                        link.setFinalFileName(fileName);
                    }
                    if (con.getCompleteContentLength() > 0) {
                        link.setVerifiedFileSize(con.getCompleteContentLength());
                    }
                    dllink = con.getURL().toString();
                    return AvailableStatus.TRUE;
                } else {
                    br.followConnection();
                    filename = br.getRegex("class=\"uc-name-size\"><a href=\"[^\"]+\">([^<>\"]+)<").getMatch(0);
                    if (filename != null) {
                        link.setName(Encoding.htmlDecode(filename).trim());
                    }
                    filesizeStr = br.getRegex("\\((\\d+(?:[,\\.]\\d)?\\s*[KMGT])\\)</span>").getMatch(0);
                    if (filesizeStr != null) {
                        link.setDownloadSize(SizeFormatter.getSize(filesizeStr + "B"));
                    }
                }
            } finally {
                if (con != null) {
                    con.disconnect();
                }
            }
            if (br.containsHTML("error\\-subcaption\">Too many users have viewed or downloaded this file recently\\. Please try accessing the file again later\\.|<title>Google Drive – (Quota|Cuota|Kuota|La quota|Quote)")) {
                /*
                 * 2019-01-18: Its not possible to download at this time - sometimes it is possible to download such files when logged in
                 * but not necessarily!
                 */
                logger.info("Official download is impossible because quota has been reached");
                fileHasReachedServersideQuota = true;
                if (isDownload) {
                    downloadTempUnavailableAndOrOnlyViaAccount(account, false);
                } else {
                    /* Continue so other handling can find filename and/or filesize! */
                }
            } else if (br.containsHTML("class=\"uc\\-error\\-caption\"")) {
                /*
                 * 2017-02-06: This could also be another error but we catch it by the classname to make this more language independant!
                 */
                /*
                 * 2019-01-18: Its not possible to download at this time - sometimes it is possible to download such files when logged in
                 * but not necessarily!
                 */
                logger.info("Official download is impossible because quota has been reached2");
                fileHasReachedServersideQuota = true;
                if (isDownload) {
                    downloadTempUnavailableAndOrOnlyViaAccount(account, false);
                } else {
                    /* Continue so other handling can find filename and/or filesize! */
                }
            } else {
                /* E.g. "This file is too big for Google to virus-scan it - download anyway?" */
                dllink = br.getRegex("\"([^\"]*?/uc\\?export=download[^<>\"]+)\"").getMatch(0);
                if (dllink != null) {
                    dllink = HTMLEntities.unhtmlentities(dllink);
                    logger.info("Direct download active");
                    return AvailableStatus.TRUE;
                } else {
                    logger.info("Direct download inactive --> Download Overview");
                }
            }
        }
        /* In case we were not able to find a download-URL until now, we'll have to try the more complicated way ... */
        logger.info("Trying to find file information via 'download overview' page");
        if (isDownload) {
            synchronized (CAPTCHA_LOCK) {
                br.getPage("https://drive.google.com/file/d/" + getFID(link) + "/view");
                this.handleErrors(this.br, link, account);
            }
        } else {
            br.getPage("https://drive.google.com/file/d/" + getFID(link) + "/view");
        }
        /* 2020-11-29: If anyone knows why we're doing this, please add comment! */
        // String jsredirect = br.getRegex("var url = \\'(http[^<>\"]*?)\\'").getMatch(0);
        // if (jsredirect != null) {
        // final String url_gdrive = "https://drive.google.com/file/d/" + getFID(link) + "/view?ddrp=1";
        // br.getPage(url_gdrive);
        // }
        isStreamable = br.containsHTML("video\\.google\\.com/get_player\\?docid=" + Encoding.urlEncode(this.getFID(link)));
        if (br.containsHTML("<p class=\"error\\-caption\">Sorry, we are unable to retrieve this document\\.</p>") || br.getHttpConnection().getResponseCode() == 403 || br.getHttpConnection().getResponseCode() == 404) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        } else if (br.getURL().contains("accounts.google.com/")) {
            link.getLinkStatus().setStatusText("You are missing the rights to download this file");
            privatefile = true;
            return AvailableStatus.TRUE;
        } else if (this.requiresSpecialCaptcha(br)) {
            logger.info("Don't handle captcha in availablecheck");
            return AvailableStatus.UNCHECKABLE;
        }
        /* Only look for/set filename/filesize if it hasn't been done before! */
        if (filename == null) {
            filename = br.getRegex("'title'\\s*:\\s*'([^<>\"]*?)'").getMatch(0);
            if (filename == null) {
                filename = br.getRegex("\"filename\"\\s*:\\s*\"([^\"]+)\",").getMatch(0);
            }
            if (filename == null) {
                filename = br.getRegex("<title>([^\"]+) - Google Drive</title>").getMatch(0);
            }
            if (filename == null) {
                /*
                 * Chances are high that we have a non-officially-downloadable-document (pdf). PDF is displayed in browser via images (1
                 * image per page) - we would need a decrypter for this.
                 */
                /* 2020-09-14: Handling for this edge case has been removed. Provide example URLs if it happens again! */
                filename = br.getRegex("<meta property=\"og:title\" content=\"([^<>\"]+)\">").getMatch(0);
            }
            if (filename == null && !link.isNameSet()) {
                /* Fallback */
                link.setName(this.getFID(link));
            } else if (filename != null) {
                filename = Encoding.unicodeDecode(filename.trim());
                link.setName(filename);
            }
        }
        if (filesizeStr == null) {
            filesizeStr = br.getRegex("\"sizeInBytes\"\\s*:\\s*(\\d+),").getMatch(0);
            if (filesizeStr == null) {
                // value is within html or a subquent ajax request to fetch json..
                // devnote: to fix, look for the json request to https://clients\d+\.google\.com/drive/v2internal/files/ + fuid and find the
                // filesize, then search for the number within the base page. It's normally there. just not referenced as such.
                filesizeStr = br.getRegex("\\[null,\"" + (filename != null ? Pattern.quote(filename) : "[^\"]") + "\"[^\r\n]+\\[null,\\d+,\"(\\d+)\"\\]").getMatch(0);
            }
            if (filesizeStr != null) {
                link.setVerifiedFileSize(Long.parseLong(filesizeStr));
                link.setDownloadSize(SizeFormatter.getSize(filesizeStr));
            }
        }
        return AvailableStatus.TRUE;
    }

    /**
     * @return: true: Allow stream download attempt </br>
     *          false: Do not allow stream download -> Download original version of file
     */
    private boolean attemptStreamDownload(final DownloadLink link) {
        final PreferredQuality qual = PluginJsonConfig.get(GoogleConfig.class).getPreferredQuality();
        final boolean userHasDownloadedStreamBefore = link.hasProperty(PROPERTY_USED_QUALITY);
        final boolean userWantsStreamDownload = qual != PreferredQuality.ORIGINAL;
        final boolean streamShouldBeAvailable = streamShouldBeAvailable(link);
        if (userHasDownloadedStreamBefore) {
            return true;
        } else if (userWantsStreamDownload && streamShouldBeAvailable) {
            return true;
        } else {
            return false;
        }
    }

    private boolean streamShouldBeAvailable(final DownloadLink link) {
        String filename = link.getFinalFileName();
        if (filename == null) {
            filename = link.getName();
        }
        return this.isStreamable || isVideoFile(filename);
    }

    /** Returns user preferred stream quality if user prefers stream download else returns null. */
    private String handleStreamQualitySelection(final DownloadLink link, final Account account) throws PluginException, IOException, InterruptedException {
        final PreferredQuality qual = PluginJsonConfig.get(GoogleConfig.class).getPreferredQuality();
        final int preferredQualityHeight;
        final boolean userHasDownloadedStreamBefore = link.hasProperty(PROPERTY_USED_QUALITY);
        if (userHasDownloadedStreamBefore) {
            preferredQualityHeight = (int) link.getLongProperty(PROPERTY_USED_QUALITY, 0);
            logger.info("Using last used quality: " + preferredQualityHeight);
        } else {
            preferredQualityHeight = getPreferredQualityHeight(qual);
            logger.info("Using currently selected quality: " + preferredQualityHeight);
        }
        String filename = link.getFinalFileName();
        if (filename == null) {
            filename = link.getName();
        }
        final boolean streamShouldBeAvailable = streamShouldBeAvailable(link);
        if (preferredQualityHeight <= -1 || !streamShouldBeAvailable) {
            logger.info("Downloading original file");
            return null;
        }
        logger.info("Looking for stream download");
        synchronized (CAPTCHA_LOCK) {
            if (account != null) {
                /* Uses a slightly different request than when not logged in but answer is the same. */
                br.getPage("https://drive.google.com/u/0/get_video_info?docid=" + this.getFID(link));
            } else {
                br.getPage("https://drive.google.com/get_video_info?docid=" + this.getFID(link));
            }
            this.handleErrors(this.br, link, account);
        }
        final UrlQuery query = UrlQuery.parse(br.toString());
        /* Attempt final fallback/edge-case: Check for download of "un-downloadable" streams. */
        final String errorcodeStr = query.get("errorcode");
        final String errorReason = query.get("reason");
        if (errorcodeStr != null && errorcodeStr.matches("\\d+")) {
            final int errorCode = Integer.parseInt(errorcodeStr);
            if (errorCode == 100) {
                /* This should never happen but if it does, we know for sure that the file is offline! */
                /* 2020-11-29: E.g. &errorcode=100&reason=Dieses+Video+ist+nicht+vorhanden.& */
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            } else if (errorCode == 150) {
                /* Same as in file-download mode: File is definitely not downloadable at this moment! */
                /* TODO: Add recognition for non-available stream downloads --> To at least have this case logged! */
                // if (isDownload) {
                // downloadTempUnavailableAndOrOnlyViaAccount(account);
                // } else {
                // return AvailableStatus.TRUE;
                // }
                downloadTempUnavailableAndOrOnlyViaAccount(account, true);
            } else {
                logger.info("Streaming download impossible because: " + errorcodeStr + " | " + errorReason);
                return null;
            }
        }
        /* Usually same as the title we already have but always with .mp4 ending(?) */
        // final String streamFilename = query.get("title");
        // final String fmt_stream_map = query.get("fmt_stream_map");
        String url_encoded_fmt_stream_map = query.get("url_encoded_fmt_stream_map");
        url_encoded_fmt_stream_map = Encoding.urlDecode(url_encoded_fmt_stream_map, false);
        if (url_encoded_fmt_stream_map == null) {
            logger.info("Stream download impossible for unknown reasons");
            return null;
        }
        /* TODO: Collect qualities, then do quality selection */
        final YoutubeHelper dummy = new YoutubeHelper(this.br, this.getLogger());
        final List<YoutubeStreamData> qualities = new ArrayList<YoutubeStreamData>();
        final String[] qualityInfos = url_encoded_fmt_stream_map.split(",");
        for (final String qualityInfo : qualityInfos) {
            final UrlQuery qualityQuery = UrlQuery.parse(qualityInfo);
            final YoutubeStreamData yts = dummy.convert(qualityQuery, this.br.getURL());
            qualities.add(yts);
        }
        if (qualities.isEmpty()) {
            logger.warning("Failed to find any stream qualities");
            return null;
        }
        logger.info("Found " + qualities.size() + " qualities");
        String bestQualityDownloadlink = null;
        int bestQualityHeight = 0;
        String selectedQualityDownloadlink = null;
        for (final YoutubeStreamData quality : qualities) {
            if (quality.getItag().getVideoResolution().getHeight() == preferredQualityHeight) {
                logger.info("Found user preferred quality: " + preferredQualityHeight + "p");
                selectedQualityDownloadlink = quality.getUrl();
                break;
            } else if (quality.getItag().getVideoResolution().getHeight() > bestQualityHeight) {
                bestQualityHeight = quality.getItag().getVideoResolution().getHeight();
                bestQualityDownloadlink = quality.getUrl();
            }
        }
        final int usedQuality;
        if (selectedQualityDownloadlink == null && bestQualityDownloadlink != null) {
            logger.info("Using best stream quality: " + bestQualityHeight + "p");
            selectedQualityDownloadlink = bestQualityDownloadlink;
            usedQuality = bestQualityHeight;
        } else if (selectedQualityDownloadlink != null) {
            usedQuality = preferredQualityHeight;
        } else {
            /* This should never happen! */
            logger.warning("Failed to find any quality");
            return null;
        }
        if (filename != null) {
            if (DebugMode.TRUE_IN_IDE_ELSE_FALSE) {
                /* Put quality in filename */
                link.setFinalFileName(correctOrApplyFileNameExtension(filename, "_" + usedQuality + "p.mp4"));
            } else {
                link.setFinalFileName(correctOrApplyFileNameExtension(filename, ".mp4"));
            }
        }
        /* TODO: Leave this one in after public release and remove this comment! */
        if (DebugMode.TRUE_IN_IDE_ELSE_FALSE) {
            if (userHasDownloadedStreamBefore) {
                link.setComment("Using FORCED preferred quality: " + preferredQualityHeight + "p | Used quality: " + usedQuality + "p");
            } else {
                link.setComment("Using preferred quality: " + preferredQualityHeight + "p | Used quality: " + usedQuality + "p");
            }
        }
        /* Reset this because md5hash could possibly have been set during availablecheck before! */
        link.setMD5Hash(null);
        if (!userHasDownloadedStreamBefore) {
            /* User could have started download of original file before: Clear progress! */
            link.setChunksProgress(null);
            link.setVerifiedFileSize(-1);
            /* Save the quality we've decided to download in case user stops and resumes download later. */
            link.setProperty(PROPERTY_USED_QUALITY, usedQuality);
        }
        return selectedQualityDownloadlink;
    }

    /**
     * Returns result according to file-extensions listed here:
     * https://support.google.com/drive/answer/2423694/?co=GENIE.Platform%3DiOS&hl=de </br>
     * Last updated: 2020-11-29
     */
    private static boolean isVideoFile(final String filename) {
        /*
         * 2020-11-30: .ogg is also supported but audio streams seem to be the original files --> Do not allow streaming download for .ogg
         * files.
         */
        if (filename == null) {
            return false;
        } else if (new Regex(filename, Pattern.compile(".*\\.(webm|3gp|mov|wmv|mp4|mpeg|mkv|avi|flv|mts|m2ts)$", Pattern.CASE_INSENSITIVE)).matches()) {
            return true;
        } else {
            return false;
        }
    }

    private int getPreferredQualityHeight(final PreferredQuality quality) {
        switch (quality) {
        case STREAM_BEST:
            return 0;
        case STREAM_360P:
            return 360;
        case STREAM_480P:
            return 480;
        case STREAM_720P:
            return 720;
        case STREAM_1080P:
            return 1080;
        default:
            /* Original quality (no stream download) */
            return -1;
        }
    }

    private String constructDownloadUrl(final DownloadLink link) throws PluginException {
        final String fid = getFID(link);
        if (fid == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        /**
         * E.g. older alternative URL for documents: https://docs.google.com/document/export?format=pdf&id=<fid>&includes_info_params=true
         * </br>
         * Last rev. with this handling: 42866
         */
        return "https://docs.google.com/uc?id=" + getFID(link) + "&export=download";
    }

    @Override
    public void handleFree(final DownloadLink link) throws Exception {
        handleDownload(link, null);
    }

    private void handleDownload(final DownloadLink link, final Account account) throws Exception {
        boolean resume = true;
        int maxChunks = 0;
        if (link.getBooleanProperty(GoogleDrive.NOCHUNKS, false) || !resume) {
            maxChunks = 1;
        }
        String streamDownloadlink = null;
        if (useAPI()) {
            /* Additionally check via API if allowed */
            this.requestFileInformationAPI(link, true);
            streamDownloadlink = this.handleStreamQualitySelection(link, account);
            if (streamDownloadlink != null) {
                /* Yeah it's silly but we keep using this variable as it is required for website mode download. */
                this.dllink = streamDownloadlink;
                dl = new jd.plugins.BrowserAdapter().openDownload(br, link, this.dllink, resume, maxChunks);
            } else {
                final UrlQuery queryFile = new UrlQuery();
                queryFile.appendEncoded("fileId", this.getFID(link));
                queryFile.add("supportsAllDrives", "true");
                // queryFile.appendEncoded("fields", getFieldsAPI());
                queryFile.appendEncoded("key", getAPIKey());
                queryFile.appendEncoded("alt", "media");
                /* Yeah it's silly but we keep using this variable as it is required for website mode download. */
                this.dllink = jd.plugins.hoster.GoogleDrive.API_BASE + "/files/" + this.getFID(link) + "?" + queryFile.toString();
                dl = new jd.plugins.BrowserAdapter().openDownload(br, link, this.dllink, resume, maxChunks);
            }
        } else {
            requestFileInformationWebsite(link, account, true);
            if (privatefile) {
                throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_ONLY);
            } else if (fileHasReachedServersideQuota) {
                downloadTempUnavailableAndOrOnlyViaAccount(account, false);
            } else if (StringUtils.isEmpty(this.dllink)) {
                /* Last chance errorhandling */
                if (specialError403) {
                    if (account != null) {
                        throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 403");
                    } else {
                        throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 403: Add Google account or try again later");
                    }
                } else {
                    this.handleErrors(this.br, link, account);
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
            }
            /*
             * TODO: Files can be blocked for downloading but streaming may still be possible(rare case). Usually if downloads are blocked
             * because of "too high traffic", streaming is blocked too!
             */
            /**
             * 2020-11-29: Do NOT try to move this into availablecheck! Availablecheck can get around Google's "sorry" captcha for
             * downloading original files but this does not work for streaming! If a captcha is required and the user wants to download a
             * stream there is no way around it! The user has to solve it!
             */
            streamDownloadlink = this.handleStreamQualitySelection(link, account);
            if (streamDownloadlink != null) {
                this.dllink = streamDownloadlink;
            }
            dl = new jd.plugins.BrowserAdapter().openDownload(br, link, dllink, resume, maxChunks);
        }
        /* 2020-03-18: Streams do not have content-disposition but often 206 partial content. */
        // if ((!dl.getConnection().isContentDisposition() && dl.getConnection().getResponseCode() != 206) ||
        // (dl.getConnection().getResponseCode() != 200 && dl.getConnection().getResponseCode() != 206)) {
        if (!this.looksLikeDownloadableContent(dl.getConnection())) {
            if (dl.getConnection().getResponseCode() == 403) {
                /* Most likely quota error or "Missing permissions" error. */
                downloadTempUnavailableAndOrOnlyViaAccount(account, false);
            } else if (dl.getConnection().getResponseCode() == 416) {
                dl.getConnection().disconnect();
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 416", 5 * 60 * 1000l);
            }
            try {
                dl.getConnection().setAllowedResponseCodes(new int[] { dl.getConnection().getResponseCode() });
                br.followConnection();
            } catch (IOException e) {
                logger.log(e);
            }
            if (br.containsHTML("error\\-subcaption\">Too many users have viewed or downloaded this file recently\\. Please try accessing the file again later\\.|<title>Google Drive – (Quota|Cuota|Kuota|La quota|Quote)")) {
                // so its not possible to download at this time.
                downloadTempUnavailableAndOrOnlyViaAccount(account, false);
            } else if (br.containsHTML("class=\"uc\\-error\\-caption\"")) {
                /*
                 * 2017-02-06: This could also be another error but we catch it by the classname to make this more language independant!
                 */
                downloadTempUnavailableAndOrOnlyViaAccount(account, false);
            } else {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Unknown server error");
            }
        }
        try {
            if (link.getFinalFileName() == null) {
                String fileName = getFileNameFromHeader(dl.getConnection());
                if (fileName != null) {
                    link.setFinalFileName(fileName);
                }
            }
            if (!this.dl.startDownload()) {
                try {
                    if (dl.externalDownloadStop()) {
                        return;
                    }
                } catch (final Throwable e) {
                    getLogger().log(e);
                }
                /* unknown error, we disable multiple chunks */
                if (link.getBooleanProperty(GoogleDrive.NOCHUNKS, false) == false) {
                    link.setProperty(GoogleDrive.NOCHUNKS, Boolean.valueOf(true));
                    throw new PluginException(LinkStatus.ERROR_RETRY);
                }
            }
        } catch (final PluginException e) {
            // New V2 errorhandling
            /* unknown error, we disable multiple chunks */
            if (e.getLinkStatus() != LinkStatus.ERROR_RETRY && link.getBooleanProperty(GoogleDrive.NOCHUNKS, false) == false) {
                link.setProperty(GoogleDrive.NOCHUNKS, Boolean.valueOf(true));
                throw new PluginException(LinkStatus.ERROR_RETRY);
            }
            throw e;
        }
    }

    /**
     * Checks for errors that can happen at "any time". Preferably call this inside synchronized block especially if an account is available
     * in an attempt to avoid having to solve multiple captchas!
     */
    private void handleErrors(final Browser br, final DownloadLink link, final Account account) throws PluginException, InterruptedException, IOException {
        if (requiresSpecialCaptcha(br)) {
            handleSpecialCaptcha(link, account);
        } else if (br.getHttpConnection().getResponseCode() == 429) {
            throw new PluginException(LinkStatus.ERROR_HOSTER_TEMPORARILY_UNAVAILABLE, "429 too many requests");
        }
    }

    private void handleErrorsAPI(final Browser br, final DownloadLink link, final Account account) {
        /* TODO: Add functionality */
    }

    private boolean requiresSpecialCaptcha(final Browser br) {
        return br.getHttpConnection().getResponseCode() == 429 && br.getURL().contains("/sorry/index");
    }

    private void handleSpecialCaptcha(final DownloadLink link, final Account account) throws PluginException, IOException, InterruptedException {
        if (link == null) {
            /* 2020-11-29: This captcha should never happen during account-check! It should only happen when requesting files. */
            throw new AccountUnavailableException("Captcha blocked", 5 * 60 * 1000l);
        } else {
            /*
             * 2020-09-09: Google is sometimes blocking users/whole ISP IP subnets so they need to go through this step in order to e.g.
             * continue downloading.
             */
            logger.info("Google 'ISP/IP block captcha' detected");
            /*
             * 2020-09-14: TODO: This handling doesn't work so we'll at least display a meaningful errormessage. The captcha should never
             * occur anyways as upper handling will try to avoid it!
             */
            final boolean canSolveCaptcha = false;
            if (!canSolveCaptcha) {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Google blocked your IP - captcha required but not implemented yet");
            }
            final Form captchaForm = br.getForm(0);
            if (captchaForm == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            final String recaptchaV2Response = new CaptchaHelperHostPluginRecaptchaV2(this, br).getToken();
            captchaForm.put("g-recaptcha-response", Encoding.urlEncode(recaptchaV2Response));
            /* This should now redirect back to where we initially wanted to got to! */
            // br.getHeaders().put("X-Client-Data", "0");
            br.submitForm(captchaForm);
            /* Double-check to make sure access was granted */
            if (br.getHttpConnection().getResponseCode() == 429) {
                logger.info("Captcha failed");
                /*
                 * Do not invalidate captcha result because most likely that was correct but our plugin somehow failed -> Try again later
                 */
                throw new PluginException(LinkStatus.ERROR_HOSTER_TEMPORARILY_UNAVAILABLE, "429 too many requests: Captcha failed");
            } else {
                logger.info("Captcha success");
                if (account != null) {
                    /*
                     * Cookies have changed! Store new cookies so captcha won't happen again immediately. This is stored on the current
                     * session and not just IP!
                     */
                    account.saveCookies(br.getCookies(br.getHost()), "");
                } else {
                    /* TODO: Save- and restore session cookies - this captcha only has to be solved once per session per X time! */
                }
            }
        }
    }

    /**
     * Use this for response 403 or messages like 'file can not be downloaded at this moment'. Such files will usually be downloadable via
     * account.
     */
    private void downloadTempUnavailableAndOrOnlyViaAccount(final Account account, final boolean isStreamDownload) throws PluginException {
        final String errorMaxWithAccount;
        final String errorMsgWithoutAccount;
        if (isStreamDownload) {
            errorMaxWithAccount = "Stream-Download impossible - wait and retry later, import the file into your account and dl it from there, disable stream-download or try again with a different account";
            errorMsgWithoutAccount = "Stream-Download impossible - add Google account and retry, wait and retry later or disable stream-download";
        } else {
            errorMaxWithAccount = "Download impossible - wait and retry later, import the file into your account and dl it from there or try again with a different account";
            errorMsgWithoutAccount = "Download impossible - add Google account and retry or wait and retry later";
        }
        if (account != null) {
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, errorMaxWithAccount, 2 * 60 * 60 * 1000);
        } else {
            /* 2020-03-10: No warranties that a download will work via account but most times it will! */
            /*
             * 2020-08-10: Updated Exception - rather wait and try again later because such file may be downloadable without account again
             * after some time!
             */
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, errorMsgWithoutAccount, 2 * 60 * 60 * 1000);
            // throw new AccountRequiredException();
        }
    }

    private boolean login(final Browser br, final Account account, final boolean forceLoginValidation) throws Exception {
        final GoogleHelper helper = new GoogleHelper(br);
        helper.setLogger(this.getLogger());
        return helper.login(account, forceLoginValidation);
    }

    @Override
    public AccountInfo fetchAccountInfo(final Account account) throws Exception {
        final AccountInfo ai = new AccountInfo();
        if (!login(br, account, true)) {
            throw new AccountUnavailableException("Login failed", 2 * 60 * 60 * 1000l);
        }
        ai.setUnlimitedTraffic();
        account.setType(AccountType.FREE);
        /* Free accounts cannot have captchas */
        account.setConcurrentUsePossible(true);
        account.setMaxSimultanDownloads(20);
        return ai;
    }

    @Override
    public void handlePremium(final DownloadLink link, final Account account) throws Exception {
        handleDownload(link, account);
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
        if (link != null) {
            link.setProperty("ServerComaptibleForByteRangeRequest", true);
            link.removeProperty(GoogleDrive.NOCHUNKS);
            link.removeProperty(GoogleDrive.PROPERTY_USED_QUALITY);
        }
    }

    @Override
    public Class<? extends PluginConfigInterface> getConfigInterface() {
        return GoogleConfig.class;
    }
}