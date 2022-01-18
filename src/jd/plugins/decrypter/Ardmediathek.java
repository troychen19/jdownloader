//    jDownloader - Downloadmanager
//    Copyright (C) 2008  JD-Team support@jdownloader.org
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
import java.net.MalformedURLException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;

import org.appwork.storage.JSonStorage;
import org.appwork.storage.TypeRef;
import org.appwork.utils.Hash;
import org.appwork.utils.StringUtils;
import org.appwork.utils.UniqueAlltimeID;
import org.appwork.utils.formatter.TimeFormatter;
import org.jdownloader.plugins.components.config.ArdConfigInterface;
import org.jdownloader.plugins.components.config.DasersteConfig;
import org.jdownloader.plugins.components.config.EurovisionConfig;
import org.jdownloader.plugins.components.config.KikaDeConfig;
import org.jdownloader.plugins.components.config.MdrDeConfig;
import org.jdownloader.plugins.components.config.MediathekDasersteConfig;
import org.jdownloader.plugins.components.config.MediathekProperties;
import org.jdownloader.plugins.components.config.SandmannDeConfig;
import org.jdownloader.plugins.components.config.SportschauConfig;
import org.jdownloader.plugins.components.config.SputnikDeConfig;
import org.jdownloader.plugins.components.config.TagesschauDeConfig;
import org.jdownloader.plugins.components.config.WDRConfig;
import org.jdownloader.plugins.components.config.WDRMausConfig;
import org.jdownloader.plugins.components.hls.HlsContainer;
import org.jdownloader.plugins.config.PluginJsonConfig;
import org.jdownloader.scripting.JavaScriptEngineFactory;

import jd.PluginWrapper;
import jd.controlling.ProgressController;
import jd.http.Browser;
import jd.http.URLConnectionAdapter;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DecrypterRetryException;
import jd.plugins.DecrypterRetryException.RetryReason;
import jd.plugins.DownloadLink;
import jd.plugins.FilePackage;
import jd.plugins.LinkStatus;
import jd.plugins.Plugin;
import jd.plugins.PluginException;
import jd.plugins.PluginForDecrypt;
import jd.plugins.components.MediathekHelper;
import jd.plugins.components.PluginJSonUtils;

@DecrypterPlugin(revision = "$Revision$", interfaceVersion = 3, names = { "ardmediathek.de", "mediathek.daserste.de", "daserste.de", "sandmann.de", "wdr.de", "sportschau.de", "wdrmaus.de", "kika.de", "eurovision.de", "sputnik.de", "mdr.de", "ndr.de", "tagesschau.de" }, urls = { "https?://(?:[A-Z0-9]+\\.)?ardmediathek\\.de/.+", "https?://(?:www\\.)?mediathek\\.daserste\\.de/.*?documentId=\\d+[^/]*?", "https?://www\\.daserste\\.de/.*?\\.html", "https?://(?:www\\.)?sandmann\\.de/.+", "https?://(?:[a-z0-9]+\\.)?wdr\\.de/[^<>\"]+\\.html|https?://deviceids-[a-z0-9\\-]+\\.wdr\\.de/ondemand/\\d+/\\d+\\.js", "https?://(?:\\w+\\.)?sportschau\\.de/.*?\\.html", "https?://(?:www\\.)?wdrmaus\\.de/.+", "https?://(?:www\\.)?kika\\.de/[^<>\"]+\\.html", "https?://(?:www\\.)?eurovision\\.de/[^<>\"]+\\.html", "https?://(?:www\\.)?sputnik\\.de/[^<>\"]+\\.html",
        "https?://(?:www\\.)?mdr\\.de/[^<>\"]+\\.html", "https?://(?:www\\.)?ndr\\.de/[^<>\"]+\\.html", "https?://(?:www\\.)?tagesschau\\.de/[^<>\"]+\\.html" })
public class Ardmediathek extends PluginForDecrypt {
    /* Constants */
    private static final String                 type_embedded                              = "https?://deviceids-[a-z0-9\\-]+\\.wdr\\.de/ondemand/\\d+/\\d+\\.js";
    /* Variables */
    private final HashMap<String, DownloadLink> foundQualitiesMap                          = new HashMap<String, DownloadLink>();
    private final HashMap<String, DownloadLink> foundQualitiesMap_http_urls_via_HLS_master = new HashMap<String, DownloadLink>();
    ArrayList<DownloadLink>                     decryptedLinks                             = new ArrayList<DownloadLink>();
    /* Important: Keep this updated & keep this in order: Highest --> Lowest */
    private final List<String>                  all_known_qualities                        = Arrays.asList("http_6666000_1080", "hls_6666000_1080", "http_3773000_720", "hls_3773000_720", "http_1989000_540", "hls_1989000_540", "http_1213000_360", "hls_1213000_360", "http_605000_280", "hls_605000_280", "http_448000_270", "hls_448000_270", "http_317000_270", "hls_317000_270", "http_189000_180", "hls_189000_180", "http_0_0");
    private final Map<String, Long>             heigth_to_bitrate                          = new HashMap<String, Long>();
    {
        heigth_to_bitrate.put("180", 189000l);
        /* keep in mind that sometimes there are two versions for 270! This is the higher one (default)! */
        heigth_to_bitrate.put("270", 448000l);
        heigth_to_bitrate.put("280", 605000l);
        heigth_to_bitrate.put("360", 1213000l);
        heigth_to_bitrate.put("540", 1989000l);
        heigth_to_bitrate.put("576", 1728000l);
        heigth_to_bitrate.put("720", 3773000l);
        heigth_to_bitrate.put("1080", 6666000l);
    }
    private String             subtitleLink = null;
    private boolean            grabHLS      = false;
    private String             contentID    = null;
    private ArdConfigInterface cfg          = null;

    public Ardmediathek(final PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public Class<? extends ArdConfigInterface> getConfigInterface() {
        if ("ardmediathek.de".equalsIgnoreCase(getHost())) {
            return ArdConfigInterface.class;
        } else if ("daserste.de".equalsIgnoreCase(getHost())) {
            return DasersteConfig.class;
        } else if ("mediathek.daserste.de".equalsIgnoreCase(getHost())) {
            return MediathekDasersteConfig.class;
        } else if ("wdrmaus.de".equalsIgnoreCase(getHost())) {
            return WDRMausConfig.class;
        } else if ("wdr.de".equalsIgnoreCase(getHost())) {
            return WDRConfig.class;
        } else if ("sportschau.de".equalsIgnoreCase(getHost())) {
            return SportschauConfig.class;
        } else if ("kika.de".equalsIgnoreCase(getHost())) {
            return KikaDeConfig.class;
        } else if ("eurovision.de".equalsIgnoreCase(getHost())) {
            return EurovisionConfig.class;
        } else if ("sputnik.de".equalsIgnoreCase(getHost())) {
            return SputnikDeConfig.class;
        } else if ("sandmann.de".equalsIgnoreCase(getHost())) {
            return SandmannDeConfig.class;
        } else if ("mdr.de".equalsIgnoreCase(getHost())) {
            return MdrDeConfig.class;
        } else if ("tagesschau.de".equalsIgnoreCase(getHost())) {
            return TagesschauDeConfig.class;
        } else {
            return ArdConfigInterface.class;
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public ArrayList<DownloadLink> decryptIt(final CryptedLink param, final ProgressController progress) throws Exception {
        br.setFollowRedirects(true);
        cfg = PluginJsonConfig.get(getConfigInterface());
        final List<String> selectedQualities = new ArrayList<String>();
        /*
         * 2018-03-06: TODO: Maybe add option to download hls audio as hls master playlist will often contain a mp4 stream without video (==
         * audio only).
         */
        if (cfg.isGrabHLS180pVideoEnabled()) {
            selectedQualities.add("hls_" + heigth_to_bitrate.get("180") + "_180");
        }
        if (cfg.isGrabHLS270pLowerVideoEnabled()) {
            selectedQualities.add("hls_317000_270");
        }
        if (cfg.isGrabHLS270pVideoEnabled()) {
            selectedQualities.add("hls_" + heigth_to_bitrate.get("270") + "_270");
        }
        if (cfg.isGrabHLS280pVideoEnabled()) {
            selectedQualities.add("hls_" + heigth_to_bitrate.get("280") + "_280");
        }
        if (cfg.isGrabHLS360pVideoEnabled()) {
            selectedQualities.add("hls_" + heigth_to_bitrate.get("360") + "_360");
        }
        if (cfg.isGrabHLS540pVideoEnabled()) {
            selectedQualities.add("hls_" + heigth_to_bitrate.get("540") + "_540");
        }
        if (cfg.isGrabHLS576pVideoEnabled()) {
            selectedQualities.add("hls_" + heigth_to_bitrate.get("576") + "_576");
        }
        if (cfg.isGrabHLS720pVideoEnabled()) {
            selectedQualities.add("hls_" + heigth_to_bitrate.get("720") + "_720");
        }
        if (cfg.isGrabHLS1080pVideoEnabled()) {
            selectedQualities.add("hls_" + heigth_to_bitrate.get("1080") + "_1080");
        }
        grabHLS = selectedQualities.size() > 0;
        if (cfg.isGrabHTTP180pVideoEnabled()) {
            selectedQualities.add("http_" + heigth_to_bitrate.get("180") + "_180");
        }
        if (cfg.isGrabHTTP270pLowerVideoEnabled()) {
            selectedQualities.add("http_317000_270");
        }
        if (cfg.isGrabHTTP270pVideoEnabled()) {
            selectedQualities.add("http_" + heigth_to_bitrate.get("270") + "_270");
        }
        if (cfg.isGrabHTTP280pVideoEnabled()) {
            selectedQualities.add("http_" + heigth_to_bitrate.get("280") + "_280");
        }
        if (cfg.isGrabHTTP360pVideoEnabled()) {
            selectedQualities.add("http_" + heigth_to_bitrate.get("360") + "_360");
        }
        if (cfg.isGrabHTTP540pVideoEnabled()) {
            selectedQualities.add("http_" + heigth_to_bitrate.get("540") + "_540");
        }
        if (cfg.isGrabHTTP576pVideoEnabled()) {
            selectedQualities.add("http_" + heigth_to_bitrate.get("576") + "_576");
        }
        if (cfg.isGrabHTTP720pVideoEnabled()) {
            selectedQualities.add("http_" + heigth_to_bitrate.get("720") + "_720");
        }
        if (cfg.isGrabHTTP1080pVideoEnabled()) {
            selectedQualities.add("http_" + heigth_to_bitrate.get("1080") + "_1080");
        }
        /*
         * 2018-02-22: Important: So far there is only one OLD website, not compatible with the "decryptMediathek" function! Keep this in
         * mind when changing things!
         */
        final String host = this.getHost();
        if (host.equalsIgnoreCase("daserste.de") || host.equalsIgnoreCase("kika.de") || host.equalsIgnoreCase("sputnik.de") || host.equalsIgnoreCase("mdr.de")) {
            crawlDasersteVideo(param);
        } else if (host.equalsIgnoreCase("tagesschau.de")) {
            crawlTagesschauVideos(param);
        } else if (host.equalsIgnoreCase("ardmediathek.de")) {
            /* 2020-05-26: Separate handling required */
            this.decryptArdmediathekDeNew(param);
        } else {
            this.decryptMediathek(param);
        }
        handleUserQualitySelection(selectedQualities);
        if (decryptedLinks == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        if (decryptedLinks.size() == 0) {
            logger.info("Failed to find any links");
            decryptedLinks.add(this.createOfflinelink(param.getCryptedUrl()));
            return decryptedLinks;
        }
        return decryptedLinks;
    }

    private void errorGEOBlocked(final CryptedLink param) throws DecrypterRetryException {
        throw new DecrypterRetryException(RetryReason.GEO);
    }

    public static boolean isOffline(final Browser br) {
        if (br.getHttpConnection().getResponseCode() == 404) {
            return true;
        } else {
            return false;
        }
    }

    /** Find xml URL which leads to subtitle and video stream URLs. */
    private String getVideoXMLURL(final CryptedLink param) throws Exception {
        final String host = getHost();
        String url_xml = null;
        if (host.equalsIgnoreCase("daserste.de")) {
            /* The fast way - we do not even have to access the main URL which the user has added :) */
            url_xml = param.getCryptedUrl().replace(".html", "~playerXml.xml");
        } else if (param.getCryptedUrl().matches(".+mdr\\.de/.+/((?:video|audio)\\-\\d+)\\.html")) {
            /* Some special mdr.de URLs --> We do not have to access main URL so this way we can speed up the crawl process a bit :) */
            this.contentID = new Regex(param.getCryptedUrl(), "((?:audio|video)\\-\\d+)\\.html$").getMatch(0);
            url_xml = String.format("https://www.mdr.de/mediathek/mdr-videos/d/%s-avCustom.xml", this.contentID);
        } else {
            /* E.g. kika.de, sputnik.de, mdr.de */
            br.getPage(param.getCryptedUrl());
            if (isOffline(this.br)) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            url_xml = br.getRegex("\\'((?:https?://|(?:\\\\)?/)[^<>\"]+\\-avCustom\\.xml)\\'").getMatch(0);
            if (!StringUtils.isEmpty(url_xml)) {
                if (url_xml.contains("\\")) {
                    url_xml = url_xml.replace("\\", "");
                }
                this.contentID = new Regex(url_xml, "((?:audio|video)\\-\\d+)").getMatch(0);
            }
        }
        return url_xml;
    }

    /**
     * Find subtitle URL inside xml String
     */
    private String getXMLSubtitleURL(final Browser xmlBR) throws IOException {
        String subtitleURL = getXML(xmlBR.toString(), "videoSubtitleUrl");
        /* TODO: Check if we can safely remove the following lines of code */
        // if (StringUtils.isEmpty(subtitleURL)) {
        // /* E.g. checkeins.de */
        // subtitleURL = xmlBR.getRegex("<dataTimedTextNoOffset url=\"((?:https:)?[^<>\"]+\\.xml)\">").getMatch(0);
        // }
        if (subtitleURL != null) {
            return xmlBR.getURL(subtitleURL).toString();
        } else {
            return null;
        }
    }

    /**
     * Find subtitle URL inside json String
     *
     * @throws MalformedURLException
     */
    private String getJsonSubtitleURL(final Browser jsonBR) throws IOException {
        String subtitleURL;
        if (br.getURL().contains("wdr.de/")) {
            subtitleURL = PluginJSonUtils.getJsonValue(jsonBR, "captionURL");
            if (subtitleURL == null) {
                // TODO: check other formats
                subtitleURL = PluginJSonUtils.getJsonValue(jsonBR, "xml");
            }
        } else {
            subtitleURL = PluginJSonUtils.getJson(jsonBR, "_subtitleUrl");
        }
        if (subtitleURL != null) {
            return jsonBR.getURL(subtitleURL).toString();
        } else {
            return null;
        }
    }

    private String getHlsToHttpURLFormat(final String hlsMaster, final String exampleHTTPURL) {
        final Regex regex_hls = new Regex(hlsMaster, ".+/([^/]+/[^/]+/[^,/]+)(?:/|_|\\.),([A-Za-z0-9_,\\-]+),\\.mp4\\.csmil/?");
        String urlpart = regex_hls.getMatch(0);
        String urlpart2 = new Regex(hlsMaster, "//[^/]+/[^/]+/(.*?)(?:/|_),").getMatch(0);
        String http_url_format = null;
        /**
         * hls --> http urls (whenever possible) <br />
         */
        /* First case */
        if (hlsMaster.contains("sr_hls_od-vh") && urlpart != null) {
            http_url_format = "http://mediastorage01.sr-online.de/Video/" + urlpart + "_%s.mp4";
        }
        /* 2020-06-02: Do NOT yet try to make a generic RegEx for all types of HLS URLs!! */
        final String pattern_ard = ".*//hlsodswr-vh\\.akamaihd\\.net/i/(.*?),.*?\\.mp4\\.csmil/master\\.m3u8";
        final String pattern_hr = ".*//hrardmediathek-vh\\.akamaihd.net/i/(.*?),.+\\.mp4\\.csmil/master\\.m3u8$";
        /* Daserste */
        if (hlsMaster.contains("dasersteuni-vh.akamaihd.net")) {
            if (urlpart2 != null) {
                http_url_format = "https://pdvideosdaserste-a.akamaihd.net/" + urlpart2 + "/%s.mp4";
            }
        } else if (hlsMaster.contains("br-i.akamaihd.net")) {
            if (urlpart2 != null) {
                http_url_format = "http://cdn-storage.br.de/" + urlpart2 + "_%s.mp4";
            }
        } else if (hlsMaster.contains("wdradaptiv-vh.akamaihd.net") && urlpart2 != null) {
            /* wdr */
            http_url_format = "http://wdrmedien-a.akamaihd.net/" + urlpart2 + "/%s.mp4";
        } else if (hlsMaster.contains("rbbmediaadp-vh") && urlpart2 != null) {
            /* For all RBB websites e.g. also sandmann.de */
            http_url_format = "https://rbbmediapmdp-a.akamaihd.net/" + urlpart2 + "_%s.mp4";
        } else if (hlsMaster.contains("ndrod-vh.akamaihd.net") && urlpart != null) {
            /* 2018-03-07: There is '/progressive/' and '/progressive_geo/' --> We have to grab this from existing http urls */
            final String baseHttp;
            if (exampleHTTPURL != null) {
                baseHttp = new Regex(exampleHTTPURL, "(https?://[^/]+/[^/]+/)").getMatch(0);
            } else {
                baseHttp = br.getRegex("(https?://mediandr\\-a\\.akamaihd\\.net/progressive[^/]*?/)[^\"]+\\.mp4").getMatch(0);
            }
            if (baseHttp != null) {
                http_url_format = baseHttp + urlpart + ".%s.mp4";
            }
        } else if (new Regex(hlsMaster, pattern_ard).matches()) {
            urlpart = new Regex(hlsMaster, pattern_ard).getMatch(0);
            http_url_format = "https://pdodswr-a.akamaihd.net/" + urlpart + "%s.mp4";
        } else if (new Regex(hlsMaster, pattern_hr).matches()) {
            urlpart = new Regex(hlsMaster, pattern_hr).getMatch(0);
            http_url_format = "http://hrardmediathek-a.akamaihd.net/" + urlpart + "%skbit.mp4";
        } else {
            /* Unsupported URL */
            logger.warning("Warning: Unsupported HLS pattern, cannot create HTTP URL!");
        }
        return http_url_format;
    }

    private void decryptArdmediathekDeNew(final CryptedLink param) throws Exception {
        /* E.g. old classic.ardmediathek.de URLs */
        final boolean requiresOldContentIDHandling;
        String ardDocumentID = new Regex(param.getCryptedUrl(), "documentId=(\\d+)").getMatch(0);
        Map<String, Object> entries = null;
        final ArdMetadata metadata = new ArdMetadata();
        if (ardDocumentID != null) {
            requiresOldContentIDHandling = true;
            metadata.setTitle(ardDocumentID);
            metadata.setContentID(ardDocumentID);
        } else {
            final URL url = new URL(param.getCryptedUrl());
            requiresOldContentIDHandling = false;
            String ardBase64;
            final String pattern_player = ".+/player/([^/]+).*";
            if (param.getCryptedUrl().matches(pattern_player)) {
                /* E.g. URLs that are a little bit older */
                ardBase64 = new Regex(url.getPath(), pattern_player).getMatch(0);
            } else {
                /* New URLs */
                ardBase64 = new Regex(url.getPath(), "/([^/]+)/?$").getMatch(0);
            }
            if (ardBase64 == null) {
                /* This should never happen */
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            if (Encoding.isUrlCoded(ardBase64)) {
                ardBase64 = Encoding.urlDecode(ardBase64, true);
            }
            /* Check if we really have a base64 String otherwise we can abort right away */
            final String ardBase64Decoded = Encoding.Base64Decode(ardBase64);
            if (StringUtils.equals(ardBase64, ardBase64Decoded)) {
                logger.info("Unsupported URL (?)");
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            br.getPage("https://page.ardmediathek.de/page-gateway/pages/daserste/item/" + Encoding.urlEncode(ardBase64) + "?devicetype=pc&embedded=false");
            if (br.getHttpConnection().getResponseCode() == 404) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            ardDocumentID = PluginJSonUtils.getJson(br, "contentId");
            // final ArrayList<Object> ressourcelist = (ArrayList<Object>) entries.get("");
            entries = JSonStorage.restoreFromString(br.toString(), TypeRef.HASHMAP);
            entries = (Map<String, Object>) JavaScriptEngineFactory.walkJson(entries, "widgets/{0}/");
            final String broadcastedOn = (String) entries.get("broadcastedOn");
            final String title = (String) entries.get("title");
            final String showname = (String) JavaScriptEngineFactory.walkJson(entries, "show/title");
            final String type = (String) entries.get("type");
            if ("player_live".equalsIgnoreCase(type)) {
                logger.info("Cannot download livestreams");
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            } else if (entries.get("blockedByFsk") == Boolean.TRUE) {
                /* AGE restricted content (can only be watched in the night) */
                throw new DecrypterRetryException(RetryReason.NO_ACCOUNT, "FSK_BLOCKED_" + title, "FSK_BLOCKED", null);
            } else if (StringUtils.isEmpty(broadcastedOn)) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            } else if (StringUtils.isAllEmpty(title, showname)) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            String date_formatted = new Regex(broadcastedOn, "(\\d{4}-\\d{2}-\\d{2})").getMatch(0);
            if (date_formatted == null) {
                /* Fallback */
                date_formatted = broadcastedOn;
            }
            metadata.setTitle(title);
            metadata.setSubtitle(showname);
            metadata.setDateTimestamp(getDateMilliseconds(broadcastedOn));
            if (ardDocumentID != null) {
                /* Required for linkid / dupe check */
                this.contentID = ardDocumentID;
            }
        }
        metadata.setChannel("ardmediathek");
        if (requiresOldContentIDHandling) {
            if (StringUtils.isEmpty(ardDocumentID)) {
                /* Probably offline content */
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            /* 2020-05-26: Also possible: http://page.ardmediathek.de/page-gateway/playerconfig/<documentID> */
            /* Old way: http://www.ardmediathek.de/play/media/%s?devicetype=pc&features=flash */
            br.getPage(String.format("http://page.ardmediathek.de/page-gateway/mediacollection/%s?devicetype=pc", ardDocumentID));
            entries = (Map<String, Object>) JavaScriptEngineFactory.walkJson(entries, "mediaCollection/embedded");
        } else {
            entries = JSonStorage.restoreFromString(br.toString(), TypeRef.HASHMAP);
        }
        crawlARDJson(param, metadata, entries);
    }

    /** Last revision with old handling: 38658 */
    private void decryptMediathek(final CryptedLink param) throws Exception {
        br.getPage(param.getCryptedUrl());
        if (isOffline(this.br)) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        final Browser brHTML = br.cloneBrowser();
        if (param.getCryptedUrl().matches(type_embedded)) {
            /* Embedded content --> json URL has already been accessed */
            // brJSON = br.cloneBrowser();
            /* Do nothing */
        } else {
            /**
             * Look for embedded content which will go back into this crawler. Especially needed for: wdr.de, wdrmaus.de, sportschau.de,
             * sandmann.de
             */
            final ArrayList<DownloadLink> embeds = crawlEmbeddedContent(this.br);
            if (!embeds.isEmpty()) {
                this.decryptedLinks.addAll(embeds);
                return;
            }
            String url_json = null;
            if (this.getHost().equals("sportschau.de")) {
                /* Special handling: Embedded videoplayer --> "ardjson" URL will be inside that html */
                final String embedPlayerURL = br.getRegex("allowFullScreen\\s*src=\"(/[^\"]+)\"").getMatch(0);
                /* This step is optional in case the user directly adds an embedded URL. */
                if (embedPlayerURL != null && embedPlayerURL.contains("-ardplayer")) {
                    url_json = embedPlayerURL.replace("-ardplayer", "-ardjson");
                } else if (br.getURL().contains("-ardplayer")) {
                    url_json = br.getURL().replace("-ardplayer", "-ardjson");
                } else if (br.getURL().contains("-ardjson")) {
                    /* URL has already been accessed. */
                    url_json = br.getURL();
                }
            } else {
                br.setFollowRedirects(true);
                if (this.getHost().equalsIgnoreCase("sandmann.de")) {
                    url_json = br.getRegex("data\\-media\\-ref=\"([^\"]*?\\.jsn)[^\"]*?\"").getMatch(0);
                    if (!StringUtils.isEmpty(url_json)) {
                        if (url_json.startsWith("/")) {
                            url_json = "https://www.sandmann.de" + url_json;
                        }
                        /* This is a very ugly contentID */
                        this.contentID = new Regex(url_json, "sandmann\\.de/(.+)").getMatch(0);
                    }
                } else if (this.getHost().contains("ndr.de") || this.getHost().equalsIgnoreCase("eurovision.de")) {
                    /* E.g. daserste.ndr.de, blabla.ndr.de */
                    this.contentID = br.getRegex("([A-Za-z0-9]+\\d+)\\-(?:ard)?player_[^\"]+\"").getMatch(0);
                    if (!StringUtils.isEmpty(this.contentID)) {
                        url_json = String.format("https://www.ndr.de/%s-ardjson.json", this.contentID);
                    }
                } else {
                    /* wdr.de, wdrmaus.de */
                    url_json = this.br.getRegex("(?:\\'|\")mediaObj(?:\\'|\"):\\s*?\\{\\s*?(?:\\'|\")url(?:\\'|\"):\\s*?(?:\\'|\")(https?://[^<>\"]+\\.js)(?:\\'|\")").getMatch(0);
                    if (url_json != null) {
                        /* 2018-03-07: Same IDs that will also appear in every streamingURL! */
                        this.contentID = new Regex(url_json, "(\\d+/\\d+)\\.js$").getMatch(0);
                    }
                }
            }
            if (StringUtils.isEmpty(url_json)) {
                /* No downloadable content --> URL should be offline (or only text content) */
                /* 2021-04-07: Check for special case with json inside html e.g. for wdr.de */
                final String json = br.getRegex("globalObject\\.gseaInlineMediaData\\[\"mdb-\\d+\"\\] =\\s*(\\{.*?\\});\\s*</script>").getMatch(0);
                if (json == null) {
                    throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                }
                br.getRequest().setHtmlCode(json);
            } else {
                br.getPage(url_json);
                /* No json --> No media to crawl (rare case!)! */
                if (!br.getHttpConnection().getContentType().contains("application/json") && !br.getHttpConnection().getContentType().contains("application/javascript") && !br.containsHTML("\\{") || br.getHttpConnection().getResponseCode() == 404 || br.toString().length() <= 10) {
                    throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                }
            }
        }
        /* 2021-08-11: E.g. wdr.de | type_embedded */
        final String specialOldJson = regexOldJson(br.toString());
        if (specialOldJson != null) {
            br.getRequest().setHtmlCode(specialOldJson);
        }
        String title = null;
        String show = null;
        String channel = null;
        Map<String, Object> dataEmbeddedContent = null;
        try {
            final String json = regexOldJson(br.toString());
            dataEmbeddedContent = JSonStorage.restoreFromString(json, TypeRef.HASHMAP);
        } catch (final Throwable e) {
        }
        /* E.g. wdr.de, Tags: schema.org */
        final String jsonSchemaOrg = brHTML.getRegex("<script[^>]*?type=\"application/ld\\+json\"[^>]*?>(.*?)</script>").getMatch(0);
        /* These RegExes should be compatible with all websites */
        /* Date is already provided in the format we need. */
        String date = brHTML.getRegex("<meta property=\"video:release_date\" content=\"(\\d{4}\\-\\d{2}\\-\\d{2})[^\"]*?\"[^>]*?/?>").getMatch(0);
        if (date == null) {
            date = brHTML.getRegex("<span itemprop=\"datePublished\" content=\"(\\d{4}\\-\\d{2}\\-\\d{2})[^\"]*?\"[^>]*?/?>").getMatch(0);
        }
        String description = brHTML.getRegex("<meta property=\"og:description\" content=\"([^\"]+)\"").getMatch(0);
        final String host = getHost();
        if (jsonSchemaOrg != null) {
            /* 2018-02-15: E.g. daserste.de, wdr.de */
            final String headline = brHTML.getRegex("<h3 class=\"headline\">([^<>]+)</h3>").getMatch(0);
            try {
                Map<String, Object> entries = JavaScriptEngineFactory.jsonToJavaMap(jsonSchemaOrg);
                final String uploadDate = (String) entries.get("uploadDate");
                title = (String) entries.get("name");
                if ("Video".equalsIgnoreCase(title) && !StringUtils.isEmpty(headline)) {
                    /**
                     * 2018-02-22: Some of these schema-objects contain wrong information e.g.
                     * https://www1.wdr.de/mediathek/video/klangkoerper/klangkoerper/video-wdr-dackl-jazzkonzert-100.html --> This is a
                     * simple fallback.
                     */
                    title = headline;
                }
                if (description == null) {
                    description = (String) entries.get("description");
                }
                if (StringUtils.isEmpty(date) && !StringUtils.isEmpty(uploadDate)) {
                    /* Fallback */
                    date = new Regex(uploadDate, "(\\d{4}\\-\\d{2}\\-\\d{2})").getMatch(0);
                }
                /* Find more data */
                entries = (Map<String, Object>) entries.get("productionCompany");
                if (entries != null) {
                    channel = (String) entries.get("name");
                }
            } catch (final Throwable e) {
            }
            if (StringUtils.isEmpty(title) && headline != null) {
                /* 2018-04-11: ardmediathek.de */
                title = headline;
            }
        } else if (host.equalsIgnoreCase("wdrmaus.de")) {
            final String content_ids_str = brHTML.getRegex("var _contentId = \\[([^<>\\[\\]]+)\\];").getMatch(0);
            if (content_ids_str != null) {
                final String[] content_ids = content_ids_str.split(",");
                if (content_ids != null && content_ids.length >= 3) {
                    show = content_ids[0];
                    title = content_ids[2];
                }
            }
            if (StringUtils.isEmpty(title)) {
                title = brHTML.getRegex("<title>([^<>]+) \\- Die Sendung mit der Maus \\- WDR</title>").getMatch(0);
            }
            if (StringUtils.isEmpty(show) && (brHTML.getURL().contains("/lachgeschichten") || brHTML.getURL().contains("/sachgeschichten"))) {
                // show = "Die Sendung mit der Maus";
                show = "Lach- und Sachgeschichten";
            }
            /*
             * 2018-02-22: TODO: This may sometimes be inaccurate when there are multiple videoObjects on one page (rare case) e.g.
             * http://www.wdrmaus.de/extras/mausthemen/eisenbahn/index.php5 --> This is so far not a real usage case and we do not have any
             * complaints about the current plugin behavior!
             */
            if (StringUtils.isEmpty(date)) {
                date = brHTML.getRegex("Sendetermin: (\\d{2}\\.\\d{2}\\.\\d{4})").getMatch(0);
            }
            if (StringUtils.isEmpty(date)) {
                /* Last chance */
                date = PluginJSonUtils.getJson(br, "trackerClipAirTime");
            }
        } else if (dataEmbeddedContent != null) {
            /* E.g. type_embedded --> deviceids-medp-id1.wdr.de/ondemand/123/1234567.js */
            dataEmbeddedContent = (Map<String, Object>) dataEmbeddedContent.get("trackerData");
            // final String trackerClipCategory = (String) dataEmbeddedContent.get("trackerClipCategory");
            show = (String) dataEmbeddedContent.get("trackerClipSubcategory");
            title = (String) dataEmbeddedContent.get("trackerClipTitle");
            // final String trackerClipAirTime = (String) dataEmbeddedContent.get("trackerClipAirTime");
            date = (String) dataEmbeddedContent.get("trackerClipAirTime");
        } else if (host.equalsIgnoreCase("ndr.de") || host.equalsIgnoreCase("eurovision.de")) {
            /* ndr.de */
            if (brHTML.getURL().contains("daserste.ndr.de") && StringUtils.isEmpty(date)) {
                date = brHTML.getRegex("<p>Dieses Thema im Programm:</p>\\s*?<h2>[^<>]*?(\\d{2}\\.\\d{2}\\.\\d{4})[^<>]*?</h2>").getMatch(0);
            }
            title = brHTML.getRegex("<meta property=\"og:title\" content=\"([^<>\"]+)\"/>").getMatch(0);
            if (StringUtils.isEmpty(date)) {
                /* Last chance */
                date = PluginJSonUtils.getJson(br, "assetid");
                if (!StringUtils.isEmpty(date)) {
                    date = new Regex(date, "TV\\-(\\d{8})").getMatch(0);
                }
            }
        } else {
            /* E.g. ardmediathek.de */
            String newjson = brHTML.getRegex("window\\.__APOLLO_STATE__ = (\\{.*?);\\s+").getMatch(0);
            if (newjson == null) {
                newjson = br.toString();
            }
            show = PluginJSonUtils.getJson(newjson, "show");
            title = PluginJSonUtils.getJson(newjson, "clipTitle");
            if (StringUtils.isEmpty(title)) {
                /* 2021-04-07: wdr.de */
                title = PluginJSonUtils.getJson(newjson, "trackerClipTitle");
            }
            if (title == null) {
                title = br.getRegex("<meta name\\s*=\\s*\"dcterms.title\"\\s*content\\s*=\\s*\"(.*?)\"").getMatch(0);
            }
            if (date == null) {
                date = PluginJSonUtils.getJson(newjson, "broadcastedOn");
            }
        }
        if (StringUtils.isEmpty(title)) {
            /* This should never happen */
            title = "UnknownTitle_" + UniqueAlltimeID.create();
        }
        title = title.trim();
        if (StringUtils.isEmpty(channel)) {
            /* Fallback */
            channel = host.substring(0, host.lastIndexOf(".")).replace(".", "_");
        }
        title = Encoding.htmlDecode(title);
        title = encodeUnicode(title);
        final ArdMetadata metadata = new ArdMetadata(title);
        metadata.setSubtitle(show);
        if (date != null) {
            metadata.setDateTimestamp(getDateMilliseconds(date));
        }
        Object entries = null;
        try {
            entries = JSonStorage.restoreFromString(br.toString(), TypeRef.HASHMAP);
        } catch (final Throwable ignore) {
        }
        crawlARDJson(param, metadata, entries);
    }

    private static String regexOldJson(final String html) {
        return new Regex(html, "\\$mediaObject\\.jsonpHelper\\.storeAndPlay\\((\\{.*?\\})\\);").getMatch(0);
    }

    private ArrayList<DownloadLink> crawlEmbeddedContent(final Browser br) {
        final ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
        final String[] embeddedVideosTypeOldJson = br.getRegex("(?:\\'|\")mediaObj(?:\\'|\"):\\s*?\\{\\s*?(?:\\'|\")url(?:\\'|\"):\\s*?(?:\\'|\")(https?://[^<>\"]+\\.js)(?:\\'|\")").getColumn(0);
        for (final String embeddedVideo : embeddedVideosTypeOldJson) {
            ret.add(this.createDownloadlink(embeddedVideo));
        }
        return ret;
    }

    private void crawlARDJson(final CryptedLink param, final ArdMetadata metadata, final Object mediaCollection) throws Exception {
        /* We know how their http urls look - this way we can avoid HDS/HLS/RTMP */
        /*
         * http://adaptiv.wdr.de/z/medp/ww/fsk0/104/1046579/,1046579_11834667,1046579_11834665,1046579_11834669,.mp4.csmil/manifest.f4
         */
        // //wdradaptiv-vh.akamaihd.net/i/medp/ondemand/weltweit/fsk0/139/1394333/,1394333_16295554,1394333_16295556,1394333_16295555,1394333_16295557,1394333_16295553,1394333_16295558,.mp4.csmil/master.m3u8
        /*
         * Grab all http qualities inside json
         */
        subtitleLink = getJsonSubtitleURL(this.br);
        final List<String> httpStreamsQualityIdentifiers = new ArrayList<String>();
        /* For http stream quality identifiers which have been created by the hls --> http URLs converter */
        final List<String> httpStreamsQualityIdentifiers_2_over_hls_master = new ArrayList<String>();
        Map<String, Object> map;
        String exampleHTTPURL = null;
        String hlsMaster = null;
        if (mediaCollection instanceof Map && ((Map<String, Object>) mediaCollection).containsKey("mediaResource")) {
            /* E.g. older wdr.de json --> Only extract hls-master, then generate http URLs down below */
            final Map<String, Object> mediaResource = (Map<String, Object>) ((Map<String, Object>) mediaCollection).get("mediaResource");
            /* All of these are usually HLS */
            final String[] mediaNames = new String[] { "dflt", "alt" };
            for (final String mediaType : mediaNames) {
                if (mediaResource.containsKey(mediaType)) {
                    final Map<String, Object> media = (Map<String, Object>) mediaResource.get(mediaType);
                    final String hlsMasterTmp = (String) media.get("videoURL");
                    if (media.get("mediaFormat").toString().equalsIgnoreCase("hls") && !StringUtils.isEmpty(hlsMasterTmp)) {
                        hlsMaster = hlsMasterTmp;
                        break;
                    }
                }
            }
        } else {
            if (mediaCollection instanceof Map) {
                map = (Map<String, Object>) mediaCollection;
                if (!map.containsKey("_mediaArray")) {
                    /* 2020-06-08: For new ARD URLs */
                    map = (Map<String, Object>) JavaScriptEngineFactory.walkJson(map, "widgets/{0}/mediaCollection/embedded");
                }
            } else {
                map = null;
            }
            if (map != null && map.containsKey("_mediaArray")) {
                /*
                 * Website actually tries to stream video - only then it is safe to know if the items is only "somewhere" GEO-blocked or
                 * GEO-blocked for the current user/IP!
                 */
                // final boolean geoBlocked = ((Boolean) map.get("_geoblocked")).booleanValue();
                // if (geoBlocked) {
                // /* 2020-11-19: Direct-URLs are given but will all redirect to a "GEO-blocked" video so let's stop here! */
                // throw new DecrypterException(EXCEPTION_GEOBLOCKED);
                // }
                try {
                    final List<Map<String, Object>> mediaArray = (List<Map<String, Object>>) map.get("_mediaArray");
                    mediaArray: for (Map<String, Object> media : mediaArray) {
                        final List<Map<String, Object>> mediaStreamArray = (List<Map<String, Object>>) media.get("_mediaStreamArray");
                        for (int mediaStreamIndex = 0; mediaStreamIndex < mediaStreamArray.size(); mediaStreamIndex++) {
                            // list is sorted from best to lowest quality, first one is m3u8
                            final Map<String, Object> mediaStream = mediaStreamArray.get(mediaStreamIndex);
                            final int quality;
                            final Object _stream = mediaStream.get("_stream");
                            if (mediaStream.get("_quality") instanceof Number) {
                                quality = ((Number) mediaStream.get("_quality")).intValue();
                            } else {
                                /* E.g. skip quality "auto" (HLS) */
                                if (_stream instanceof String) {
                                    final String url = _stream.toString();
                                    if (url.contains(".m3u8")) {
                                        hlsMaster = url;
                                    }
                                }
                                continue;
                            }
                            final List<String> streams;
                            if (_stream instanceof String) {
                                streams = new ArrayList<String>();
                                streams.add((String) _stream);
                            } else {
                                streams = ((List<String>) _stream);
                            }
                            for (int index = 0; index < streams.size(); index++) {
                                final String stream = streams.get(index);
                                if (stream == null || !StringUtils.endsWithCaseInsensitive(stream, ".mp4")) {
                                    /* Skip invalid objects */
                                    continue;
                                }
                                final String url = br.getURL(stream).toString();
                                exampleHTTPURL = url;
                                final int widthInt;
                                final int heightInt;
                                /*
                                 * Sometimes the resolutions is given, sometimes we have to assume it and sometimes (e.g. HLS streaming)
                                 * there are multiple qualities available for one stream URL.
                                 */
                                if (mediaStream.containsKey("_width") && mediaStream.containsKey("_height")) {
                                    widthInt = ((Number) mediaStream.get("_width")).intValue();
                                    heightInt = ((Number) mediaStream.get("_height")).intValue();
                                } else if (quality == 0 && streams.size() == 1) {
                                    widthInt = 320;
                                    heightInt = 180;
                                } else if (quality == 1 && streams.size() == 1) {
                                    widthInt = 512;
                                    heightInt = 288;
                                } else if (quality == 1 && streams.size() == 2) {
                                    switch (index) {
                                    case 0:
                                        widthInt = 512;
                                        heightInt = 288;
                                        break;
                                    case 1:
                                    default:
                                        widthInt = 480;
                                        heightInt = 270;
                                        break;
                                    }
                                } else if (quality == 2 && streams.size() == 1) {
                                    widthInt = 960;
                                    heightInt = 544;
                                } else if (quality == 2 && streams.size() == 2) {
                                    switch (index) {
                                    case 0:
                                        widthInt = 640;
                                        heightInt = 360;
                                        break;
                                    case 1:
                                    default:
                                        widthInt = 960;
                                        heightInt = 540;
                                        break;
                                    }
                                } else if (quality == 3 && streams.size() == 1) {
                                    widthInt = 960;
                                    heightInt = 540;
                                } else if (quality == 3 && streams.size() == 2) {
                                    switch (index) {
                                    case 0:
                                        widthInt = 1280;
                                        heightInt = 720;
                                        break;
                                    case 1:
                                    default:
                                        widthInt = 960;
                                        heightInt = 540;
                                        break;
                                    }
                                } else if (StringUtils.containsIgnoreCase(stream, "0.mp4") || StringUtils.containsIgnoreCase(stream, "128k.mp4")) {
                                    widthInt = 320;
                                    heightInt = 180;
                                } else if (StringUtils.containsIgnoreCase(stream, "lo.mp4")) {
                                    widthInt = 256;
                                    heightInt = 144;
                                } else if (StringUtils.containsIgnoreCase(stream, "A.mp4") || StringUtils.containsIgnoreCase(stream, "mn.mp4") || StringUtils.containsIgnoreCase(stream, "256k.mp4")) {
                                    widthInt = 480;
                                    heightInt = 270;
                                } else if (StringUtils.containsIgnoreCase(stream, "B.mp4") || StringUtils.containsIgnoreCase(stream, "hi.mp4") || StringUtils.containsIgnoreCase(stream, "512k.mp4")) {
                                    widthInt = 512;
                                    heightInt = 288;
                                } else if (StringUtils.containsIgnoreCase(stream, "C.mp4") || StringUtils.containsIgnoreCase(stream, "hq.mp4") || StringUtils.containsIgnoreCase(stream, "1800k.mp4")) {
                                    widthInt = 960;
                                    heightInt = 540;
                                } else if (StringUtils.containsIgnoreCase(stream, "E.mp4") || StringUtils.containsIgnoreCase(stream, "ln.mp4") || StringUtils.containsIgnoreCase(stream, "1024k.mp4") || StringUtils.containsIgnoreCase(stream, "1.mp4")) {
                                    widthInt = 640;
                                    heightInt = 360;
                                } else if (StringUtils.containsIgnoreCase(stream, "X.mp4") || StringUtils.containsIgnoreCase(stream, "hd.mp4")) {
                                    widthInt = 1280;
                                    heightInt = 720;
                                } else {
                                    /*
                                     * Fallback to 'old' handling which could result in wrong resolutions (but that's better than missing
                                     * downloadlinks!)
                                     */
                                    final Object width = mediaStream.get("_width");
                                    final Object height = mediaStream.get("_height");
                                    if (width instanceof Number) {
                                        widthInt = ((Number) width).intValue();
                                    } else {
                                        switch (((Number) quality).intValue()) {
                                        case 0:
                                            widthInt = 320;
                                            break;
                                        case 1:
                                            widthInt = 512;
                                            break;
                                        case 2:
                                            widthInt = 640;
                                            break;
                                        case 3:
                                            widthInt = 1280;
                                            break;
                                        default:
                                            widthInt = -1;
                                            break;
                                        }
                                    }
                                    if (width instanceof Number) {
                                        heightInt = ((Number) height).intValue();
                                    } else {
                                        switch (((Number) quality).intValue()) {
                                        case 0:
                                            heightInt = 180;
                                            break;
                                        case 1:
                                            heightInt = 288;
                                            break;
                                        case 2:
                                            heightInt = 360;
                                            break;
                                        case 3:
                                            heightInt = 720;
                                            break;
                                        default:
                                            heightInt = -1;
                                            break;
                                        }
                                    }
                                }
                                final DownloadLink download = addQuality(param, metadata, foundQualitiesMap, url, null, 0, widthInt, heightInt, false);
                                if (download != null) {
                                    httpStreamsQualityIdentifiers.add(getQualityIdentifier(url, 0, widthInt, heightInt));
                                    if (cfg.isGrabBESTEnabled()) {
                                        // we iterate mediaStreamArray from best to lowest
                                        // TODO: optimize for cfg.isOnlyBestVideoQualityOfSelectedQualitiesEnabled()
                                        break mediaArray;
                                    }
                                }
                            }
                        }
                    }
                } catch (final Throwable ignore) {
                    logger.log(ignore);
                }
            }
        }
        /*
         * TODO: It might only make sense to attempt this if we found more than 3 http qualities previously because usually 3 means we will
         * also only have 3 hls qualities --> There are no additional http qualities!
         */
        // hlsMaster =
        // "https://wdradaptiv-vh.akamaihd.net/i/medp/ondemand/weltweit/fsk0/232/2326527/,2326527_32403893,2326527_32403894,2326527_32403895,2326527_32403891,2326527_32403896,2326527_32403892,.mp4.csmil/master.m3u8";
        String http_url_audio = br.getRegex("((?:https?:)?//[^<>\"]+\\.mp3)\"").getMatch(0);
        final String quality_string = new Regex(hlsMaster, ".*?/i/.*?,([A-Za-z0-9_,\\-\\.]+),?\\.mp4\\.csmil.*?").getMatch(0);
        if (StringUtils.isEmpty(hlsMaster) && http_url_audio == null && httpStreamsQualityIdentifiers.size() == 0) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        /*
         * This is a completely different attempt to find HTTP URLs. As long as it works, this may be more reliable than everything above
         * here!
         */
        final boolean tryToFindAdditionalHTTPURLs = true;
        if (tryToFindAdditionalHTTPURLs && hlsMaster != null) {
            final String http_url_format = getHlsToHttpURLFormat(hlsMaster, exampleHTTPURL);
            final String[] qualities_hls = quality_string != null ? quality_string.split(",") : null;
            if (http_url_format != null && qualities_hls != null && qualities_hls.length > 0) {
                /* Access HLS master to find correct resolution for each ID (the only possible way) */
                URLConnectionAdapter con = null;
                try {
                    con = br.openGetConnection(br.getURL(hlsMaster).toString());
                    if (con.getURL().toString().contains("/static/geoblocking.mp4")) {
                        if (httpStreamsQualityIdentifiers.size() == 0) {
                            this.errorGEOBlocked(param);
                        }
                    } else {
                        br.followConnection(true);
                    }
                } finally {
                    if (con != null) {
                        con.disconnect();
                    }
                }
                final String[] resolutionsInOrder = br.getRegex("RESOLUTION=(\\d+x\\d+)").getColumn(0);
                if (resolutionsInOrder != null) {
                    logger.info("Crawling additional http urls");
                    for (int counter = 0; counter <= qualities_hls.length - 1; counter++) {
                        if (counter > qualities_hls.length - 1 || counter > resolutionsInOrder.length - 1) {
                            break;
                        }
                        final String quality_id = qualities_hls[counter];
                        final String final_url = String.format(http_url_format, quality_id);
                        // final String linkid = qualities[counter];
                        final String resolution = resolutionsInOrder[counter];
                        final String[] height_width = resolution.split("x");
                        final String width = height_width[0];
                        final String height = height_width[1];
                        final int widthInt = Integer.parseInt(width);
                        final int heightInt = Integer.parseInt(height);
                        final String qualityIdentifier = getQualityIdentifier(final_url, 0, widthInt, heightInt);
                        if (!httpStreamsQualityIdentifiers_2_over_hls_master.contains(qualityIdentifier)) {
                            logger.info("Found (additional) http quality via HLS Master: " + qualityIdentifier);
                            addQuality(param, metadata, foundQualitiesMap_http_urls_via_HLS_master, final_url, null, 0, widthInt, heightInt, false);
                            httpStreamsQualityIdentifiers_2_over_hls_master.add(qualityIdentifier);
                        }
                    }
                }
            }
            /*
             * Decide whether we want to use the existing http URLs or whether we want to prefer the ones we've generated out of their HLS
             * URLs.
             */
            final int numberof_http_qualities_found_inside_json = foundQualitiesMap.keySet().size();
            final int numberof_http_qualities_found_via_hls_to_http_conversion = foundQualitiesMap_http_urls_via_HLS_master.keySet().size();
            if (numberof_http_qualities_found_via_hls_to_http_conversion > numberof_http_qualities_found_inside_json) {
                /*
                 * 2019-04-15: Prefer URLs created via this way because if we don't, we may get entries labled as different qualities which
                 * may be duplicates!
                 */
                logger.info(String.format("Found [%d] qualities via HLS --> HTTP conversion which is more than number of http URLs inside json [%d]", numberof_http_qualities_found_via_hls_to_http_conversion, numberof_http_qualities_found_inside_json));
                logger.info("--> Using converted URLs instead");
                foundQualitiesMap.clear();
                foundQualitiesMap.putAll(foundQualitiesMap_http_urls_via_HLS_master);
            }
        }
        if (hlsMaster != null) {
            addHLS(param, metadata, br, hlsMaster, false);
        }
        if (http_url_audio != null) {
            if (http_url_audio.startsWith("//")) {
                /* 2019-04-11: Workaround for missing protocol */
                http_url_audio = "https:" + http_url_audio;
            }
            addQuality(param, metadata, foundQualitiesMap, http_url_audio, null, 0, 0, 0, false);
        }
    }

    /**
     * Handling for older ARD websites. </br>
     * INFORMATION: network = akamai or limelight == RTMP
     */
    private void crawlDasersteVideo(final CryptedLink param) throws Exception {
        br.getPage(param.getCryptedUrl());
        if (this.br.getHttpConnection().getResponseCode() == 404) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        setBrowserExclusive();
        br.setFollowRedirects(true);
        final String xml_URL = getVideoXMLURL(param);
        if (xml_URL == null) {
            /* Probably no downloadable content available */
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        br.getPage(xml_URL);
        /* Usually daserste.de as there is no way to find a contentID inside URL added by the user. */
        final String id = br.getRegex("<c7>(.*?)</c7>").getMatch(0);
        if (id != null && this.contentID == null) {
            contentID = Hash.getSHA1(id);
        }
        if (br.getHttpConnection().getResponseCode() == 404 || !br.getHttpConnection().getContentType().contains("xml")) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        this.subtitleLink = getXMLSubtitleURL(this.br);
        String date = getXML(br.toString(), "broadcastDate");
        if (StringUtils.isEmpty(date)) {
            /* E.g. kika.de */
            date = getXML(br.toString(), "datetimeOfBroadcasting");
        }
        if (StringUtils.isEmpty(date)) {
            /* E.g. mdr.de */
            date = getXML(br.toString(), "broadcastStartDate");
        }
        /* E.g. kika.de */
        final String show = getXML(br.toString(), "channelName");
        String title = getXML(br.toString(), "shareTitle");
        if (StringUtils.isEmpty(title)) {
            title = getXML(br.toString(), "broadcastName");
        }
        if (StringUtils.isEmpty(title)) {
            /* E.g. sputnik.de */
            title = getXML(br.toString(), "headline");
        }
        if (StringUtils.isEmpty(title)) {
            title = "UnknownTitle_" + System.currentTimeMillis();
        }
        final ArdMetadata metadata = new ArdMetadata(title);
        if (date != null) {
            metadata.setDateTimestamp(getDateMilliseconds(date));
        }
        final ArrayList<String> hls_master_dupelist = new ArrayList<String>();
        final String assetsAudiodescription = br.getRegex("<assets type=\"audiodesc\">(.*?)</assets>").getMatch(0);
        final String assetsNormal = br.getRegex("<assets>(.*?)</assets>").getMatch(0);
        boolean isAudioDescription = false;
        String[] mediaStreamArray = null;
        if (this.cfg.isPreferAudioDescription() && assetsAudiodescription != null) {
            logger.info("Crawling asset-type audiodescription");
            isAudioDescription = true;
            mediaStreamArray = new Regex(assetsAudiodescription, "(<asset.*?</asset>)").getColumn(0);
        }
        if (mediaStreamArray == null || mediaStreamArray.length == 0) {
            logger.info("Crawling asset-type normal");
            isAudioDescription = false;
            mediaStreamArray = new Regex(assetsNormal, "(<asset.*?</asset>)").getColumn(0);
        }
        if (mediaStreamArray.length == 0) {
            /* 2021-05-10: Only check for this if no downloadurls are available! */
            final String fskRating = this.br.getRegex("<fskRating>fsk(\\d+)</fskRating>").getMatch(0);
            if (fskRating != null && Short.parseShort(fskRating) >= 12) {
                /* Video is age restricted --> Only available from >=8PM. */
                final String filenameURL = Plugin.getFileNameFromURL(new URL(param.getCryptedUrl()));
                if (filenameURL != null) {
                    throw new DecrypterRetryException(RetryReason.FILE_NOT_FOUND, "FSK_BLOCKED_" + filenameURL, "FSK_BLOCKED", null);
                } else {
                    throw new DecrypterRetryException(RetryReason.FILE_NOT_FOUND, "FSK_BLOCKED", "FSK_BLOCKED", null);
                }
            }
        }
        for (final String stream : mediaStreamArray) {
            final String streamType = getXML(stream, "streamLabel");
            if (StringUtils.equalsIgnoreCase(streamType, "DASH-Streaming")) {
                /*
                 * 2021-11-18: Usually DASH comes with separated video/audio and also HLS should be available too --> Skip DASH </br> Seen
                 * for: mdr.de livestreams e.g. https://www.mdr.de/video/livestreams/mdr-plus/sport-eventlivestreamzweiww-328.html
                 */
                logger.info("Skipping DASH stream");
                continue;
            }
            /* E.g. kika.de */
            final String hls_master;
            String http_url = getXML(stream, "progressiveDownloadUrl");
            if (StringUtils.isEmpty(http_url)) {
                /* E.g. daserste.de */
                http_url = getXML(stream, "fileName");
                if (StringUtils.isEmpty(http_url)) {
                    /* hls master fallback, eg livestreams */
                    http_url = getXML(stream, "adaptiveHttpStreamingRedirectorUrl");
                }
            }
            /* E.g. daserste.de */
            String filesize = getXML(stream, "size");
            if (StringUtils.isEmpty(filesize)) {
                /* E.g. kika.de */
                filesize = getXML(stream, "fileSize");
            }
            final String bitrate_video = getXML(stream, "bitrateVideo");
            final String bitrate_audio = getXML(stream, "bitrateAudio");
            final String width_str = getXML(stream, "frameWidth");
            final String height_str = getXML(stream, "frameHeight");
            /* This sometimes contains resolution: e.g. <profileName>Video 2018 | MP4 720p25 | Web XL| 16:9 | 1280x720</profileName> */
            final String profileName = getXML(stream, "profileName");
            final String resolutionInProfileName = new Regex(profileName, "(\\d+x\\d+)").getMatch(0);
            int width = 0;
            int height = 0;
            if (width_str != null && width_str.matches("\\d+")) {
                width = Integer.parseInt(width_str);
            }
            if (height_str != null && height_str.matches("\\d+")) {
                height = Integer.parseInt(height_str);
            }
            if (width == 0 && height == 0 && resolutionInProfileName != null) {
                final String[] resInfo = resolutionInProfileName.split("x");
                width = Integer.parseInt(resInfo[0]);
                height = Integer.parseInt(resInfo[1]);
            }
            if (StringUtils.isEmpty(http_url) || isUnsupportedProtocolDasersteVideo(http_url)) {
                continue;
            }
            if (http_url.contains(".m3u8")) {
                hls_master = http_url;
                http_url = null;
            } else {
                /* hls master is stored in separate tag e.g. kika.de */
                hls_master = getXML(stream, "adaptiveHttpStreamingRedirectorUrl");
            }
            /* HLS master url may exist in every XML item --> We only have to add all HLS qualities once! */
            if (!StringUtils.isEmpty(hls_master) && !hls_master_dupelist.contains(hls_master)) {
                /* HLS */
                addHLS(param, metadata, this.br, hls_master, isAudioDescription);
                hls_master_dupelist.add(hls_master);
            }
            if (!StringUtils.isEmpty(http_url)) {
                /* http */
                long bitrate;
                final String bitrateFromURLStr = new Regex(http_url, "(\\d+)k").getMatch(0);
                if (!StringUtils.isEmpty(bitrate_video) && !StringUtils.isEmpty(bitrate_audio)) {
                    bitrate = Long.parseLong(bitrate_video) + Long.parseLong(bitrate_audio);
                    if (bitrate < 10000) {
                        bitrate = bitrate * 1000;
                    }
                } else if (bitrateFromURLStr != null) {
                    bitrate = Long.parseLong(bitrateFromURLStr);
                } else {
                    bitrate = 0;
                }
                addQualityDasersteVideo(param, metadata, http_url, filesize, bitrate, width, height, isAudioDescription);
            }
        }
        return;
    }

    private void crawlTagesschauVideos(final CryptedLink param) throws Exception {
        br.getPage(param.getCryptedUrl());
        if (isOffline(br)) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        /* Crawl all embedded items on this page */
        final String[] embedDatas = br.getRegex("data-ts_component='ts-mediaplayer'\\s*data-config='([^\\']+)'").getColumn(0);
        for (final String embedData : embedDatas) {
            final String embedJson = Encoding.htmlDecode(embedData);
            final Map<String, Object> root = JSonStorage.restoreFromString(embedJson, TypeRef.HASHMAP);
            final Map<String, Object> mc = (Map<String, Object>) root.get("mc");
            final String _type = (String) mc.get("_type");
            if (_type == null || !_type.equalsIgnoreCase("video")) {
                /* Skip unsupported items */
                continue;
            }
            final Map<String, Object> _info = (Map<String, Object>) mc.get("_info");
            final String clipDate = _info.get("clipDate").toString();
            this.contentID = (String) JavaScriptEngineFactory.walkJson(root, "pc/_pixelConfig/{0}/clipData/assetid");
            final ArdMetadata metadata = new ArdMetadata(mc.get("_title").toString());
            metadata.setChannel(_info.get("channelTitle").toString());
            if (clipDate != null) {
                final long timestamp = TimeFormatter.getMilliSeconds(clipDate, "dd.MM.yyyy HH:mm", Locale.GERMANY);
                metadata.setDateTimestamp(timestamp);
            }
            this.crawlARDJson(param, metadata, mc);
            /* TODO: Refactor plugin to make it possible to add multiple videos in one go */
            break;
        }
    }

    private void addHLS(final CryptedLink param, final ArdMetadata metadata, final Browser br, final String hlsMaster, final boolean isAudioDescription) throws Exception {
        if (!this.grabHLS) {
            /* Avoid this http request if user hasn't selected any hls qualities */
            return;
        }
        Browser hlsBR;
        if (br.getURL().contains(".m3u8")) {
            /* HLS master has already been accessed before so no need to access it again. */
            hlsBR = br;
        } else {
            /* Access (hls) master. */
            hlsBR = br.cloneBrowser();
            hlsBR.getPage(hlsMaster);
        }
        final List<HlsContainer> allHlsContainers = HlsContainer.getHlsQualities(hlsBR);
        for (final HlsContainer hlscontainer : allHlsContainers) {
            if (!hlscontainer.isVideo()) {
                /* Skip audio containers here as we (sometimes) have separate mp3 URLs for this host. */
                continue;
            }
            final String final_download_url = hlscontainer.getDownloadurl();
            addQuality(param, metadata, foundQualitiesMap, final_download_url, null, hlscontainer.getBandwidth(), hlscontainer.getWidth(), hlscontainer.getHeight(), isAudioDescription);
        }
    }

    /* Especially for video.daserste.de */
    private void addQualityDasersteVideo(final CryptedLink param, final ArdMetadata metadata, final String directurl, final String filesize_str, long bitrate, int width, int height, final boolean isAudioDescription) {
        /* Try to get/Fix correct width/height values. */
        /* Type 1 */
        String width_URL = new Regex(directurl, "(hi|hq|ln|lo|mn)\\.mp4$").getMatch(0);
        if (width_URL == null) {
            /* Type 2 */
            width_URL = new Regex(directurl, "(s|m|sm|ml|l)\\.mp4$").getMatch(0);
        }
        if (width_URL == null) {
            /* Type 3 */
            width_URL = new Regex(directurl, "(webm|webs|webl|webxl)").getMatch(0);
        }
        if (width_URL == null) {
            /* Type 4 */
            width_URL = new Regex(directurl, "/(\\d{1,4})\\-\\d+\\.mp4$").getMatch(0);
        }
        width = getWidth(width_URL, width);
        height = getHeight(width_URL, width, height);
        addQuality(param, metadata, foundQualitiesMap, directurl, filesize_str, bitrate, width, height, isAudioDescription);
    }

    /* Returns quality identifier String, compatible with quality selection values. Format: protocol_bitrateCorrected_heightCorrected */
    private String getQualityIdentifier(final String directurl, long bitrate, int width, int height) {
        final String protocol;
        if (directurl.contains("m3u8")) {
            protocol = "hls";
        } else {
            protocol = "http";
        }
        /* Use this for quality selection as real resolution can be slightly different than the values which our users can select. */
        final int height_corrected = getHeightForQualitySelection(height);
        final long bitrate_corrected;
        if (bitrate > 0) {
            bitrate_corrected = getBitrateForQualitySelection(bitrate, directurl);
        } else {
            bitrate_corrected = getDefaultBitrateForHeight(height_corrected);
        }
        final String qualityStringForQualitySelection = protocol + "_" + bitrate_corrected + "_" + height_corrected;
        return qualityStringForQualitySelection;
    }

    private DownloadLink addQuality(final CryptedLink param, final ArdMetadata metadata, final HashMap<String, DownloadLink> qualitiesMap, final String directurl, final String filesize_str, long bitrate, int width, int height, final boolean isAudioDescription) {
        /* Errorhandling */
        final String ext;
        if (directurl == null) {
            /* Skip items with bad data. */
            return null;
        } else if (directurl.contains(".mp3")) {
            ext = "mp3";
        } else {
            ext = "mp4";
        }
        long filesize = 0;
        if (filesize_str != null && filesize_str.matches("\\d+")) {
            filesize = Long.parseLong(filesize_str);
        }
        /* Use real resolution inside filenames */
        final String resolution = width + "x" + height;
        final String protocol;
        if (directurl.contains("m3u8")) {
            protocol = "hls";
        } else {
            protocol = "http";
            if (cfg.isGrabBESTEnabled() || cfg.isOnlyBestVideoQualityOfSelectedQualitiesEnabled()) {
                final Browser brc = br.cloneBrowser();
                brc.setFollowRedirects(true);
                URLConnectionAdapter con = null;
                try {
                    con = brc.openHeadConnection(directurl);
                    if (!jd.plugins.hoster.ARDMediathek.isVideoContent(con)) {
                        brc.followConnection(true);
                        return null;
                    } else {
                        brc.followConnection(true);
                        if (con.getCompleteContentLength() > 0) {
                            filesize = con.getCompleteContentLength();
                        }
                    }
                } catch (IOException e) {
                    logger.log(e);
                    return null;
                } finally {
                    if (con != null) {
                        con.disconnect();
                    }
                }
            }
        }
        final String qualityStringForQualitySelection = getQualityIdentifier(directurl, bitrate, width, height);
        final DownloadLink link = createDownloadlink(directurl.replaceAll("https?://", getHost() + "decrypted://"));
        final MediathekProperties data = link.bindData(MediathekProperties.class);
        data.setTitle(metadata.getTitle());
        data.setSourceHost(getHost());
        data.setChannel(metadata.getChannel());
        data.setResolution(resolution);
        data.setBitrateTotal(bitrate);
        data.setProtocol(protocol);
        data.setFileExtension(ext);
        data.setAudioDescription(isAudioDescription);
        if (metadata.getDateTimestamp() > -1) {
            data.setReleaseDate(metadata.getDateTimestamp());
        }
        data.setShow(metadata.getSubtitle());
        link.setFinalFileName(MediathekHelper.getMediathekFilename(link, data, true, true));
        link.setContentUrl(param.getCryptedUrl());
        if (this.contentID == null) {
            logger.log(new Exception("FixMe!"));
        } else {
            /* Needed for linkid / dupe check! */
            link.setProperty("itemId", this.contentID);
        }
        if (filesize > 0) {
            link.setDownloadSize(filesize);
            link.setAvailable(true);
        } else if (cfg.isFastLinkcheckEnabled()) {
            link.setAvailable(true);
        }
        final FilePackage fp = FilePackage.getInstance();
        fp.setName(metadata.getPackagename());
        link._setFilePackage(fp);
        qualitiesMap.put(qualityStringForQualitySelection, link);
        return link;
    }

    private void handleUserQualitySelection(List<String> selectedQualities) {
        /* We have to re-add the subtitle for the best quality if wished by the user */
        HashMap<String, DownloadLink> finalSelectedQualityMap = new HashMap<String, DownloadLink>();
        if (cfg.isGrabBESTEnabled()) {
            /* User wants BEST only */
            finalSelectedQualityMap = findBESTInsideGivenMap(this.foundQualitiesMap);
        } else {
            final boolean grabUnknownQualities = cfg.isAddUnknownQualitiesEnabled();
            boolean atLeastOneSelectedItemExists = false;
            for (final String quality : all_known_qualities) {
                if (selectedQualities.contains(quality) && foundQualitiesMap.containsKey(quality)) {
                    atLeastOneSelectedItemExists = true;
                }
            }
            if (!atLeastOneSelectedItemExists) {
                /* Only logger */
                logger.info("Possible user error: User selected only qualities which are not available --> Adding ALL");
            } else if (selectedQualities.size() == 0) {
                /* Errorhandling for bad user selection */
                logger.info("User selected no quality at all --> Adding ALL qualities instead");
                selectedQualities = all_known_qualities;
            }
            final Iterator<Entry<String, DownloadLink>> it = foundQualitiesMap.entrySet().iterator();
            while (it.hasNext()) {
                final Entry<String, DownloadLink> entry = it.next();
                final String quality = entry.getKey();
                final DownloadLink dl = entry.getValue();
                final boolean isUnknownQuality = !all_known_qualities.contains(quality);
                if (isUnknownQuality) {
                    logger.info("Found unknown quality: " + quality);
                    if (grabUnknownQualities) {
                        logger.info("Adding unknown quality: " + quality);
                        finalSelectedQualityMap.put(quality, dl);
                    }
                } else if (selectedQualities.contains(quality) || !atLeastOneSelectedItemExists) {
                    /* User has selected this particular quality OR we have to add it because user plugin settings were bad! */
                    finalSelectedQualityMap.put(quality, dl);
                }
            }
            /* Check if user maybe only wants the best quality inside his selected video qualities. */
            if (cfg.isOnlyBestVideoQualityOfSelectedQualitiesEnabled()) {
                finalSelectedQualityMap = findBESTInsideGivenMap(finalSelectedQualityMap);
            }
        }
        /* Finally add selected URLs */
        final Iterator<Entry<String, DownloadLink>> it = finalSelectedQualityMap.entrySet().iterator();
        while (it.hasNext()) {
            final Entry<String, DownloadLink> entry = it.next();
            final DownloadLink dl = entry.getValue();
            if (cfg.isGrabSubtitleEnabled() && !StringUtils.isEmpty(subtitleLink)) {
                final DownloadLink dl_subtitle = createDownloadlink(subtitleLink.replaceAll("https?://", getHost() + "decrypted://"));
                final MediathekProperties data_src = dl.bindData(MediathekProperties.class);
                final MediathekProperties data_subtitle = dl_subtitle.bindData(MediathekProperties.class);
                data_subtitle.setStreamingType("subtitle");
                data_subtitle.setSourceHost(data_src.getSourceHost());
                data_subtitle.setChannel(data_src.getChannel());
                data_subtitle.setProtocol(data_src.getProtocol() + "sub");
                data_subtitle.setResolution(data_src.getResolution());
                data_subtitle.setBitrateTotal(data_src.getBitrateTotal());
                data_subtitle.setTitle(data_src.getTitle());
                data_subtitle.setFileExtension("xml");
                if (data_src.getShow() != null) {
                    data_subtitle.setShow(data_src.getShow());
                }
                if (data_src.getReleaseDate() > 0) {
                    data_subtitle.setReleaseDate(data_src.getReleaseDate());
                }
                dl_subtitle.setAvailable(true);
                dl_subtitle.setFinalFileName(MediathekHelper.getMediathekFilename(dl_subtitle, data_subtitle, true, true));
                dl_subtitle.setProperty("itemId", dl.getProperty("itemId", null));
                dl_subtitle.setContentUrl(dl.getContentUrl());
                dl_subtitle._setFilePackage(dl.getFilePackage());
                decryptedLinks.add(dl_subtitle);
            }
            decryptedLinks.add(dl);
        }
        if (all_known_qualities.isEmpty()) {
            logger.info("Failed to find any quality at all");
        }
    }

    private boolean isUnsupportedProtocolDasersteVideo(final String directlink) {
        final boolean isUnsupported = directlink == null || !StringUtils.startsWithCaseInsensitive(directlink, "http") || StringUtils.endsWithCaseInsensitive(directlink, "manifest.f4m");
        return isUnsupported;
    }

    private HashMap<String, DownloadLink> findBESTInsideGivenMap(final HashMap<String, DownloadLink> map_with_all_qualities) {
        HashMap<String, DownloadLink> newMap = new HashMap<String, DownloadLink>();
        DownloadLink keep = null;
        if (map_with_all_qualities.size() > 0) {
            for (final String quality : all_known_qualities) {
                keep = map_with_all_qualities.get(quality);
                if (keep != null) {
                    newMap.put(quality, keep);
                    break;
                }
            }
        }
        if (newMap.isEmpty()) {
            /* Failover in case of bad user selection or general failure! */
            newMap = map_with_all_qualities;
        }
        return newMap;
    }

    /** Returns videos' width. Do not remove parts of this code without understanding them - this code is crucial for the plugin! */
    private int getWidth(final String width_str, final int width_given) {
        final int width;
        if (width_given > 0) {
            width = width_given;
        } else if (width_str != null) {
            if (width_str.matches("\\d+")) {
                width = Integer.parseInt(width_str);
            } else {
                /* Convert given quality-text to width. */
                if (width_str.equals("mn") || width_str.equals("sm")) {
                    width = 480;
                } else if (width_str.equals("hi") || width_str.equals("m") || width_str.equals("_ard") || width_str.equals("webm")) {
                    width = 512;
                } else if (width_str.equals("ln") || width_str.equals("ml")) {
                    width = 640;
                } else if (width_str.equals("lo") || width_str.equals("s") || width_str.equals("webs")) {
                    width = 320;
                } else if (width_str.equals("hq") || width_str.equals("l") || width_str.equals("webl")) {
                    width = 960;
                } else if (width_str.equals("webxl")) {
                    width = 1280;
                } else {
                    width = 0;
                }
            }
        } else {
            /* This should never happen! */
            width = 0;
        }
        return width;
    }

    /** Returns videos' height. Do not remove parts of thise code without understanding them - this code is crucial for the plugin! */
    private int getHeight(final String width_str, final int width, final int height_given) {
        final int height;
        if (height_given > 0) {
            height = height_given;
        } else if (width_str != null) {
            height = Integer.parseInt(convertWidthToHeight(width_str));
        } else {
            /* This should never happen! */
            height = 0;
        }
        return height;
    }

    private String convertWidthToHeight(final String width_str) {
        final String height;
        if (width_str == null) {
            height = "0";
        } else if (width_str.matches("\\d+")) {
            final int width = Integer.parseInt(width_str);
            if (width == 320) {
                height = "180";
            } else if (width == 480) {
                height = "270";
            } else if (width == 512) {
                height = "288";
            } else if (width == 640) {
                height = "360";
            } else if (width == 960) {
                height = "540";
            } else {
                height = Integer.toString(width / 2);
            }
        } else {
            /* Convert given quality-text to height. */
            if (width_str.equals("mn") || width_str.equals("sm")) {
                height = "270";
            } else if (width_str.equals("hi") || width_str.equals("m") || width_str.equals("_ard") || width_str.equals("webm")) {
                height = "288";
            } else if (width_str.equals("ln") || width_str.equals("ml")) {
                height = "360";
            } else if (width_str.equals("lo") || width_str.equals("s") || width_str.equals("webs")) {
                height = "180";
            } else if (width_str.equals("hq") || width_str.equals("l") || width_str.equals("webl")) {
                height = "540";
            } else if (width_str.equals("webxl")) {
                height = "540";
            } else {
                height = "0";
            }
        }
        return height;
    }

    /* Returns default videoBitrate for width values. */
    private long getDefaultBitrateForHeight(final int height) {
        final String height_str = Integer.toString(height);
        long bitrateVideo;
        if (heigth_to_bitrate.containsKey(height_str)) {
            bitrateVideo = heigth_to_bitrate.get(height_str);
        } else {
            /* Unknown or audio */
            bitrateVideo = 0;
        }
        return bitrateVideo;
    }

    /**
     * Given width may not always be exactly what we have in our quality selection but we need an exact value to make the user selection
     * work properly!
     */
    private int getHeightForQualitySelection(final int height) {
        final int heightelect;
        if (height > 0 && height <= 250) {
            heightelect = 180;
        } else if (height > 250 && height <= 272) {
            heightelect = 270;
        } else if (height > 272 && height <= 320) {
            heightelect = 280;
        } else if (height > 320 && height <= 400) {
            heightelect = 360;
        } else if (height > 400 && height < 576) {
            heightelect = 540;
        } else if (height >= 576 && height <= 600) {
            heightelect = 576;
        } else if (height > 600 && height <= 800) {
            heightelect = 720;
        } else {
            /* Either unknown quality or audio (0x0) */
            heightelect = height;
        }
        return heightelect;
    }

    /**
     * Given bandwidth may not always be exactly what we have in our quality selection but we need an exact value to make the user selection
     * work properly!
     */
    private long getBitrateForQualitySelection(final long bandwidth, final String directurl) {
        final long bandwidthselect;
        if (directurl != null && directurl.contains(".mp3")) {
            /* Audio --> There is usually only 1 bandwidth available so for our selection, we use the value 0. */
            bandwidthselect = 0;
        } else if (bandwidth > 0 && bandwidth <= 250000) {
            bandwidthselect = 189000;
        } else if (bandwidth > 250000 && bandwidth <= 350000) {
            /* lower 270 */
            bandwidthselect = 317000;
        } else if (bandwidth > 350000 && bandwidth <= 480000) {
            /* higher/normal 270 */
            bandwidthselect = 448000;
        } else if (bandwidth > 480000 && bandwidth <= 800000) {
            /* 280 */
            bandwidthselect = 605000;
        } else if (bandwidth > 800000 && bandwidth <= 1600000) {
            /* 360 */
            bandwidthselect = 1213000;
        } else if (bandwidth > 1600000 && bandwidth <= 2800000) {
            /* 540 */
            bandwidthselect = 1989000;
        } else if (bandwidth > 2800000 && bandwidth <= 4500000) {
            /* 720 */
            bandwidthselect = 3773000;
        } else if (bandwidth > 4500000 && bandwidth <= 10000000) {
            /* 1080 */
            bandwidthselect = 6666000;
        } else {
            /* Probably unknown quality */
            bandwidthselect = bandwidth;
        }
        return bandwidthselect;
    }

    public class ArdMetadata {
        private String title         = null;
        private String subtitle      = null;
        private String channel       = null;
        private String contentID     = null;
        private long   dateTimestamp = -1;

        protected String getTitle() {
            return title;
        }

        protected void setTitle(String title) {
            this.title = title;
        }

        protected String getSubtitle() {
            return subtitle;
        }

        protected void setSubtitle(String subtitle) {
            this.subtitle = subtitle;
        }

        protected String getChannel() {
            return channel;
        }

        protected void setChannel(String channel) {
            this.channel = channel;
        }

        protected String getContentID() {
            return contentID;
        }

        protected void setDateTimestamp(final long dateTimestamp) {
            this.dateTimestamp = dateTimestamp;
        }

        protected long getDateTimestamp() {
            return dateTimestamp;
        }

        public ArdMetadata() {
        }

        public ArdMetadata(final String title) {
            this.title = title;
        }

        protected void setContentID(String contentID) {
            this.contentID = contentID;
        }

        /** Returns date in format yyyy-MM-dd */
        protected String getFormattedDate() {
            if (this.dateTimestamp == -1) {
                return null;
            } else {
                return new SimpleDateFormat("yyyy-MM-dd").format(new Date(this.dateTimestamp));
            }
        }

        protected String getPackagename() {
            final String dateFormatted = this.getFormattedDate();
            if (dateFormatted != null) {
                return dateFormatted + " - " + this.title;
            } else {
                return this.title;
            }
        }
    }

    // private String getXML(final String parameter) {
    // return getXML(this.br.toString(), parameter);
    // }
    private String getXML(final String source, final String parameter) {
        return new Regex(source, "<" + parameter + "[^<]*?>([^<>]*?)</" + parameter + ">").getMatch(0);
    }

    public static final String correctRegionString(final String input) {
        String output;
        if (input.equals("de")) {
            output = "de";
        } else {
            output = "weltweit";
        }
        return output;
    }

    private long getDateMilliseconds(String input) {
        if (input == null) {
            return -1;
        }
        final long date_milliseconds;
        if (input.matches("\\d{4}\\-\\d{2}\\-\\d{2}")) {
            date_milliseconds = TimeFormatter.getMilliSeconds(input, "yyyy-MM-dd", Locale.GERMAN);
        } else if (input.matches("\\d{2}\\.\\d{2}\\.\\d{4} \\d{2}:\\d{2}")) {
            date_milliseconds = TimeFormatter.getMilliSeconds(input, "dd.MM.yyyy HH:mm", Locale.GERMAN);
        } else {
            /* 2015-06-23T20:15:00.000+02:00 --> 2015-06-23T20:15:00.000+0200 */
            input = new Regex(input, "^(\\d{4}\\-\\d{2}\\-\\d{2})").getMatch(0);
            date_milliseconds = TimeFormatter.getMilliSeconds(input, "yyyy-MM-dd", Locale.GERMAN);
        }
        return date_milliseconds;
    }

    public boolean hasCaptcha(final CryptedLink link, final jd.plugins.Account acc) {
        return false;
    }
}
