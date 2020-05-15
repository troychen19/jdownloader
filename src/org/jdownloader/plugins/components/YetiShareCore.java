package org.jdownloader.plugins.components;

//jDownloader - Downloadmanager
//Copyright (C) 2016  JD-Team support@jdownloader.org
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
import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import org.appwork.utils.StringUtils;
import org.appwork.utils.formatter.SizeFormatter;
import org.appwork.utils.formatter.TimeFormatter;
import org.appwork.utils.parser.UrlQuery;
import org.jdownloader.captcha.v2.challenge.recaptcha.v2.CaptchaHelperHostPluginRecaptchaV2;
import org.jdownloader.scripting.JavaScriptEngineFactory;

import jd.PluginWrapper;
import jd.config.Property;
import jd.http.Browser;
import jd.http.Cookies;
import jd.http.URLConnectionAdapter;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.parser.html.Form;
import jd.parser.html.Form.MethodType;
import jd.plugins.Account;
import jd.plugins.Account.AccountType;
import jd.plugins.AccountInfo;
import jd.plugins.AccountRequiredException;
import jd.plugins.AccountUnavailableException;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.Plugin;
import jd.plugins.PluginException;
import jd.plugins.components.PluginJSonUtils;
import jd.plugins.components.SiteType.SiteTemplate;
import jd.plugins.components.UserAgents;

@HostPlugin(revision = "$Revision$", interfaceVersion = 2, names = {}, urls = {})
public class YetiShareCore extends antiDDoSForHost {
    public YetiShareCore(PluginWrapper wrapper) {
        super(wrapper);
        // this.enablePremium(getPurchasePremiumURL());
    }

    // public static List<String[]> getPluginDomains() {
    // final List<String[]> ret = new ArrayList<String[]>();
    // // each entry in List<String[]> will result in one PluginForHost, Plugin.getHost() will return String[0]->main domain
    // ret.add(new String[] { "testhost.com" });
    // return ret;
    // }
    //
    // public static String[] getAnnotationNames() {
    // return buildAnnotationNames(getPluginDomains());
    // }
    //
    // @Override
    // public String[] siteSupportedNames() {
    // return buildSupportedNames(getPluginDomains());
    // }
    //
    // public static String[] getAnnotationUrls() {
    // final List<String[]> pluginDomains = getPluginDomains();
    // final List<String> ret = new ArrayList<String>();
    // for (final String[] domains : pluginDomains) {
    // ret.add("https?://(?:www\\.)?" + buildHostsPatternPart(domains) + YetiShareCore.getDefaultAnnotationPatternPart());
    // }
    // return ret.toArray(new String[0]);
    // }
    // @Override
    // public String rewriteHost(String host) {
    // return this.rewriteHost(getPluginDomains(), host, new String[0]);
    // }
    public static final String getDefaultAnnotationPatternPart() {
        return "/(?!folder)[A-Za-z0-9]+(?:/[^/<>]+)?";
    }

    public static String[] buildAnnotationUrls(final List<String[]> pluginDomains) {
        final List<String> ret = new ArrayList<String>();
        for (final String[] domains : pluginDomains) {
            ret.add("https?://(?:www\\.)?" + YetiShareCore.buildHostsPatternPart(domains) + YetiShareCore.getDefaultAnnotationPatternPart());
        }
        return ret.toArray(new String[0]);
    }

    /**
     * For sites which use this script: http://www.yetishare.com/<br />
     * YetiShareCore Version 2.0.1.0-psp<br />
     * mods: see overridden functions in host plugins<br />
     * limit-info:<br />
     * captchatype: null, solvemedia, reCaptchaV2<br />
     * Another alternative method of linkchecking (displays filename only): host.tld/<fid>~s (statistics) 2019-06-12: Consider adding API
     * support: https://fhscript.com/admin/api_documentation.php#account-info Examples for websites which have the API enabled (but not
     * necessarily unlocked for all users, usually only special-uploaders): crazyshare.cc, easylinkz.net, freefile.me <br />
     * 2020-03-30: I failed to make ANY successful API tests. 100% of all websites which support this API are running a broken version!
     */
    @Override
    public String getAGBLink() {
        return this.getMainPage() + "/terms.html";
    }

    public String getPurchasePremiumURL() {
        return this.getMainPage() + "/register.html";
    }

    // private static final boolean enable_regex_stream_url = true;
    private static AtomicReference<String> agent = new AtomicReference<String>(null);

    @Override
    public void correctDownloadLink(final DownloadLink link) {
        /* link cleanup, but respect users protocol choosing or forced protocol */
        final String fid = getFUIDFromURL(link);
        final String protocol;
        if (supports_https()) {
            protocol = "https";
        } else {
            protocol = "http";
        }
        link.setPluginPatternMatcher(String.format("%s://%s/%s", protocol, this.getHost(), fid));
        link.setLinkID(this.getHost() + "://" + fid);
    }

    /**
     * Returns whether resume is supported or not for current download mode based on account availability and account type. <br />
     * Override this function to set resume settings!
     */
    @Override
    public boolean isResumeable(final DownloadLink link, final Account account) {
        if (account != null && account.getType() == AccountType.FREE) {
            /* Free Account */
            return true;
        } else if (account != null && account.getType() == AccountType.PREMIUM) {
            /* Premium account */
            return true;
        } else {
            /* Free(anonymous) and unknown account type */
            return true;
        }
    }

    /**
     * Returns how many max. chunks per file are allowed for current download mode based on account availability and account type. <br />
     * Override this function to set chunks settings!
     */
    public int getMaxChunks(final Account account) {
        if (account != null && account.getType() == AccountType.FREE) {
            /* Free Account */
            return 0;
        } else if (account != null && account.getType() == AccountType.PREMIUM) {
            /* Premium account */
            return 0;
        } else {
            /* Free(anonymous) and unknown account type */
            return 0;
        }
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return -1;
    }

    public int getMaxSimultaneousFreeAccountDownloads() {
        return -1;
    }

    @Override
    public int getMaxSimultanPremiumDownloadNum() {
        return -1;
    }

    /** Returns direct-link-property-String for current download mode based on account availibility and account type. */
    protected static String getDownloadModeDirectlinkProperty(final Account account) {
        if (account != null && account.getType() == AccountType.FREE) {
            /* Free Account */
            return "freelink2";
        } else if (account != null && account.getType() == AccountType.PREMIUM) {
            /* Premium account */
            return "premlink";
        } else {
            /* Free(anonymous) and unknown account type */
            return "freelink";
        }
    }

    /**
     * @return true: Website supports https and plugin will prefer https. <br />
     *         false: Website does not support https - plugin will avoid https. <br />
     *         default: true
     */
    public boolean supports_https() {
        return true;
    }

    /**
     * @return true: Implies that website will show filename & filesize via website.tld/<fuid>~i <br />
     *         Most YetiShare websites support this kind of linkcheck! </br>
     *         false: Implies that website does NOT show filename & filesize via website.tld/<fuid>~i. <br />
     *         default: true
     */
    public boolean supports_availablecheck_over_info_page(DownloadLink link) {
        return true;
    }

    /** @return default: true */
    protected boolean supports_availablecheck_filesize_html() {
        return true;
    }

    /**
     * Most YetiShare configurations will use 'www.' by default but will work with- and without 'www.' and will let the user decide (= no
     * redirect happens when we use 'www.' although it is not used by them by default). <br />
     *
     * @return true: Implies that website requires 'www.' in all URLs. <br />
     *         false: Implies that website does NOT require 'www.' in all URLs. <br />
     *         default: true
     */
    public boolean requires_WWW() {
        return true;
    }

    /**
     * @return true: Use random User-Agent. <br />
     *         false: Use Browsers' default User-Agent. <br />
     *         default: false
     */
    public boolean enable_random_user_agent() {
        return false;
    }

    /**
     * <b> Enabling this may lead to at least one additional website-request! </b><br />
     * TODO: 2019-02-20: Find website which supports video streaming! --> 2020-03-25: vidload.net Deprecated since 2020-05-14 as this has
     * never been used so far!
     *
     * @return true: Implies that website supports embedding videos. <br />
     *         false: Implies that website does NOT support embedding videos. <br />
     *         default: false
     */
    @Deprecated
    protected boolean supports_embed_stream_download() {
        return false;
    }

    /** See fetchAccountInfoAPI */
    protected boolean supports_api() {
        return false;
    }

    /**
     * Enforces old, non-ajax login-method. </br>
     * This is only rarely needed e.g. filemia.com </br>
     * default = false
     */
    @Deprecated
    protected boolean enforce_old_login_method() {
        return false;
    }

    /** Returns empty StringArray for filename, filesize, [more information in the future?] */
    protected String[] getFileInfoArray() {
        return new String[2];
    }

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws Exception {
        return requestFileInformation(link, null, false);
    }

    public AvailableStatus requestFileInformation(final DownloadLink link, final Account account, final boolean isDownload) throws Exception {
        setWeakFilename(link);
        br.setFollowRedirects(true);
        prepBrowser(this.br);
        final String fallback_filename = this.getFallbackFilename(link);
        final String[] fileInfo = getFileInfoArray();
        try {
            if (supports_availablecheck_over_info_page(link)) {
                getPage(link.getPluginPatternMatcher() + "~i");
                /* Offline check is unsafe which is why we need to check for other errors first! */
                try {
                    this.checkErrors(link, account);
                } catch (final PluginException e) {
                    if (isDownload || e.getLinkStatus() == LinkStatus.ERROR_FILE_NOT_FOUND) {
                        throw e;
                    } else {
                        logger.log(e);
                        return AvailableStatus.TRUE;
                    }
                }
                /* Offline errorhandling */
                if (!br.getURL().contains("~i") || br.getHttpConnection().getResponseCode() == 404) {
                    throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                }
            } else {
                getPage(link.getPluginPatternMatcher());
                /* Offline check is very unsafe which is why we need to check for other errors first! */
                try {
                    this.checkErrors(link, account);
                } catch (final PluginException e) {
                    if (isDownload || e.getLinkStatus() == LinkStatus.ERROR_FILE_NOT_FOUND) {
                        throw e;
                    } else {
                        logger.log(e);
                        /* File is probably online but another error happened --> Do not throw it during availablecheck! */
                        return AvailableStatus.TRUE;
                    }
                }
                if (isOfflineWebsite(link)) {
                    throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                }
            }
            scanInfo(link, fileInfo);
            if (StringUtils.isEmpty(fileInfo[0])) {
                /* Final fallback - this should never happen! */
                fileInfo[0] = fallback_filename;
            }
            link.setName(fileInfo[0]);
            if (fileInfo[1] != null) {
                link.setDownloadSize(SizeFormatter.getSize(Encoding.htmlDecode(fileInfo[1].replace(",", ""))));
            }
        } finally {
            /* Something went seriously wrong? Use fallback filename! */
            if (StringUtils.isEmpty(fileInfo[0])) {
                link.setName(getFallbackFilename(link));
            }
        }
        return AvailableStatus.TRUE;
    }

    /**
     * Tries to find filename and filesize inside html. On Override, make sure to first use your special RegExes e.g. fileInfo[0]="bla",
     * THEN, if needed, call super.scanInfo(fileInfo). <br />
     * fileInfo[0] = filename, fileInfo[1] = filesize
     */
    public String[] scanInfo(final DownloadLink link, final String[] fileInfo) {
        if (supports_availablecheck_over_info_page(link)) {
            final List<String> fileNameCandidates = new ArrayList<String>();
            if (!StringUtils.isEmpty(fileInfo[0])) {
                fileNameCandidates.add(Encoding.htmlDecode(fileInfo[0]).trim());
            }
            final String[] tableData = this.br.getRegex("class=\"responsiveInfoTable\">([^<>\"/]*?)<").getColumn(0);
            /* Sometimes we get crippled results with the 2nd RegEx so use this one first */
            {
                String lang_str_information = PluginJSonUtils.getJson(br, "file_information_left_description");
                if (StringUtils.isEmpty(lang_str_information)) {
                    /* Fallback to English */
                    lang_str_information = "Information about";
                }
                String name = this.br.getRegex("data\\-animation\\-delay=\"\\d+\"\\s*>\\s*" + Pattern.quote(lang_str_information) + "\\s*([^<>\"]*?)\\s*</div>").getMatch(0);
                if (name == null) {
                    /* Wider attempt */
                    name = this.br.getRegex(">\\s*" + Pattern.quote(lang_str_information) + "\\s*([^<>\"]+)<").getMatch(0);
                }
                name = name != null ? Encoding.htmlOnlyDecode(name).trim() : null;
                if (name != null && !fileNameCandidates.contains(name)) {
                    fileNameCandidates.add(name);
                }
            }
            {
                /*
                 * "Information about"-filename-trait without the animation(delay). E.g. easylinkz.net - sometimes it may also happen that
                 * the 'Filename:' is empty and the filename is only present at this place!
                 */
                String name = this.br.getRegex("<meta\\s*name\\s*=\\s*\"description[^\"]*\"\\s*content\\s*=\\s*\"\\s*(?:Information about|informacje o)\\s*([^<>\"]+)\\s*\"").getMatch(0);
                name = name != null ? Encoding.htmlOnlyDecode(name).trim() : null;
                if (name != null && !fileNameCandidates.contains(name)) {
                    fileNameCandidates.add(name);
                }
            }
            {
                /*
                 * "Information about"-filename-trait without the animation(delay). E.g. easylinkz.net - sometimes it may also happen that
                 * the 'Filename:' is empty and the filename is only present at this place!
                 */
                String name = this.br.getRegex("class\\s*=\\s*\"description\\-1[^\"]*\"\\s*>\\s*(?:Information about|informacje o)\\s*([^<>\"]+)\\s*<").getMatch(0);
                name = name != null ? Encoding.htmlOnlyDecode(name).trim() : null;
                if (name != null && !fileNameCandidates.contains(name)) {
                    fileNameCandidates.add(name);
                }
            }
            {
                String name = fileInfo[0] = this.br.getRegex("(?:Filename|Dateiname|اسم الملف|Nome|Dosya Adı|Nazwa Pliku)\\s*:[\t\n\r ]*?</td>[\t\n\r ]*?<td(?: class=\"responsiveInfoTable\")?>\\s*([^<>\"]*?)\\s*<").getMatch(0);
                name = name != null ? Encoding.htmlOnlyDecode(name).trim() : null;
                if (name != null && !fileNameCandidates.contains(name)) {
                    fileNameCandidates.add(name);
                }
            }
            if (StringUtils.isEmpty(fileInfo[1])) {
                fileInfo[1] = br.getRegex("(?:Filesize|Dateigröße|حجم الملف|Tamanho|Boyut|Rozmiar Pliku)\\s*:\\s*</td>\\s*?<td(?:\\s*class=\"responsiveInfoTable\")?>\\s*([^<>\"]*?)\\s*<").getMatch(0);
            }
            try {
                /* Language-independant attempt ... */
                if (StringUtils.isEmpty(fileInfo[0]) && tableData.length > 0) {
                    String name = tableData[0];
                    name = name != null ? Encoding.htmlOnlyDecode(name).trim() : null;
                    if (StringUtils.isNotEmpty(name) && !fileNameCandidates.contains(name)) {
                        fileNameCandidates.add(name);
                    }
                }
                if (StringUtils.isEmpty(fileInfo[1]) && tableData.length > 1) {
                    fileInfo[1] = tableData[1];
                }
            } catch (final Throwable e) {
                logger.log(e);
            }
            String bestName = null;
            for (final String fileNameCandidate : fileNameCandidates) {
                if (StringUtils.isEmpty(fileNameCandidate)) {
                    continue;
                } else if (bestName == null) {
                    bestName = fileNameCandidate;
                } else if (bestName.contains("...") && !fileNameCandidate.contains("...")) {
                    bestName = fileNameCandidate;
                } else if (bestName.length() < fileNameCandidate.length()) {
                    bestName = fileNameCandidate;
                }
            }
            fileInfo[0] = bestName;
        } else {
            final Regex fInfo = br.getRegex("<strong>([^<>\"]*?) \\((\\d+(?:,\\d+)?(?:\\.\\d+)? (?:TB|GB|MB|KB|B))\\)<");
            if (StringUtils.isEmpty(fileInfo[0])) {
                fileInfo[0] = fInfo.getMatch(0);
            }
            if (supports_availablecheck_filesize_html()) {
                if (StringUtils.isEmpty(fileInfo[1])) {
                    fileInfo[1] = fInfo.getMatch(1);
                }
                /* Generic failover */
                if (StringUtils.isEmpty(fileInfo[1])) {
                    // sync with XFileSharingProBasic.scanInfo- Generic failover
                    fileInfo[1] = br.getRegex("(?:>\\s*|\\(\\s*|\"\\s*|\\[\\s*|\\s+)([0-9\\.]+(?:\\s+|\\&nbsp;)?(TB|GB|MB|KB)(?!ps|/s|\\s*Storage|\\s*Disk|\\s*Space))").getMatch(0);
                }
            }
        }
        return fileInfo;
    }

    @Override
    public void handleFree(final DownloadLink downloadLink) throws Exception, PluginException {
        requestFileInformation(downloadLink, null, true);
        handleDownload(downloadLink, null);
    }

    public void handleDownload(final DownloadLink link, final Account account) throws Exception, PluginException {
        final boolean resume = this.isResumeable(link, account);
        final int maxchunks = this.getMaxChunks(account);
        final String directlinkproperty = getDownloadModeDirectlinkProperty(account);
        String continue_link = null;
        boolean captcha = false;
        boolean success = false;
        long timeBeforeCaptchaInput;
        continue_link = checkDirectLink(link, account);
        if (StringUtils.isEmpty(continue_link) && this.dl == null) {
            br.setFollowRedirects(false);
            // if (supports_embed_stream_download()) {
            // try {
            // final Browser br2 = this.br.cloneBrowser();
            // getPage(br2, String.format("/embed/u=%s/", this.getFID(link)));
            // continue_link = this.getStreamUrl(br2);
            // } catch (final BrowserException e) {
            // }
            // }
            if (supports_availablecheck_over_info_page(link)) {
                getPage(link.getPluginPatternMatcher());
                /* For premium mode, we might get our final downloadurl here already. */
                while (true) {
                    final String redirect = this.br.getRedirectLocation();
                    if (redirect != null) {
                        if (isDownloadlink(redirect)) {
                            continue_link = redirect;
                            break;
                        } else {
                            br.followRedirect();
                        }
                    } else {
                        continue_link = getContinueLink();
                        break;
                    }
                }
            }
            if (StringUtils.isEmpty(continue_link)) {
                checkErrors(link, account);
                continue_link = getContinueLink();
            }
            /* Passwords are usually before waittime. */
            handlePassword(link);
            /* Handle up to x pre-download pages before the (eventually existing) captcha */
            final int startValue = 0;
            /* loopLog holds information about the continue_link of each loop so afterwards we get an overview via logger */
            String loopLog = continue_link;
            for (int i = startValue; i <= 5; i++) {
                logger.info("Handling pre-download page #" + (i + 1));
                timeBeforeCaptchaInput = System.currentTimeMillis();
                if (i > startValue) {
                    loopLog += " --> " + continue_link;
                }
                if (isDownloadlink(continue_link)) {
                    /*
                     * If we already found a downloadlink let's try to download it because html can still contain captcha html --> We don't
                     * need a captcha in this case/loop/pass for sure! E.g. host '3rbup.com'.
                     */
                    waitTime(link, timeBeforeCaptchaInput);
                    dl = jd.plugins.BrowserAdapter.openDownload(br, link, continue_link, resume, maxchunks);
                } else {
                    final Form continueform = getContinueForm(i, continue_link);
                    if (i == startValue && continueform == null) {
                        logger.info("No continue_form/continue_link available, plugin broken");
                        checkErrorsLastResort(link, account);
                    } else if (continueform == null) {
                        logger.info("No continue_form/continue_link available, stepping out of pre-download loop");
                        break;
                    } else {
                        logger.info("Found continue_form/continue_link, continuing...");
                    }
                    final String rcID = br.getRegex("recaptcha/api/noscript\\?k=([^<>\"]*?)\"").getMatch(0);
                    if (br.containsHTML("data\\-sitekey=|g\\-recaptcha\\'")) {
                        loopLog += " --> reCaptchaV2";
                        captcha = true;
                        final String recaptchaV2Response = new CaptchaHelperHostPluginRecaptchaV2(this, br).getToken();
                        success = true;
                        waitTime(link, timeBeforeCaptchaInput);
                        continueform.put("capcode", "false");
                        continueform.put("g-recaptcha-response", recaptchaV2Response);
                        continueform.setMethod(MethodType.POST);
                        dl = jd.plugins.BrowserAdapter.openDownload(br, link, continueform, resume, maxchunks);
                    } else if (rcID != null) {
                        /* Dead end! */
                        captcha = true;
                        success = false;
                        throw new PluginException(LinkStatus.ERROR_FATAL, "Website uses reCaptchaV1 which has been shut down by Google. Contact website owner!");
                    } else if (br.containsHTML("solvemedia\\.com/papi/")) {
                        loopLog += " --> SolvemediaCaptcha";
                        captcha = true;
                        success = false;
                        logger.info("Detected captcha method \"solvemedia\" for this host");
                        final org.jdownloader.captcha.v2.challenge.solvemedia.SolveMedia sm = new org.jdownloader.captcha.v2.challenge.solvemedia.SolveMedia(br);
                        if (br.containsHTML("api\\-secure\\.solvemedia\\.com/")) {
                            sm.setSecure(true);
                        }
                        File cf = null;
                        try {
                            cf = sm.downloadCaptcha(getLocalCaptchaFile());
                        } catch (final Exception e) {
                            if (org.jdownloader.captcha.v2.challenge.solvemedia.SolveMedia.FAIL_CAUSE_CKEY_MISSING.equals(e.getMessage())) {
                                throw new PluginException(LinkStatus.ERROR_FATAL, "Host side solvemedia.com captcha error - please contact the " + this.getHost() + " support", -1, e);
                            } else {
                                throw e;
                            }
                        }
                        final String code = getCaptchaCode("solvemedia", cf, link);
                        final String chid = sm.getChallenge(code);
                        waitTime(link, timeBeforeCaptchaInput);
                        continueform.put("adcopy_challenge", Encoding.urlEncode(chid));
                        continueform.put("adcopy_response", Encoding.urlEncode(code));
                        continueform.setMethod(MethodType.POST);
                        dl = jd.plugins.BrowserAdapter.openDownload(br, link, continueform, resume, maxchunks);
                    } else if (continueform != null && continueform.getMethod() == MethodType.POST) {
                        loopLog += " --> Form_POST";
                        success = true;
                        waitTime(link, timeBeforeCaptchaInput);
                        /* Use URL instead of Form - it is all we need! */
                        dl = jd.plugins.BrowserAdapter.openDownload(br, link, continueform, resume, maxchunks);
                    } else {
                        if (continue_link == null) {
                            checkErrors(link, account);
                            logger.warning("Failed to find continue_link");
                            checkErrorsLastResort(link, account);
                        }
                        br.setFollowRedirects(false);
                        waitTime(link, timeBeforeCaptchaInput);
                        getPage(continue_link);
                        /* Loop to handle redirects */
                        while (true) {
                            final String redirect = this.br.getRedirectLocation();
                            if (redirect != null) {
                                if (isDownloadlink(redirect)) {
                                    continue_link = redirect;
                                    break;
                                } else {
                                    br.followRedirect();
                                }
                            } else {
                                continue_link = this.getContinueLink();
                                break;
                            }
                        }
                        br.setFollowRedirects(true);
                        continue;
                    }
                }
                checkResponseCodeErrors(dl.getConnection());
                if (dl.getConnection().isContentDisposition()) {
                    success = true;
                    loopLog += " --> " + dl.getConnection().getURL().toString();
                    break;
                }
                br.followConnection();
                /* Get new continue_link for the next run */
                continue_link = getContinueLink();
                checkErrors(link, account);
                if (captcha && br.containsHTML("(api\\.recaptcha\\.net|google\\.com/recaptcha/api/)")) {
                    logger.info("Wrong captcha");
                    continue;
                }
            }
            logger.info("loopLog: " + loopLog);
        }
        /*
         * Save directurl before download-attempt as it should be valid even if it e.g. fails because of server issue 503 (= too many
         * connections) --> Should work fine after the next try.
         */
        if (dl == null) {
            checkErrors(link, account);
            checkErrorsLastResort(link, account);
        }
        link.setProperty(directlinkproperty, dl.getConnection().getURL().toString());
        checkResponseCodeErrors(dl.getConnection());
        if (!dl.getConnection().isContentDisposition()) {
            br.followConnection();
            if (captcha && !success) {
                throw new PluginException(LinkStatus.ERROR_CAPTCHA);
            }
            checkErrors(link, account);
            checkErrorsLastResort(link, account);
        }
        dl.setFilenameFix(isContentDispositionFixRequired(dl, dl.getConnection(), link));
        dl.startDownload();
    }

    protected Form getContinueForm(final int loop_counter, final String continue_link) throws PluginException {
        /* 2019-07-05: continue_form without captcha is a rare case. Example-site: freefile.me */
        Form continueform = br.getFormbyActionRegex(".+pt=.+");
        if (continueform == null) {
            continueform = br.getFormByInputFieldKeyValue("submitted", "1");
        }
        if (continueform == null) {
            continueform = br.getFormbyKey("submitted");
        }
        if (!StringUtils.isEmpty(continue_link) && continueform == null) {
            continueform = new Form();
            continueform.setMethod(MethodType.GET);
            continueform.setAction(continue_link);
            continueform.put("submit", "Submit");
            continueform.put("submitted", "1");
            continueform.put("d", "1");
        }
        return continueform;
    }

    protected String getContinueLink() throws Exception {
        String continue_link = br.getRegex("\\$\\(\\'\\.download\\-timer\\'\\)\\.html\\(\"<a href=\\'(https?://[^<>\"]*?)\\'").getMatch(0);
        if (continue_link == null) {
            continue_link = br.getRegex("class=\\'btn btn\\-free\\' href=\\'(https?://[^<>\"]*?)\\'>").getMatch(0);
        }
        if (continue_link == null) {
            continue_link = br.getRegex("<div class=\"captchaPageTable\">\\s*<form method=\"POST\" action=\"(https?://[^<>\"]*?)\"").getMatch(0);
        }
        if (continue_link == null) {
            continue_link = br.getRegex("(https?://[^/]+/[^<>\"\\':]*pt=[^<>\"\\']*)(?:\"|\\')").getMatch(0);
        }
        if (continue_link == null) {
            continue_link = getDllink();
        }
        return continue_link;
    }

    // private String getStreamUrl() {
    // return getStreamUrl(this.br);
    // }
    //
    // private String getStreamUrl(final Browser br) {
    // return br.getRegex("file\\s*?:\\s*?\"(https?://[^<>\"]+)\"").getMatch(0);
    // }
    /** 2019-08-29: Never call this directly - always call it via getContinueLink!! */
    private String getDllink() {
        return getDllink(this.br);
    }

    /** If overridden, make sure to make isDownloadlink compatible as well! */
    protected String getDllink(final Browser br) {
        /* 2020-02-26: Example without 'http': cnubis.com */
        String ret = br.getRegex("\"((?:https?:)?//[A-Za-z0-9\\.\\-]+\\.[^/]+/[^<>\"]*?(?:\\?|\\&)download_token=[A-Za-z0-9]+[^<>\"]*?)\"").getMatch(0);
        if (StringUtils.isEmpty(ret)) {
            ret = br.getRegex("\"(https?://[^\"]+/files/[^\"]+)\"").getMatch(0);
        }
        if (isDownloadlink(ret)) {
            return ret;
        } else if (ret != null) {
            logger.info("isDownloadlink false:" + ret);
            return null;
        } else {
            return null;
        }
    }

    public boolean isDownloadlink(final String url) {
        /* Most sites use 'download_token', '/files/' is e.g. used by dropmega.com and dbree.co */
        final boolean ret = url != null && (url.contains("download_token=") || url.contains("/files/"));
        return ret;
    }

    /** Returns unique id from inside URL - usually with this pattern: [A-Za-z0-9]+ */
    protected String getFUIDFromURL(final DownloadLink link) {
        return getFUIDFromURL(link.getPluginPatternMatcher());
    }

    /** Returns unique id from inside URL - usually with this pattern: [A-Za-z0-9]+ */
    public String getFUIDFromURL(final String url) {
        try {
            final String result = new Regex(new URL(url).getPath(), "^/([A-Za-z0-9]+)").getMatch(0);
            return result;
        } catch (MalformedURLException e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * In some cases, URL may contain filename which can be used as fallback e.g. 'https://host.tld/<fuid>/<filename>'. Example host which
     * has URLs that contain filenames: freefile.me, letsupload.co
     */
    public String getFilenameFromURL(final DownloadLink link) {
        final String result;
        if (link.getContentUrl() != null) {
            result = getFilenameFromURL(link.getContentUrl());
        } else {
            result = getFilenameFromURL(link.getPluginPatternMatcher());
        }
        return result;
    }

    public static String getFilenameFromURL(final String url) {
        try {
            final String result = new Regex(new URL(url).getPath(), "[^/]+/(.+)$").getMatch(0);
            return result;
        } catch (MalformedURLException e) {
            e.printStackTrace();
        }
        return null;
    }

    /** Tries to get filename from URL and if this fails, will return <fuid> filename. */
    public String getFallbackFilename(final DownloadLink dl) {
        String fallback_filename = this.getFilenameFromURL(dl);
        if (fallback_filename == null) {
            fallback_filename = this.getFUIDFromURL(dl);
        }
        return fallback_filename;
    }

    /** Tries to get filename from URL and if this fails, will return <fuid> filename. */
    public String getFallbackFilename(final String url) {
        String fallback_filename = getFilenameFromURL(url);
        if (fallback_filename == null) {
            fallback_filename = getFUIDFromURL(url);
        }
        return fallback_filename;
    }

    private void handlePassword(final DownloadLink dl) throws Exception {
        if (br.getURL().contains("/file_password.html")) {
            logger.info("Current link is password protected");
            String passCode = dl.getStringProperty("pass", null);
            if (passCode == null) {
                passCode = Plugin.getUserInput("Password?", dl);
                if (passCode == null || passCode.equals("")) {
                    logger.info("User has entered blank password, exiting handlePassword");
                    dl.setDownloadPassword(null);
                    throw new PluginException(LinkStatus.ERROR_RETRY, "Wrong password entered");
                }
                dl.setDownloadPassword(passCode);
            }
            postPage(br.getURL(), "submit=access+file&submitme=1&file=" + this.getFUIDFromURL(dl) + "&filePassword=" + Encoding.urlEncode(passCode));
            if (br.getURL().contains("/file_password.html")) {
                logger.info("User entered incorrect password --> Retrying");
                dl.setDownloadPassword(null);
                throw new PluginException(LinkStatus.ERROR_RETRY, "Wrong password entered");
            }
            logger.info("User entered correct password --> Continuing");
        }
    }

    protected boolean preDownloadWaittimeSkippable() {
        return false;
    }

    /**
     * Handles pre download (pre-captcha) waittime. If WAITFORCED it ensures to always wait long enough even if the waittime RegEx fails.
     */
    protected void waitTime(final DownloadLink downloadLink, final long timeBefore) throws PluginException {
        /* Ticket Time */
        final String waitStr = regexWaittime();
        if (this.preDownloadWaittimeSkippable()) {
            /* Very rare case! */
            logger.info("Skipping pre-download waittime: " + waitStr);
        } else {
            final int extraWaitSeconds = 1;
            int wait;
            if (waitStr != null && waitStr.matches("\\d+")) {
                int passedTime = (int) ((System.currentTimeMillis() - timeBefore) / 1000) - extraWaitSeconds;
                logger.info("Found waittime, parsing waittime: " + waitStr);
                wait = Integer.parseInt(waitStr);
                /*
                 * Check how much time has passed during eventual captcha event before this function has been called and see how much time
                 * is left to wait.
                 */
                wait -= passedTime;
                if (passedTime > 0) {
                    /* This usually means that the user had to solve a captcha which cuts down the remaining time we have to wait. */
                    logger.info("Total passed time during captcha: " + passedTime);
                }
            } else {
                /* No waittime at all */
                wait = 0;
            }
            if (wait > 0) {
                logger.info("Waiting final waittime: " + wait);
                sleep(wait * 1000l, downloadLink);
            } else if (wait < -extraWaitSeconds) {
                /* User needed more time to solve the captcha so there is no waittime left :) */
                logger.info("Congratulations: Time to solve captcha was higher than waittime --> No waittime left");
            } else {
                /* No waittime at all */
                logger.info("Found no waittime");
            }
        }
    }

    /* https://stackoverflow.com/questions/10664434/escaping-special-characters-in-java-regular-expressions/25853507 */
    static Pattern SPECIAL_REGEX_CHARS = Pattern.compile("[{}()\\[\\].+*?^$\\\\|]");

    static String escapeSpecialRegexChars(String str) {
        return SPECIAL_REGEX_CHARS.matcher(str).replaceAll("\\\\$0");
    }

    private HashMap<String, Object> getErrorKeyFromErrorMessage(final String errorStr) {
        if (StringUtils.isEmpty(errorStr)) {
            return null;
        }
        try {
            final String language_keys_json = br.getRegex("l\\s*=\\s*(\\{.*?\\});\\s*?return").getMatch(0);
            final HashMap<String, Object> language_keys_map = (HashMap<String, Object>) JavaScriptEngineFactory.jsonToJavaMap(language_keys_json);
            final Iterator<Entry<String, Object>> iterator = language_keys_map.entrySet().iterator();
            String found_key_for_errormessage = null;
            final HashMap<String, String> errorProperties = new HashMap<String, String>();
            while (iterator.hasNext()) {
                final Entry<String, Object> entry = iterator.next();
                final String key = entry.getKey();
                final String langStr = (String) entry.getValue();
                final String[] dynamicVarsNames = new Regex(langStr, "\\[\\[\\[([^\\]]+)\\]\\]\\]").getColumn(0);
                if (dynamicVarsNames.length > 0) {
                    /* First escape alll special chars in our langString */
                    String langStr2 = escapeSpecialRegexChars(langStr);
                    /* Now we have to replace the placeholders so that we can match the whole String against errorStr. */
                    langStr2 = langStr2.replaceAll("\\\\\\[\\\\\\[\\\\\\[[^\\]]+\\\\\\]\\\\\\]\\\\\\]", "(.*?)");
                    // Pattern langStrEscaped = Pattern.compile(langStr.replaceAll("\\[\\[\\[[^\\]]+\\]\\]\\]", ".*?"));
                    final Regex langStrRegEx = new Regex(errorStr, langStr2);
                    if (!langStrRegEx.matches()) {
                        continue;
                    }
                    found_key_for_errormessage = key;
                    for (int index = 0; index < dynamicVarsNames.length; index++) {
                        final String dynamicVarName = dynamicVarsNames[index];
                        final String dynamicVarValue = langStrRegEx.getMatch(index);
                        errorProperties.put(dynamicVarName, dynamicVarValue);
                    }
                    break;
                } else {
                    /* Easy - static String */
                    if (langStr.equalsIgnoreCase(errorStr)) {
                        found_key_for_errormessage = key;
                        break;
                    }
                }
            }
            if (found_key_for_errormessage != null) {
                HashMap<String, Object> errorMap = new HashMap<String, Object>();
                errorMap.put("error_key", found_key_for_errormessage);
                if (!errorProperties.isEmpty()) {
                    errorMap.put("error_properties", errorProperties);
                }
                return errorMap;
            } else {
                logger.info("Failed to find language key for errormessage: " + errorStr);
                return null;
            }
        } catch (final Throwable e) {
            logger.log(e);
            return null;
        }
    }

    /* 2020-03-25: No plugin should ever have to override this. Please create a ticket before changing this! */
    private void checkErrorsLanguageIndependant(final DownloadLink link, final Account account) throws PluginException {
        String errorMsgURL = null;
        try {
            final UrlQuery query = UrlQuery.parse(br.getURL());
            errorMsgURL = query.get("e");
            if (errorMsgURL != null) {
                errorMsgURL = URLDecoder.decode(errorMsgURL, "UTF-8");
            }
        } catch (final Throwable e) {
            logger.log(e);
        }
        if (!StringUtils.isEmpty(errorMsgURL)) {
            logger.info("Found errormessage in current URL: " + errorMsgURL);
            final HashMap<String, Object> errorMap = getErrorKeyFromErrorMessage(errorMsgURL);
            if (errorMap == null) {
                /* Not all websites have (all) language keys present e.g. ultimbox.com */
                logger.info("Failed to find error_key --> Trying checkErrorsURLOld");
                checkErrorsURLOld(link, account);
                logger.info("checkErrorsURLOld did not do anything --> Throwing Exception ERROR_TEMPORARILY_UNAVAILABLE because of errorMsgURL: " + errorMsgURL);
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Unknown error without errorkey: " + errorMsgURL);
            }
            final String errorkey = (String) errorMap.get("error_key");
            logger.info("Found key to errormessage: " + errorkey);
            HashMap<String, String> errorProperties = null;
            if (errorMap.containsKey("error_properties")) {
                /* This is really only for logging purposes */
                errorProperties = (HashMap<String, String>) errorMap.get("error_properties");
                final Iterator<Entry<String, String>> dynVarsIterator = errorProperties.entrySet().iterator();
                int counter = 0;
                while (dynVarsIterator.hasNext()) {
                    counter++;
                    final Entry<String, String> entry = dynVarsIterator.next();
                    final String key = entry.getKey();
                    final String value = entry.getValue();
                    logger.info("Found ErrorProperty " + counter + " | " + key + " : " + value);
                }
            }
            final long default_waittime = 15 * 60 * 1000l;
            /* Now handle errors */
            if (errorkey.equalsIgnoreCase("error_file_has_been_removed_by_admin") || errorkey.equalsIgnoreCase("error_file_has_been_removed_by_user") || errorkey.equalsIgnoreCase("error_file_has_been_removed_due_to_copyright") || errorkey.equalsIgnoreCase("error_file_has_expired")) {
                // VERIFIED
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND, errorMsgURL);
            }
            /** premiumonly errorhandling */
            else if (errorkey.equalsIgnoreCase("error_you_must_register_for_a_premium_account_for_filesize")) {
                throw new AccountRequiredException(errorMsgURL);
            } else if (errorkey.equalsIgnoreCase("error_you_must_be_a_x_user_to_download_this_file")) {
                throw new AccountRequiredException(errorMsgURL);
            } else if (errorkey.equalsIgnoreCase("error_you_must_register_for_a_premium_account_for_filesize")) {
                throw new AccountRequiredException(errorMsgURL);
            } else if (errorkey.equalsIgnoreCase("error_file_is_not_publicly_shared")) {
                /* Very very rare case */
                logger.info("This file can only be downloaded by the initial uploader");
                throw new AccountRequiredException(errorMsgURL);
            } /** Limit errorhandling */
            else if (errorkey.equalsIgnoreCase("error_you_have_reached_the_download_limit")) {
                throw new PluginException(LinkStatus.ERROR_IP_BLOCKED, errorMsgURL, default_waittime);
            } else if (errorkey.equalsIgnoreCase("error_you_have_reached_the_download_limit_this_file")) {
                /* "You do not have enough bandwidth left to download this file." */
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, errorMsgURL, default_waittime);
            } else if (errorkey.equalsIgnoreCase("error_you_have_reached_the_maximum_daily_download_limit")) {
                /* "You have reached the maximum download bandwidth today, please upgrade or try again later." */
                throw new PluginException(LinkStatus.ERROR_IP_BLOCKED, errorMsgURL, default_waittime);
            } else if (errorkey.equalsIgnoreCase("error_you_have_reached_the_maximum_daily_download_limit_this_file")) {
                /* "You do not have enough bandwidth left today to download this file." */
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, errorMsgURL, default_waittime);
            } else if (errorkey.equalsIgnoreCase("error_you_have_reached_the_maximum_permitted_downloads_in_the_last_24_hours")) {
                throw new PluginException(LinkStatus.ERROR_IP_BLOCKED, errorMsgURL, default_waittime);
            } else if (errorkey.equalsIgnoreCase("error_you_have_reached_the_max_permitted_downloads")) {
                /*
                 * "You have reached the maximum concurrent downloads. Please wait for your existing downloads to complete or register for a premium account above."
                 */
                throw new PluginException(LinkStatus.ERROR_HOSTER_TEMPORARILY_UNAVAILABLE, errorMsgURL, default_waittime);
            } else if (errorkey.equalsIgnoreCase("error_you_must_wait_between_downloads")) {
                // VERIFIED
                /* E.g. "30 minutes" */
                final String waitStr = errorProperties.get("WAITING_TIME_LABEL");
                long waittime = parseWaittime(waitStr);
                if (waittime <= 0) {
                    waittime = default_waittime;
                }
                if (waittime < 180000) {
                    throw new PluginException(LinkStatus.ERROR_HOSTER_TEMPORARILY_UNAVAILABLE, errorMsgURL, waittime);
                } else if (account != null) {
                    throw new AccountUnavailableException(errorMsgURL, waittime);
                } else {
                    throw new PluginException(LinkStatus.ERROR_IP_BLOCKED, errorMsgURL, waittime);
                }
            } else {
                logger.warning("Unknown errorkey: " + errorkey + " --> Trying checkErrorsURLOld");
                checkErrorsURLOld(link, account);
                logger.info("checkErrorsURLOld did not do anything --> Throwing Exception ERROR_TEMPORARILY_UNAVAILABLE because of errorMsgURL: " + errorMsgURL);
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Unknown error: " + errorMsgURL + " | Errorkey: " + errorkey);
            }
        }
    }

    private long parseWaittime(final String src) {
        /*
         * Important: URL Might contain htmlencoded parts! Be sure that these RegExes are tolerant enough to get the information we need!
         */
        final String wait_hours = new Regex(src, "(\\d+)\\s*?hours?").getMatch(0);
        final String wait_minutes = new Regex(src, "(\\d+)\\s*?minutes?").getMatch(0);
        final String wait_seconds = new Regex(src, "(\\d+)\\s*?(?:seconds?|segundos)").getMatch(0);
        int minutes = 0, seconds = 0, hours = 0;
        if (wait_hours != null) {
            hours = Integer.parseInt(wait_hours);
        }
        if (wait_minutes != null) {
            minutes = Integer.parseInt(wait_minutes);
        }
        if (wait_seconds != null) {
            seconds = Integer.parseInt(wait_seconds);
        }
        final int extraWaittimeSeconds = 1;
        int waittime = ((3600 * hours) + (60 * minutes) + seconds + extraWaittimeSeconds) * 1000;
        if (waittime <= 0) {
            /* Fallback */
            logger.info("Waittime RegExes seem to be broken or given String does not contain any waittime");
        }
        return waittime;
    }

    public void checkErrors(final DownloadLink link, final Account account) throws PluginException {
        checkErrorsLanguageIndependant(link, account);
        /*
         * Old / fallback / English / additional errorhandling - please leave this here although checkErrorsLanguageIndependant should cover
         * most of all errors
         */
        checkErrorsURLOld(link, account);
        if (br.toString().equals("unknown user")) {
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 'Unknown user'", 30 * 60 * 1000l);
        } else if (br.toString().equals("ERROR: Wrong IP")) {
            /*
             * 2019-07-05: New: rare case but this can either happen randomly or when you try to resume a stored downloadurl with a new IP.
             */
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 'Wrong IP'", 5 * 60 * 1000l);
        }
    }

    public void checkErrorsLastResort(final DownloadLink link, final Account account) throws PluginException {
        logger.info("Last resort errorhandling");
        if (account != null && !this.isLoggedin()) {
            throw new AccountUnavailableException("Session expired?", 5 * 60 * 1000l);
        }
        logger.warning("Unknown error happened");
        throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
    }

    @Deprecated
    /** It is intended to replace this with checkErrorsLanguageIndependant! */
    private void checkErrorsURLOld(final DownloadLink link, final Account account) throws PluginException {
        final String url = getCurrentURLDecoded();
        if (br.containsHTML("Error: Too many concurrent download requests")) {
            throw new PluginException(LinkStatus.ERROR_HOSTER_TEMPORARILY_UNAVAILABLE, "Wait before starting new downloads", 3 * 60 * 1000l);
        } else if (new Regex(br.getURL(), Pattern.compile(".*?e=You\\+have\\+reached\\+the\\+maximum\\+concurrent\\+downloads.*?", Pattern.CASE_INSENSITIVE)).matches()) {
            throw new PluginException(LinkStatus.ERROR_HOSTER_TEMPORARILY_UNAVAILABLE, "Max. simultan downloads limit reached, wait to start more downloads", 1 * 60 * 1000l);
        } else if (new Regex(br.getURL(), Pattern.compile(".*e=.*Could\\+not\\+open\\+file\\+for\\+reading.*", Pattern.CASE_INSENSITIVE)).matches()) {
            throw new PluginException(LinkStatus.ERROR_HOSTER_TEMPORARILY_UNAVAILABLE, "Server error", 60 * 60 * 1000l);
        } else if (url != null && new Regex(url, Pattern.compile(".*?\\?e=(You must wait |Você deve esperar).*?", Pattern.CASE_INSENSITIVE)).matches()) {
            final long extraWaittimeMilliseconds = 1000;
            long waittime = this.parseWaittime(url);
            if (waittime <= 0) {
                /* Fallback */
                logger.info("Waittime RegExes seem to be broken - using default waittime");
            }
            waittime += extraWaittimeMilliseconds;
            logger.info("Detected reconnect waittime (milliseconds): " + waittime);
            /* Not enough wait time to reconnect -> Wait short and retry */
            if (waittime < 180000) {
                throw new PluginException(LinkStatus.ERROR_HOSTER_TEMPORARILY_UNAVAILABLE, "Wait until new downloads can be started", waittime);
            } else if (account != null) {
                throw new AccountUnavailableException("Download limit reached", waittime);
            } else {
                throw new PluginException(LinkStatus.ERROR_IP_BLOCKED, null, waittime);
            }
        } else if (url != null && new Regex(url, Pattern.compile(".*?(You must register for a premium account to|Ten plik jest za duży do pobrania dla darmowego użytkownika|/register\\.).+", Pattern.CASE_INSENSITIVE)).matches()) {
            throw new AccountRequiredException();
        } else if (br.getURL().contains("You+have+reached+the+maximum+permitted+downloads+in")) {
            throw new PluginException(LinkStatus.ERROR_IP_BLOCKED, "Daily limit reached", 3 * 60 * 60 * 1001l);
        }
    }

    /** Handles all kinds of error-responsecodes! */
    private void checkResponseCodeErrors(final URLConnectionAdapter con) throws PluginException {
        if (con == null) {
            return;
        }
        final long responsecode = con.getResponseCode();
        if (responsecode == 403) {
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 403", 5 * 60 * 1000l);
        } else if (responsecode == 404) {
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 404", 5 * 60 * 1000l);
        } else if (responsecode == 416) {
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 416", 2 * 60 * 1000l);
        } else if (responsecode == 429) {
            throw new PluginException(LinkStatus.ERROR_HOSTER_TEMPORARILY_UNAVAILABLE, "Server error 429 connection limit reached, please contact our support!", 5 * 60 * 1000l);
        }
    }

    /**
     * @return true = file is offline, false = file is online </br>
     *         Be sure to always call checkErrors before calling this!
     * @throws Exception
     */
    protected boolean isOfflineWebsite(final DownloadLink link) throws Exception {
        /* TODO: Consider checking for fuid in URL too in the future --> This might be a good offline indicator */
        // final String fid = this.getFUIDFromURL(link);
        // final boolean currentURLContainsFID = br.getURL().contains(fid);
        final boolean isDownloadable = this.getContinueLink() != null;
        final boolean isFileWebsite = br.containsHTML("class=\"downloadPageTable(V2)?\"") || br.containsHTML("class=\"download\\-timer\"");
        final boolean isErrorPage = br.getURL().contains("/error.html") || br.getURL().contains("/index.html");
        final boolean isOffline404 = br.getHttpConnection().getResponseCode() == 404;
        if ((!isFileWebsite || isErrorPage || isOffline404) && !isDownloadable) {
            return true;
        }
        return false;
    }

    protected String getCurrentURLDecoded() {
        if (br.getURL() != null) {
            String currentURL = br.getURL();
            if (Encoding.isUrlCoded(currentURL)) {
                try {
                    currentURL = URLDecoder.decode(currentURL, "UTF-8");
                } catch (final Throwable e) {
                    logger.info("Failed to urldecode current URL: " + br.getURL());
                }
            }
            return currentURL;
        }
        return null;
    }

    /** Returns pre-download-waittime (seconds) from inside HTML. */
    public String regexWaittime() {
        String ttt = this.br.getRegex("\\$\\(\\'\\.download\\-timer\\-seconds\\'\\)\\.html\\((\\d+)\\);").getMatch(0);
        if (ttt == null) {
            ttt = this.br.getRegex("var\\s*?seconds\\s*?=\\s*?(\\d+);").getMatch(0);
        }
        return ttt;
    }

    private String checkDirectLink(final DownloadLink link, final Account account) throws InterruptedException, PluginException {
        final String directlinkproperty = getDownloadModeDirectlinkProperty(account);
        final String dllink = link.getStringProperty(directlinkproperty);
        if (dllink != null) {
            final boolean resume = this.isResumeable(link, account);
            final int maxchunks = this.getMaxChunks(account);
            final Browser br2 = this.br.cloneBrowser();
            br2.setFollowRedirects(true);
            URLConnectionAdapter con = null;
            boolean valid = false;
            try {
                // con = br2.openHeadConnection(dllink);
                this.dl = jd.plugins.BrowserAdapter.openDownload(br2, link, dllink, resume, maxchunks);
                con = dl.getConnection();
                if (br2.getHttpConnection().getResponseCode() == 429) {
                    /*
                     * Too many connections but that does not mean that our downloadlink is valid. Accept it and if it still returns 429 on
                     * download-attempt this error will get displayed to the user.
                     */
                    logger.info("Stored directurl lead to 429 | too many connections");
                    valid = true;
                    return dllink;
                } else if (!con.isOK() || con.getContentType().contains("text") || con.getLongContentLength() == -1) {
                    link.setProperty(directlinkproperty, Property.NULL);
                    return null;
                } else {
                    valid = true;
                    return dllink;
                }
            } catch (final InterruptedException e) {
                throw e;
            } catch (final Exception e) {
                link.setProperty(directlinkproperty, Property.NULL);
                logger.log(e);
            } finally {
                if (!valid) {
                    try {
                        con.disconnect();
                    } catch (final Throwable e) {
                    }
                    this.dl = null;
                }
            }
        }
        return null;
    }

    protected String getProtocol() {
        if ((this.br.getURL() != null && this.br.getURL().contains("https://")) || supports_https()) {
            return "https://";
        } else {
            return "http://";
        }
    }

    protected Browser prepBrowser(final Browser br) {
        br.setAllowedResponseCodes(new int[] { 416, 429 });
        if (enable_random_user_agent()) {
            if (agent.get() == null) {
                agent.set(UserAgents.stringUserAgent());
            }
            br.getHeaders().put("User-Agent", agent.get());
        }
        return br;
    }

    protected void login(final Account account, boolean force) throws Exception {
        synchronized (account) {
            try {
                br.setCookiesExclusive(true);
                prepBrowser(this.br, account.getHoster());
                br.setFollowRedirects(true);
                final Cookies cookies = account.loadCookies("");
                if (cookies != null) {
                    this.br.setCookies(this.getHost(), cookies);
                    if (System.currentTimeMillis() - account.getCookiesTimeStamp("") <= 300000l && !force) {
                        /* We trust these cookies as they're not that old --> Do not check them */
                        return;
                    }
                    logger.info("Verifying login-cookies");
                    getPage(this.getMainPage() + "/account_home.html");
                    if (isLoggedin()) {
                        logger.info("Successfully logged in via cookies");
                        account.saveCookies(this.br.getCookies(this.getHost()), "");
                        return;
                    } else {
                        logger.info("Failed to login via cookies");
                    }
                }
                logger.info("Performing full login");
                getPage(this.getProtocol() + this.getHost() + "/login.html");
                Form loginform;
                if (br.containsHTML("flow\\-login\\.js") && !enforce_old_login_method()) {
                    final String loginstart = new Regex(br.getURL(), "(https?://(www\\.)?)").getMatch(0);
                    /* New (ajax) login method - mostly used - example: iosddl.net */
                    logger.info("Using new login method");
                    /* These headers are important! */
                    br.getHeaders().put("X-Requested-With", "XMLHttpRequest");
                    br.getHeaders().put("Accept", "application/json, text/javascript, */*; q=0.01");
                    loginform = br.getFormbyProperty("id", "form_login");
                    if (loginform == null) {
                        logger.info("Fallback to custom built loginform");
                        loginform = new Form();
                        loginform.put("submitme", "1");
                    }
                    loginform.put("username", Encoding.urlEncode(account.getUser()));
                    loginform.put("password", Encoding.urlEncode(account.getPass()));
                    final String action = loginstart + this.getHost() + "/ajax/_account_login.ajax.php";
                    loginform.setAction(action);
                    if (loginform.containsHTML("class=\"g\\-recaptcha\"")) {
                        /* E.g. crazyshare.cc */
                        final DownloadLink dlinkbefore = this.getDownloadLink();
                        if (dlinkbefore == null) {
                            this.setDownloadLink(new DownloadLink(this, "Account", this.getHost(), "https://" + account.getHoster(), true));
                        }
                        final String recaptchaV2Response = new CaptchaHelperHostPluginRecaptchaV2(this, br).getToken();
                        if (dlinkbefore != null) {
                            this.setDownloadLink(dlinkbefore);
                        }
                        loginform.put("g-recaptcha-response", Encoding.urlEncode(recaptchaV2Response));
                    }
                    submitForm(loginform);
                    if (!br.containsHTML("\"login_status\":\"success\"")) {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_DISABLE);
                    }
                } else {
                    /* Old login method - rare case! Example: udrop.net */
                    logger.info("Using old login method");
                    loginform = br.getFormbyProperty("id", "form_login");
                    if (loginform == null) {
                        loginform = br.getFormbyKey("loginUsername");
                    }
                    if (loginform == null) {
                        logger.info("Fallback to custom built loginform");
                        loginform = new Form();
                        loginform.setMethod(MethodType.POST);
                        loginform.put("submit", "Login");
                        loginform.put("submitme", "1");
                    }
                    if (loginform.hasInputFieldByName("loginUsername") && loginform.hasInputFieldByName("loginPassword")) {
                        /* 2019-07-08: Rare case: Example: freaktab.org */
                        loginform.put("loginUsername", Encoding.urlEncode(account.getUser()));
                        loginform.put("loginPassword", Encoding.urlEncode(account.getPass()));
                    } else if (loginform.hasInputFieldByName("email")) {
                        /* 2020-04-30: E.g. filemia.com */
                        loginform.put("email", Encoding.urlEncode(account.getUser()));
                        loginform.put("password", Encoding.urlEncode(account.getPass()));
                    } else {
                        loginform.put("username", Encoding.urlEncode(account.getUser()));
                        loginform.put("password", Encoding.urlEncode(account.getPass()));
                    }
                    /* 2019-07-31: At the moment only this older login method supports captchas. Examplehost: uploadship.com */
                    if (br.containsHTML("solvemedia\\.com/papi/")) {
                        /* Handle login-captcha if required */
                        DownloadLink dlinkbefore = this.getDownloadLink();
                        try {
                            final DownloadLink dl_dummy;
                            if (dlinkbefore != null) {
                                dl_dummy = dlinkbefore;
                            } else {
                                dl_dummy = new DownloadLink(this, "Account", this.getHost(), "https://" + account.getHoster(), true);
                                this.setDownloadLink(dl_dummy);
                            }
                            final org.jdownloader.captcha.v2.challenge.solvemedia.SolveMedia sm = new org.jdownloader.captcha.v2.challenge.solvemedia.SolveMedia(br);
                            if (br.containsHTML("api\\-secure\\.solvemedia\\.com/")) {
                                sm.setSecure(true);
                            }
                            File cf = null;
                            try {
                                cf = sm.downloadCaptcha(getLocalCaptchaFile());
                            } catch (final Exception e) {
                                if (org.jdownloader.captcha.v2.challenge.solvemedia.SolveMedia.FAIL_CAUSE_CKEY_MISSING.equals(e.getMessage())) {
                                    throw new PluginException(LinkStatus.ERROR_FATAL, "Host side solvemedia.com captcha error - please contact the " + this.getHost() + " support");
                                }
                                throw e;
                            }
                            final String code = getCaptchaCode("solvemedia", cf, dl_dummy);
                            final String chid = sm.getChallenge(code);
                            loginform.put("adcopy_challenge", chid);
                            loginform.put("adcopy_response", "manual_challenge");
                        } finally {
                            if (dlinkbefore != null) {
                                this.setDownloadLink(dlinkbefore);
                            }
                        }
                    }
                    submitForm(loginform);
                    if (br.containsHTML(">\\s*Your username and password are invalid<") || !isLoggedin()) {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_DISABLE);
                    }
                }
                account.saveCookies(this.br.getCookies(this.getHost()), "");
            } catch (final PluginException e) {
                if (e.getLinkStatus() == LinkStatus.ERROR_PREMIUM) {
                    account.clearCookies("");
                }
                throw e;
            }
        }
    }

    protected boolean isLoggedin() {
        return br.containsHTML("/logout\\.html\"");
    }

    @Override
    public AccountInfo fetchAccountInfo(final Account account) throws Exception {
        if (supports_api()) {
            return fetchAccountInfoAPI(account);
        } else {
            return fetchAccountInfoWebsite(account);
        }
    }

    protected AccountInfo fetchAccountInfoWebsite(final Account account) throws Exception {
        final AccountInfo ai = new AccountInfo();
        login(account, true);
        if (br.getURL() == null || !br.getURL().contains("/account_home.html")) {
            getPage("/account_home.html");
        }
        /* 2019-03-01: Bad german translation, example: freefile.me */
        boolean isPremium = br.containsHTML("class\\s*=\\s*\"badge badge\\-success\"\\s*>\\s*(?:BEZAHLT(er)? BENUTZER|PAID USER|USUARIO DE PAGO|VIP|PREMIUM)\\s*</span>");
        if (!isPremium) {
            account.setType(AccountType.FREE);
            account.setMaxSimultanDownloads(this.getMaxSimultaneousFreeAccountDownloads());
            /* All accounts get the same (IP-based) downloadlimits --> Simultaneous free account usage makes no sense! */
            account.setConcurrentUsePossible(false);
            ai.setStatus("Registered (free) account");
        } else {
            getPage("/upgrade.html");
            /* If the premium account is expired we'll simply accept it as a free account. */
            String expireStr = br.getRegex("Reverts To Free Account\\s*:\\s*</td>\\s*<td>\\s*(\\d{2}/\\d{2}/\\d{4} \\d{2}:\\d{2}:\\d{2})").getMatch(0);
            if (expireStr == null) {
                expireStr = br.getRegex("Reverts To Free Account\\s*:\\s*</span>\\s*<input[^>]*value\\s*=\\s*\"(\\d{2}/\\d{2}/\\d{4} \\d{2}:\\d{2}:\\d{2})").getMatch(0);
                if (expireStr == null) {
                    /* More wide RegEx to be more language independant (e.g. required for freefile.me) */
                    expireStr = br.getRegex(">\\s*(\\d{2}/\\d{2}/\\d{4} \\d{2}:\\d{2}:\\d{2})\\s*<").getMatch(0);
                }
            }
            if (expireStr == null) {
                /*
                 * 2019-03-01: As far as we know, EVERY premium account will have an expire-date given but we will still accept accounts for
                 * which we fail to find the expire-date.
                 */
                logger.info("Failed to find expire-date");
                return ai;
            }
            long expire_milliseconds = parseExpireTimeStamp(account, expireStr);
            isPremium = expire_milliseconds > System.currentTimeMillis();
            if (!isPremium) {
                /* Expired premium == FREE */
                account.setType(AccountType.FREE);
                account.setMaxSimultanDownloads(this.getMaxSimultaneousFreeAccountDownloads());
                /* All accounts get the same (IP-based) downloadlimits --> Simultan free account usage makes no sense! */
                account.setConcurrentUsePossible(false);
                ai.setStatus("Registered (free) user");
            } else {
                ai.setValidUntil(expire_milliseconds, this.br);
                account.setType(AccountType.PREMIUM);
                account.setMaxSimultanDownloads(this.getMaxSimultanPremiumDownloadNum());
                ai.setStatus("Premium account");
            }
        }
        ai.setUnlimitedTraffic();
        return ai;
    }

    /**
     * 2020-05-14: psp: https://fhscript.com/admin/api_documentation.php?username=admin&password=password&submitme=1 </br>
     * Their API implementation and documentation is SO BAD - I've not seen it working, checked about 15 websites --> We will probably never
     * be able to add support for it!
     */
    protected AccountInfo fetchAccountInfoAPI(final Account account) throws Exception {
        return null;
    }

    protected long parseExpireTimeStamp(Account account, final String expireString) {
        if (expireString == null) {
            return -1;
        }
        final String first = new Regex(expireString, "^(\\d+)/").getMatch(0);
        final String second = new Regex(expireString, "^\\d+/(\\d+)").getMatch(0);
        final int firstNumber = Integer.parseInt(first);
        final int secondNumber = Integer.parseInt(second);
        long timestamp_daysfirst = 0;
        long timestamp_dayssecond = 0;
        /* Days first */
        if (secondNumber <= 12) {
            timestamp_daysfirst = TimeFormatter.getMilliSeconds(expireString, "dd/MM/yyyy hh:mm:ss", Locale.ENGLISH);
        }
        /* Months first */
        if (firstNumber <= 12) {
            timestamp_dayssecond = TimeFormatter.getMilliSeconds(expireString, "MM/dd/yyyy hh:mm:ss", Locale.ENGLISH);
        }
        if (timestamp_daysfirst > timestamp_dayssecond) {
            return timestamp_daysfirst;
        } else {
            return timestamp_dayssecond;
        }
    }

    @Override
    public void handlePremium(final DownloadLink link, final Account account) throws Exception {
        requestFileInformation(link, account, true);
        login(account, false);
        br.setFollowRedirects(false);
        getPage(link.getPluginPatternMatcher());
        handleDownload(link, account);
    }

    @Override
    protected void getPage(String page) throws Exception {
        page = correctProtocol(page);
        getPage(br, page);
    }

    @Override
    protected void getPage(final Browser br, String page) throws Exception {
        page = correctProtocol(page);
        super.getPage(br, page);
    }

    @Override
    protected void postPage(String page, final String postdata) throws Exception {
        page = correctProtocol(page);
        postPage(br, page, postdata);
    }

    @Override
    protected void postPage(final Browser br, String page, final String postdata) throws Exception {
        page = correctProtocol(page);
        super.postPage(br, page, postdata);
    }

    protected String correctProtocol(String url) {
        if (supports_https()) {
            /* Prefer https whenever possible */
            url = url.replaceFirst("http://", "https://");
        } else {
            url = url.replaceFirst("https://", "http://");
        }
        if (this.requires_WWW() && !url.contains("www.")) {
            url = url.replace("//", "//www.");
        } else if (!this.requires_WWW()) {
            url = url.replace("www.", "");
        }
        return url;
    }

    /** Returns https?://host.tld */
    protected String getMainPage() {
        final String[] hosts = this.siteSupportedNames();
        return ("http://" + hosts[0]).replaceFirst("https?://", this.supports_https() ? "https://" : "http://");
    }

    /**
     * Use this to set filename based on filename inside URL or fuid as filename either before a linkcheck happens so that there is a
     * readable filename displayed in the linkgrabber.
     */
    protected void setWeakFilename(final DownloadLink link) {
        final String weak_fallback_filename = this.getFallbackFilename(link);
        if (weak_fallback_filename != null) {
            link.setName(weak_fallback_filename);
        }
    }

    @Override
    public SiteTemplate siteTemplateType() {
        return SiteTemplate.MFScripts_YetiShare;
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
    }
}