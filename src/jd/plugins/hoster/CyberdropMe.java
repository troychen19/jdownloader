package jd.plugins.hoster;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.appwork.utils.StringUtils;

import jd.PluginWrapper;
import jd.http.Browser;
import jd.http.URLConnectionAdapter;
import jd.plugins.Account;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.Plugin;
import jd.plugins.PluginDependencies;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;
import jd.plugins.decrypter.CyberdropMeAlbum;

@HostPlugin(revision = "$Revision$", interfaceVersion = 3, names = {}, urls = {})
@PluginDependencies(dependencies = { CyberdropMeAlbum.class })
public class CyberdropMe extends PluginForHost {
    public CyberdropMe(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public String getAGBLink() {
        return "https://" + CyberdropMeAlbum.MAIN_CYBERDROP_DOMAIN + "/faq";
    }

    public static List<String[]> getPluginDomains() {
        return CyberdropMeAlbum.getPluginDomains();
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
            ret.add("");
        }
        return ret.toArray(new String[0]);
    }

    @Override
    public String rewriteHost(final String host) {
        /* This host is frequently changing its' main domain. */
        return this.rewriteHost(getPluginDomains(), host);
    }

    private int getMaxChunks(final Account account) {
        return 1;
    }

    /* Don't touch the following! */
    private static Map<String, AtomicInteger> freeRunning = new HashMap<String, AtomicInteger>();

    protected AtomicInteger getFreeRunning() {
        synchronized (freeRunning) {
            AtomicInteger ret = freeRunning.get(getHost());
            if (ret == null) {
                ret = new AtomicInteger(0);
                freeRunning.put(getHost(), ret);
            }
            return ret;
        }
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        final int max = getMaxSimultaneousFreeAnonymousDownloads();
        if (max == -1 || true) {
            return -1;
        } else {
            /* Start downloads sequentially */
            final int running = getFreeRunning().get();
            final int ret = Math.min(running + 1, max);
            return ret;
        }
    }

    private int getMaxSimultaneousFreeAnonymousDownloads() {
        return -1;
    }
    // @Override
    // public PluginForHost assignPlugin(PluginFinder pluginFinder, DownloadLink link) {
    // final String pluginHost = getHost();
    // if (pluginFinder != null && CyberdropMe.class.equals(getClass()) && !pluginHost.equals(link.getHost())) {
    // final String url = link.getPluginPatternMatcher();
    // final boolean checkHostFlag;
    // if (CyberdropMeAlbum.MAIN_CYBERDROP_DOMAIN.equals(pluginHost) && url.matches(CyberdropMeAlbum.TYPE_FS)) {
    // checkHostFlag = true;
    // } else if (CyberdropMeAlbum.MAIN_BUNKR_DOMAIN.equals(pluginHost) && (url.matches(CyberdropMeAlbum.TYPE_CDN) ||
    // url.matches(CyberdropMeAlbum.TYPE_STREAM))) {
    // checkHostFlag = true;
    // } else {
    // checkHostFlag = false;
    // return null;
    // }
    // if (checkHostFlag) {
    // final String host = Browser.getHost(url);
    // for (String siteSupportedName : siteSupportedNames()) {
    // if (StringUtils.equalsIgnoreCase(siteSupportedName, host)) {
    // return super.assignPlugin(pluginFinder, link);
    // }
    // }
    // }
    // return null;
    // } else {
    // return super.assignPlugin(pluginFinder, link);
    // }
    // }
    //
    // @Override
    // public LazyPlugin.FEATURE[] getFeatures() {
    // if (CyberdropMeAlbum.MAIN_BUNKR_DOMAIN.equals(getHost()) || CyberdropMeAlbum.MAIN_CYBERDROP_DOMAIN.equals(getHost())) {
    // return new LazyPlugin.FEATURE[] { LazyPlugin.FEATURE.ASSIGN_PLUGIN };
    // } else {
    // return new LazyPlugin.FEATURE[0];
    // }
    // }

    private String getContentURL(final DownloadLink link) {
        final String url = link.getPluginPatternMatcher();
        /* Do some domain corrections */
        final String newURL = url.replaceFirst("(?i)cyberdrop\\.[a-z]+/", CyberdropMeAlbum.MAIN_CYBERDROP_DOMAIN + "/");
        return newURL;
    }

    private final String PROPERTY_ALTERNATIVE_DIRECTURL = "alternative_directurl";

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws Exception {
        return requestFileInformation(link, false);
    }

    public static String findDirectURL(final Plugin plugin, final Browser br) {
        String directurl = br.getRegex("link\\.href\\s*=\\s*\"(https?://[^\"]+)\"").getMatch(0);
        if (directurl == null) {
            directurl = br.getRegex("(?i)href\\s*=\\s*\"(https?://[^\"]+)[^>]*>\\s*Download").getMatch(0);
            if (directurl == null) {
                /* Video stream (URL is usually the same as downloadurl) */
                directurl = br.getRegex("<source src\\s*=\\s*\"(https?://[^\"]+)\"[^>]*type=.video/mp4").getMatch(0);
            }
        }
        return directurl;
    }

    private AvailableStatus requestFileInformation(final DownloadLink link, final boolean isDownload) throws Exception {
        this.setBrowserExclusive();
        br.setFollowRedirects(true);
        final boolean attemptToUseLastStoredAlternativeDirecturl = false;
        String directurl = link.getStringProperty(PROPERTY_ALTERNATIVE_DIRECTURL);
        if (directurl == null || !attemptToUseLastStoredAlternativeDirecturl) {
            directurl = this.getContentURL(link);
        }
        final String filenameFromURL = Plugin.getFileNameFromURL(directurl);
        if (!link.isNameSet() && filenameFromURL != null) {
            link.setName(filenameFromURL);
        }
        final String containerURL = link.getContainerUrl();
        if (containerURL != null) {
            br.getHeaders().put("Referer", containerURL);
        }
        URLConnectionAdapter con = null;
        try {
            if (isDownload) {
                dl = jd.plugins.BrowserAdapter.openDownload(br, link, directurl, true, this.getMaxChunks(null));
                con = dl.getConnection();
            } else {
                con = br.openGetConnection(directurl);
            }
            try {
                handleConnectionErrors(br, con);
            } catch (final PluginException e) {
                /* E.g. cdn.bunkr.ru -> bunkr.su/v/... -> Try to find fresh directurl */
                logger.info("Directurl did not lead to downloadable content -> Looking for freh directurl");
                final String alternativeFreshDirecturl = findDirectURL(this, br);
                if (alternativeFreshDirecturl == null) {
                    logger.info("Failed to find fresh directurl");
                    throw e;
                } else if (StringUtils.equals(directurl, alternativeFreshDirecturl)) {
                    logger.info("Fresh directurl is the same as old one -> Retrying doesn't make any sense");
                    throw e;
                } else {
                    logger.info("Trying again with fresh directurl: " + alternativeFreshDirecturl);
                    if (isDownload) {
                        dl = jd.plugins.BrowserAdapter.openDownload(br, link, alternativeFreshDirecturl, true, this.getMaxChunks(null));
                        con = dl.getConnection();
                    } else {
                        con = br.openGetConnection(alternativeFreshDirecturl);
                    }
                    handleConnectionErrors(br, con);
                    logger.info("Fresh directurl is working: " + alternativeFreshDirecturl);
                    link.setProperty(PROPERTY_ALTERNATIVE_DIRECTURL, alternativeFreshDirecturl);
                }
            }
            if (con.getCompleteContentLength() > 0) {
                link.setVerifiedFileSize(con.getCompleteContentLength());
            }
            final String filenameFromHeader = Plugin.getFileNameFromHeader(con);
            if (!StringUtils.isEmpty(filenameFromHeader)) {
                link.setFinalFileName(filenameFromHeader);
            }
        } finally {
            if (!isDownload) {
                try {
                    con.disconnect();
                } catch (final Throwable e) {
                }
            }
        }
        return AvailableStatus.TRUE;
    }

    @Override
    public void handleFree(final DownloadLink link) throws Exception {
        requestFileInformation(link, true);
        if (this.dl == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        /* add a download slot */
        controlMaxFreeDownloads(null, link, +1);
        try {
            /* start the dl */
            dl.startDownload();
        } finally {
            /* remove download slot */
            controlMaxFreeDownloads(null, link, -1);
        }
    }

    protected void controlMaxFreeDownloads(final Account account, final DownloadLink link, final int num) {
        if (account == null) {
            final AtomicInteger freeRunning = getFreeRunning();
            synchronized (freeRunning) {
                final int before = freeRunning.get();
                final int after = before + num;
                freeRunning.set(after);
                logger.info("freeRunning(" + link.getName() + ")|max:" + getMaxSimultanFreeDownloadNum() + "|before:" + before + "|after:" + after + "|num:" + num);
            }
        }
    }

    private void handleConnectionErrors(final Browser br, final URLConnectionAdapter con) throws PluginException, IOException {
        if (!this.looksLikeDownloadableContent(con)) {
            br.followConnection(true);
            if (con.getResponseCode() == 403) {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 403", 10 * 60 * 1000l);
            } else if (con.getResponseCode() == 404) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            } else if (con.getResponseCode() == 503) {
                throw new PluginException(LinkStatus.ERROR_HOSTER_TEMPORARILY_UNAVAILABLE, "Error 503 too many connections", 1 * 60 * 1000l);
            } else {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "File broken or temporarily unavailable");
            }
        }
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
    }
}
