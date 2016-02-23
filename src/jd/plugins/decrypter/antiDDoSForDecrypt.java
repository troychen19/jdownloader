package jd.plugins.decrypter;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;

import jd.PluginWrapper;
import jd.http.Browser;
import jd.http.Cookie;
import jd.http.Cookies;
import jd.http.Request;
import jd.http.URLConnectionAdapter;
import jd.http.requests.GetRequest;
import jd.http.requests.HeadRequest;
import jd.http.requests.PostRequest;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.parser.html.Form;
import jd.plugins.CryptedLink;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForDecrypt;
import jd.utils.JDUtilities;

import org.appwork.utils.KeyValueStringEntry;
import org.appwork.utils.StringUtils;
import org.jdownloader.captcha.v2.challenge.recaptcha.v1.Recaptcha;
import org.jdownloader.captcha.v2.challenge.recaptcha.v2.CaptchaHelperCrawlerPluginRecaptchaV2;
import org.mozilla.javascript.ConsString;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.ScriptableObject;

/**
 *
 * @author raztoki
 *
 */
@SuppressWarnings({ "deprecation", "unused" })
public abstract class antiDDoSForDecrypt extends PluginForDecrypt {

    public antiDDoSForDecrypt(PluginWrapper wrapper) {
        super(wrapper);
    }

    protected CryptedLink param = null;

    protected boolean useRUA() {
        return false;
    }

    public static final String                    cfRequiredCookies = "__cfduid|cf_clearance";
    public static final String                    icRequiredCookies = "visid_incap_\\d+|incap_ses_\\d+_\\d+";
    public static final String                    suRequiredCookies = "sucuri_cloudproxy_uuid_[a-f0-9]+";
    protected static HashMap<String, Cookies>     antiDDoSCookies   = new HashMap<String, Cookies>();
    protected static AtomicReference<String>      userAgent         = new AtomicReference<String>(null);
    protected final WeakHashMap<Browser, Boolean> browserPrepped    = new WeakHashMap<Browser, Boolean>();

    protected Browser prepBrowser(final Browser prepBr, final String host) {
        if ((browserPrepped.containsKey(prepBr) && browserPrepped.get(prepBr) == Boolean.TRUE)) {
            return prepBr;
        }
        // define custom browser headers and language settings.
        // required for native cloudflare support, without the need to repeat requests.
        try {
            prepBr.addAllowedResponseCodes(new int[] { 429, 503, 504, 520, 521, 522, 523, 525 });
        } catch (final Throwable t) {
        }
        synchronized (antiDDoSCookies) {
            if (!antiDDoSCookies.isEmpty()) {
                for (final Map.Entry<String, Cookies> cookieEntry : antiDDoSCookies.entrySet()) {
                    final String key = cookieEntry.getKey();
                    if (key != null && key.equals(host)) {
                        try {
                            prepBr.setCookies(key, cookieEntry.getValue(), false);
                        } catch (final Throwable e) {
                        }
                    }
                }
            }
        }
        if (useRUA()) {
            if (userAgent.get() == null) {
                /* we first have to load the plugin, before we can reference it */
                JDUtilities.getPluginForHost("mediafire.com");
                userAgent.set(jd.plugins.hoster.MediafireCom.stringUserAgent());
            }
            prepBr.getHeaders().put("User-Agent", userAgent.get());
        }
        prepBr.getHeaders().put("Accept-Language", "en-gb, en;q=0.8");
        prepBr.getHeaders().put("Accept-Charset", null);
        prepBr.getHeaders().put("Pragma", null);
        // we now set
        browserPrepped.put(prepBr, Boolean.TRUE);
        return prepBr;
    }

    /**
     * Gets page <br />
     * - natively supports silly cloudflare anti DDoS crapola
     *
     * @author raztoki
     */
    protected void getPage(final Browser ibr, final String page) throws Exception {
        if (page == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        // virgin browser will have no protocol, we will be able to get from page. existing page request might be with relative paths, we
        // use existing browser session to determine host
        final String host = ibr.getURL() != null ? Browser.getHost(ibr.getURL()) : Browser.getHost(page);
        prepBrowser(ibr, host);
        URLConnectionAdapter con = null;
        try {
            con = ibr.openGetConnection(page);
            readConnection(con, ibr);
        } finally {
            try {
                con.disconnect();
            } catch (Throwable e) {
            }
        }
        antiDDoS(ibr);
        runPostRequestTask(ibr);
    }

    /**
     * Wrapper into getPage(importBrowser, page), where browser = br;
     *
     * @author raztoki
     *
     */
    protected void getPage(final String page) throws Exception {
        getPage(br, page);
    }

    protected void postPage(final Browser ibr, String page, final String postData) throws Exception {
        if (page == null || postData == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        // virgin browser will have no protocol, we will be able to get from page. existing page request might be with relative paths, we
        // use existing browser session to determine host
        final String host = ibr.getURL() != null ? Browser.getHost(ibr.getURL()) : Browser.getHost(page);
        prepBrowser(ibr, host);
        ibr.getHeaders().put("Content-Type", "application/x-www-form-urlencoded");
        URLConnectionAdapter con = null;
        try {
            con = ibr.openPostConnection(page, postData);
            readConnection(con, ibr);
        } finally {
            try {
                con.disconnect();
            } catch (Throwable e) {
            }
            ibr.getHeaders().put("Content-Type", null);
        }
        antiDDoS(ibr);
        runPostRequestTask(ibr);
    }

    /**
     * Wrapper into postPage(importBrowser, page, postData), where browser == this.br;
     *
     * @author raztoki
     *
     */
    protected void postPage(final String page, final String postData) throws Exception {
        postPage(br, page, postData);
    }

    protected void postPage(final Browser ibr, String page, final LinkedHashMap<String, String> param) throws Exception {
        if (page == null || param == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        // virgin browser will have no protocol, we will be able to get from page. existing page request might be with relative paths, we
        // use existing browser session to determine host
        final String host = ibr.getURL() != null ? Browser.getHost(ibr.getURL()) : Browser.getHost(page);
        prepBrowser(ibr, host);
        ibr.getHeaders().put("Content-Type", "application/x-www-form-urlencoded");
        URLConnectionAdapter con = null;
        try {
            con = ibr.openPostConnection(page, param);
            readConnection(con, ibr);
        } finally {
            try {
                con.disconnect();
            } catch (Throwable e) {
            }
            ibr.getHeaders().put("Content-Type", null);
        }
        antiDDoS(ibr);
        runPostRequestTask(ibr);
    }

    /**
     * Wrapper into postPage(importBrowser, page, param), where browser == this.br;
     *
     * @author raztoki
     *
     */
    protected void postPage(final String page, final LinkedHashMap<String, String> param) throws Exception {
        postPage(br, page, param);
    }

    protected void postPageRaw(final Browser ibr, final String page, final String post, final boolean isJson) throws Exception {
        final PostRequest request = ibr.createPostRequest(page, new ArrayList<KeyValueStringEntry>(), null);
        request.setPostDataString(post);
        setContentType(request, isJson);
        // virgin browser will have no protocol, we will be able to get from page. existing page request might be with relative paths, we
        // use existing browser session to determine host
        final String host = ibr.getURL() != null ? Browser.getHost(ibr.getURL()) : Browser.getHost(page);
        prepBrowser(ibr, host);
        URLConnectionAdapter con = null;
        try {
            con = ibr.openRequestConnection(request);
            readConnection(con, ibr);
        } finally {
            try {
                con.disconnect();
            } catch (Throwable e) {
            }
            ibr.getHeaders().put("Content-Type", null);
        }
        antiDDoS(ibr);
        runPostRequestTask(ibr);
    }

    protected void setContentType(final PostRequest request, final boolean isJson) {
        if (request != null) {
            if (isJson) {
                request.setContentType("application/json");
            } else {
                request.setContentType("application/x-www-form-urlencoded");
            }
        }
    }

    /**
     * Wrapper into postPageRaw(importBrowser, page, post), where browser == this.br;
     *
     * @author raztoki
     *
     */
    protected void postPageRaw(final String page, final String post) throws Exception {
        postPageRaw(br, page, post, false);
    }

    /**
     * Wrapper into postPageRaw(importBrowser, page, post), where browser == this.br;
     *
     * @author raztoki
     *
     */
    protected void postPageRaw(final String page, final String post, final boolean isJson) throws Exception {
        postPageRaw(br, page, post, isJson);
    }

    protected void submitForm(final Browser ibr, final Form form) throws Exception {
        if (form == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        // virgin browser will have no protocol, we will be able to get from page. existing page request might be with relative paths, we
        // use existing browser session to determine host
        final String host = ibr.getURL() != null ? Browser.getHost(ibr.getURL()) : Browser.getHost(form.getAction());
        prepBrowser(ibr, host);
        if (Form.MethodType.POST.equals(form.getMethod())) {
            // if the form doesn't contain an action lets set one based on current br.getURL().
            if (form.getAction() == null || form.getAction().equals("")) {
                form.setAction(ibr.getURL());
            }
            ibr.getHeaders().put("Content-Type", "application/x-www-form-urlencoded");
        }
        URLConnectionAdapter con = null;
        try {
            con = ibr.openFormConnection(form);
            readConnection(con, ibr);
        } finally {
            try {
                con.disconnect();
            } catch (Throwable e) {
            }
            ibr.getHeaders().put("Content-Type", null);
        }
        antiDDoS(ibr);
        runPostRequestTask(ibr);
    }

    /**
     * Wrapper into sendForm(importBrowser, form), where browser == this.br;
     *
     * @author raztoki
     *
     */
    protected void submitForm(final Form form) throws Exception {
        submitForm(br, form);
    }

    protected void sendRequest(final Browser ibr, final Request request) throws Exception {
        final String host = Browser.getHost(request.getUrl());
        prepBrowser(ibr, host);
        URLConnectionAdapter con = null;
        try {
            con = ibr.openRequestConnection(request);
            readConnection(con, ibr);
        } finally {
            try {
                con.disconnect();
            } catch (Throwable e) {
            }
            ibr.getHeaders().put("Content-Type", null);
        }
        antiDDoS(ibr);
        runPostRequestTask(ibr);
    }

    /**
     * Wrapper into sendRequest(importBrowser, form), where browser == this.br;
     *
     * @author raztoki
     *
     */
    protected void sendRequest(final Request request) throws Exception {
        sendRequest(br, request);
    }

    /**
     * Override when you want to run a post request task. This is run after getPage/postPage/submitForm/sendRequest
     *
     * @param ibr
     */
    protected void runPostRequestTask(final Browser ibr) throws Exception {
    }

    /**
     * @author razotki
     * @author jiaz
     * @param con
     * @param ibr
     * @throws IOException
     * @throws PluginException
     */
    public void readConnection(final URLConnectionAdapter con, final Browser ibr) throws IOException, PluginException {
        InputStream is = null;
        try {
            /* beta */
            try {
                con.setAllowedResponseCodes(new int[] { con.getResponseCode() });
            } catch (final Throwable e2) {
            }
            is = con.getInputStream();
        } catch (IOException e) {
            // stable fail over
            is = con.getErrorStream();
        }
        String encoding;
        final String t = readInputStream(is, encoding = ibr.getRequest().getCustomCharset());
        if (t != null) {
            logger.fine("\r\n" + t);
            ibr.getRequest().setHtmlCode(t);
            if (ibr.getRequest().isKeepByteArray() || ibr.isKeepResponseContentBytes()) {
                ibr.getRequest().setKeepByteArray(true);
                ibr.getRequest().setResponseBytes(t.getBytes(encoding == null ? "UTF-8" : encoding));
            }
        }
    }

    /**
     * @author razotki
     * @author jiaz
     * @param is
     * @return
     * @throws UnsupportedEncodingException
     * @throws IOException
     */
    private String readInputStream(final InputStream is, final String encoding) throws UnsupportedEncodingException, IOException {
        BufferedReader f = null;
        try {
            f = new BufferedReader(new InputStreamReader(is, encoding == null ? "UTF-8" : encoding));
            String line;
            final StringBuilder ret = new StringBuilder();
            final String sep = System.getProperty("line.separator");
            while ((line = f.readLine()) != null) {
                if (ret.length() > 0) {
                    ret.append(sep);
                }
                ret.append(line);
            }
            return ret.toString();
        } finally {
            try {
                is.close();
            } catch (final Throwable e) {
            }
        }
    }

    private int responseCode429 = 0;
    private int responseCode5xx = 0;

    /**
     * uses common method antiDDoS
     *
     * @author Jiaz
     * @author raztoki
     */
    protected URLConnectionAdapter openAntiDDoSRequestConnection(final Browser ibr, Request request) throws Exception {
        final String host = Browser.getHost(request.getURL());
        prepBrowser(ibr, host);
        ibr.openRequestConnection(request);
        antiDDoS(ibr, request);
        return ibr.getHttpConnection();
    }

    /**
     * wrapper for antiDDoS(Browser)
     *
     * @param ibr
     * @throws Exception
     */
    protected final void antiDDoS(final Browser ibr) throws Exception {
        antiDDoS(ibr, null);
    }

    /**
     * Performs Cloudflare, Incapsula, Sucuri requirements.<br />
     * Auto fill out the required fields and updates antiDDoSCookies session.<br />
     * Always called after Browser Request!
     *
     * @version 0.03
     * @author raztoki
     **/
    protected final void antiDDoS(final Browser ibr, final Request request) throws Exception {
        if (ibr == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        final Cookies cookies = new Cookies();
        if (ibr.getHttpConnection() != null) {
            // Cloudflare
            if (requestHeadersHasKeyNValueContains(ibr, "server", "cloudflare-nginx")) {
                processCloudflare(ibr, request, cookies);
            }
            // Incapsula
            else if (containsIncapsulaCookies(ibr)) {
                processIncapsula(ibr, cookies);
            }
            // Sucuri
            else if (requestHeadersHasKeyNValueContains(ibr, "server", "Sucuri/Cloudproxy")) {
                processSucuri(ibr, cookies);
            }
            // save the session!
            synchronized (antiDDoSCookies) {
                antiDDoSCookies.put(ibr.getHost(), cookies);
            }
        }
    }

    private void processCloudflare(final Browser ibr, final Request request, final Cookies cookies) throws Exception {
        final int responseCode = ibr.getHttpConnection().getResponseCode();
        if (request != null) {
            // used soley by openAntiDDoSRequestConnection/DirectHTTP plugin when open connection is used.
            if (request instanceof HeadRequest && (responseCode == 403 || responseCode == 429 || (responseCode >= 503 && responseCode <= 525))) {
                openAntiDDoSRequestConnection(ibr, new GetRequest(request));
                return;
            }
            // all blocks with cloudflare are within "text/*", we only need to follow the connection when this happens! Otherwise
            // data could be grabbed and we don't want that to happen.
            if (StringUtils.startsWithCaseInsensitive(ibr.getHttpConnection().getContentType(), "text/")) {
                ibr.followConnection();
            }
        }
        Form cloudflare = ibr.getFormbyProperty("id", "ChallengeForm");
        if (cloudflare == null) {
            cloudflare = ibr.getFormbyProperty("id", "challenge-form");
        }
        if (responseCode == 403 && cloudflare != null) {
            // recapthcha v2
            if (cloudflare.containsHTML("class=\"g-recaptcha\"")) {
                final Form cf = cloudflare;
                final String recaptchaV2Response = new CaptchaHelperCrawlerPluginRecaptchaV2(this, ibr) {

                    @Override
                    public String getSiteKey() {
                        return getSiteKey(cf.getHtmlCode());
                    }

                    @Override
                    public String getSecureToken() {
                        return getSecureToken(cf.getHtmlCode());
                    }

                }.getToken();
                cloudflare.put("g-recaptcha-response", Encoding.urlEncode(recaptchaV2Response));
            }
            // recapthca v1
            else if (cloudflare.hasInputFieldByName("recaptcha_response_field")) {
                // they seem to add multiple input fields which is most likely meant to be corrected by js ?
                // we will manually remove all those
                while (cloudflare.hasInputFieldByName("recaptcha_response_field")) {
                    cloudflare.remove("recaptcha_response_field");
                }
                while (cloudflare.hasInputFieldByName("recaptcha_challenge_field")) {
                    cloudflare.remove("recaptcha_challenge_field");
                }
                // this one is null, needs to be ""
                if (cloudflare.hasInputFieldByName("message")) {
                    cloudflare.remove("message");
                    cloudflare.put("messsage", "\"\"");
                }
                // recaptcha bullshit,
                String apiKey = cloudflare.getRegex("/recaptcha/api/(?:challenge|noscript)\\?k=([A-Za-z0-9%_\\+\\- ]+)").getMatch(0);
                if (apiKey == null) {
                    apiKey = ibr.getRegex("/recaptcha/api/(?:challenge|noscript)\\?k=([A-Za-z0-9%_\\+\\- ]+)").getMatch(0);
                    if (apiKey == null) {
                        throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                    }
                }
                final Recaptcha rc = new Recaptcha(ibr, this);
                rc.setId(apiKey);
                rc.load();
                final File cf = rc.downloadCaptcha(getLocalCaptchaFile());
                final String response = getCaptchaCode("recaptcha", cf, param);
                if (inValidate(response)) {
                    throw new PluginException(LinkStatus.ERROR_CAPTCHA);
                }
                cloudflare.put("recaptcha_challenge_field", rc.getChallenge());
                cloudflare.put("recaptcha_response_field", Encoding.urlEncode(response));
            }
            if (request != null) {
                ibr.openFormConnection(cloudflare);
            } else {
                ibr.submitForm(cloudflare);
            }
            if (ibr.getFormbyProperty("id", "ChallengeForm") != null || ibr.getFormbyProperty("id", "challenge-form") != null) {
                logger.warning("Wrong captcha");
                throw new PluginException(LinkStatus.ERROR_CAPTCHA);
            }
            // if it works, there should be a redirect.
            if (!ibr.isFollowingRedirects() && ibr.getRedirectLocation() != null) {
                ibr.getPage(ibr.getRedirectLocation());
            }
        } else if (ibr.getHttpConnection().getResponseCode() == 403 && ibr.containsHTML("<p>The owner of this website \\([^\\)]*" + Pattern.quote(ibr.getHost()) + "\\) has banned your IP address") && ibr.containsHTML("<title>Access denied \\| [^<]*" + Pattern.quote(ibr.getHost()) + " used CloudFlare to restrict access</title>")) {
            // website address could be www. or what ever prefixes, need to make sure
            // eg. within 403 response code,
            // <p>The owner of this website (www.premiumax.net) has banned your IP address (x.x.x.x).</p>
            // also common when proxies are used?? see keep2share.cc jdlog://5562413173041
            String ip = ibr.getRegex("your IP address \\((.*?)\\)\\.</p>").getMatch(0);
            String message = ibr.getHost() + " has banned your IP Address" + (inValidate(ip) ? "!" : "! " + ip);
            logger.warning(message);
            throw new PluginException(LinkStatus.ERROR_FATAL, message);
        } else if (responseCode == 503 && cloudflare != null) {
            // 503 response code with javascript math section && with 5 second pause
            final String[] line1 = ibr.getRegex("var t,r,a,f, (\\w+)=\\{\"(\\w+)\":([^\\}]+)").getRow(0);
            String line2 = ibr.getRegex("(\\;" + line1[0] + "." + line1[1] + ".*?t\\.length\\;)").getMatch(0);
            StringBuilder sb = new StringBuilder();
            sb.append("var a={};\r\nvar t=\"" + Browser.getHost(ibr._getURL(), true) + "\";\r\n");
            sb.append("var " + line1[0] + "={\"" + line1[1] + "\":" + line1[2] + "}\r\n");
            sb.append(line2);

            ScriptEngineManager mgr = jd.plugins.hoster.DummyScriptEnginePlugin.getScriptEngineManager(this);
            ScriptEngine engine = mgr.getEngineByName("JavaScript");
            long answer = ((Number) engine.eval(sb.toString())).longValue();
            cloudflare.getInputFieldByName("jschl_answer").setValue(answer + "");
            Thread.sleep(5500);
            if (request != null) {
                ibr.openFormConnection(cloudflare);
            } else {
                ibr.submitForm(cloudflare);
            }
            // if it works, there should be a redirect.
            if (!ibr.isFollowingRedirects() && ibr.getRedirectLocation() != null) {
                ibr.getPage(ibr.getRedirectLocation());
            }
        } else if (responseCode == 521) {
            // this basically indicates that the site is down, no need to retry.
            // HTTP/1.1 521 Origin Down || <title>api.share-online.biz | 521: Web server is down</title>
            responseCode5xx++;
            throw new PluginException(LinkStatus.ERROR_HOSTER_TEMPORARILY_UNAVAILABLE, "CloudFlare says \"521 Origin Sever\" is down!", 5 * 60 * 1000l);
        } else if (responseCode == 504 || responseCode == 520 || responseCode == 522 || responseCode == 523 || responseCode == 525) {
            // these warrant retry instantly, as it could be just slave issue? most hosts have 2 DNS response to load balance.
            // additional request could work via additional IP
            /**
             * @see clouldflare_504_snippet.html
             */
            // HTTP/1.1 504 Gateway Time-out
            // HTTP/1.1 520 Origin Error
            // HTTP/1.1 522 Origin Connection Time-out
            /**
             * @see cloudflare_523_snippet.html
             */
            // HTTP/1.1 523 Origin Unreachable
            // HTTP/1.1 525 Origin SSL Handshake Error || >CloudFlare is unable to establish an SSL connection to the origin
            // server.<
            // cache system with possible origin dependency... we will wait and retry
            if (responseCode5xx == 4) {
                // this only shows the last error in request, not the previous retries.
                throw new PluginException(LinkStatus.ERROR_HOSTER_TEMPORARILY_UNAVAILABLE, "CloudFlare says \"" + responseCode + " " + ibr.getHttpConnection().getResponseMessage() + "\"", 5 * 60 * 1000l);
            }
            responseCode5xx++;
            // this html based cookie, set by <meta (for responseCode 522)
            // <meta http-equiv="set-cookie" content="cf_use_ob=0; expires=Sat, 14-Jun-14 14:35:38 GMT; path=/">
            String[] metaCookies = ibr.getRegex("<meta http-equiv=\"set-cookie\" content=\"(.*?; expries=.*?; path=.*?\";?(?: domain=.*?;?)?)\"").getColumn(0);
            if (metaCookies != null && metaCookies.length != 0) {
                final List<String> cookieHeaders = Arrays.asList(metaCookies);
                final String date = ibr.getHeaders().get("Date");
                final String host = Browser.getHost(ibr.getURL());
                // get current cookies
                final Cookies ckies = ibr.getCookies(host);
                // add meta cookies to current previous request cookies
                for (int i = 0; i < cookieHeaders.size(); i++) {
                    final String header = cookieHeaders.get(i);
                    ckies.add(Cookies.parseCookies(header, host, date));
                }
                // set ckies as current cookies
                ibr.getHttpConnection().getRequest().setCookies(ckies);
            }

            Thread.sleep(2500);
            // effectively refresh page!
            try {
                sendRequest(ibr, ibr.getRequest().cloneRequest());
            } catch (final Exception t) {
                // we want to preserve proper exceptions!
                if (t instanceof PluginException) {
                    throw t;
                }
                t.printStackTrace();
                throw new PluginException(LinkStatus.ERROR_HOSTER_TEMPORARILY_UNAVAILABLE, "Unexpected CloudFlare related issue", 5 * 60 * 1000l);
            }
            // new sendRequest saves.
            return;
        } else if (responseCode == 429 && ibr.containsHTML("<title>Too Many Requests</title>")) {
            if (responseCode429 == 4) {
                throw new PluginException(LinkStatus.ERROR_HOSTER_TEMPORARILY_UNAVAILABLE);
            }
            responseCode429++;
            // been blocked! need to wait 1min before next request. (says k2sadmin, each site could be configured differently)
            Thread.sleep(61000);
            // try again! -NOTE: this isn't stable compliant-
            try {
                sendRequest(ibr, ibr.getRequest().cloneRequest());
            } catch (final Exception t) {
                // we want to preserve proper exceptions!
                if (t instanceof PluginException) {
                    throw t;
                }
                t.printStackTrace();
                throw new PluginException(LinkStatus.ERROR_HOSTER_TEMPORARILY_UNAVAILABLE);
            }
            return;

            // new code here...
            // <script type="text/javascript">
            // //<![CDATA[
            // try{if (!window.CloudFlare) {var
            // CloudFlare=[{verbose:0,p:1408958160,byc:0,owlid:"cf",bag2:1,mirage2:0,oracle:0,paths:{cloudflare:"/cdn-cgi/nexp/dokv=88e434a982/"},atok:"661da6801927b0eeec95f9f3e160b03a",petok:"107d6db055b8700cf1e7eec1324dbb7be6b978d0-1408974417-1800",zone:"fileboom.me",rocket:"0",apps:{}}];CloudFlare.push({"apps":{"ape":"3a15e211d076b73aac068065e559c1e4"}});!function(a,b){a=document.createElement("script"),b=document.getElementsByTagName("script")[0],a.async=!0,a.src="//ajax.cloudflare.com/cdn-cgi/nexp/dokv=97fb4d042e/cloudflare.min.js",b.parentNode.insertBefore(a,b)}()}}catch(e){};
            // //]]>
            // </script>

        } else {
            // nothing wrong, or something wrong (unsupported format)....
            // commenting out return prevents caching of cookies per request
            // return;
        }

        // get cookies we want/need.
        // refresh these with every getPage/postPage/submitForm?
        final Cookies add = ibr.getCookies(ibr.getHost());
        for (final Cookie c : add.getCookies()) {
            if (new Regex(c.getKey(), cfRequiredCookies).matches()) {
                cookies.add(c);
            }
        }
    }

    private void processIncapsula(final Browser ibr, final Cookies cookies) throws Exception {
        // they also rdns there servers to themsevles. we could use this.. but I think cookie is fine for now
        // nslookup 103.28.250.173
        // Name: 103.28.250.173.ip.incapdns.net
        // Address: 103.28.250.173

        // they also have additional header response, X-Iinfo or ("X-CDN", "Incapsula")**. ** = optional

        // not sure if this is the best way to detect this. could be done via line count (13) or html tag count (13 also including
        // closing tags)..
        final String functionz = ibr.getRegex("function\\(\\)\\s*\\{\\s*var z\\s*=\\s*\"\";.*?\\}\\)\\(\\);").getMatch(-1);
        final String[] crudeyes = ibr.toString().split("[\r\n]{1,2}");
        if (functionz != null && crudeyes != null && crudeyes.length < 15) {
            final String b = new Regex(functionz, "var b\\s*=\\s*\"(.*?)\";").getMatch(0);
            if (b != null) {
                String z = "";
                for (int i = 0; i < b.length(); i += 2) {
                    z = z + Integer.parseInt(b.substring(i, i + 2), 16) + ",";
                }
                z = z.substring(0, z.length() - 1);
                String a = "";
                for (String zz : z.split(",")) {
                    final int zzz = Integer.parseInt(zz);
                    a += Character.toString((char) zzz);
                }
                // now z contains two requests, first one unlocks, second is feedback, third is failover. don't think any
                // feedbacks/failovers are required but most likely improves your cookie health.
                final String c = new Regex(a, "xhr\\.open\\(\"GET\",\"(/_Incapsula_Resource\\?.*?)\"").getMatch(0);
                if (c != null) {
                    final Browser ajax = ibr.cloneBrowser();
                    ajax.getHeaders().put("Accept", "*/*");
                    ajax.getHeaders().put("Cache-Control", null);
                    ajax.getPage(c);
                    // now it should say "window.location.reload(true);"
                    if (ajax.containsHTML("window\\.location\\.reload\\(true\\);")) {
                        ibr.getPage(ibr.getURL());
                    } else {
                        // lag/delay between = no html
                        // should only happen in debug mode breakpointing!
                        // System.out.println("error");
                    }
                }
            }
        }
        // written support based on logged output from JDownloader.. not the best, but here goes! refine if it fails!
        // recaptcha events are loaded from iframe,
        // z reference is within the script src url (contains md5checksum), once decoded their is no magic within, unlike above.

        // on a single line
        if (crudeyes != null && crudeyes.length == 1) {
            // xinfo in the iframe is the same as header info...
            final String xinfo = ibr.getHttpConnection().getHeaderField("X-Iinfo");
            // so far in logs ive only seen this trigger after one does NOT answer a function z..
            String azas = "<iframe[^<]*\\s+src=\"(/_Incapsula_Resource\\?(?:[^\"]+&|)xinfo=" + (!inValidate(xinfo) ? Pattern.quote(xinfo) : "") + "[^\"]*)\"";
            final String iframe = ibr.getRegex(azas).getMatch(0);
            if (iframe != null) {
                final Browser ifr = ibr.cloneBrowser();
                // will need referrer,, but what other custom headers??
                ifr.getPage(iframe);
                final Form captcha = ifr.getFormbyProperty("id", "captcha-form");
                if (captcha == null) {
                    System.out.println("error");
                }
                String apiKey = captcha.getRegex("/recaptcha/api/(?:challenge|noscript)\\?k=([A-Za-z0-9%_\\+\\- ]+)").getMatch(0);
                if (apiKey == null) {
                    apiKey = "6Lebls0SAAAAAHo72LxPsLvFba0g1VzknU83sJLg";
                }
                final Recaptcha rc = new Recaptcha(ibr, this);
                rc.setId(apiKey);
                rc.load();
                final File cf = rc.downloadCaptcha(getLocalCaptchaFile());
                final String response = getCaptchaCode("recaptcha", cf, param);
                if (inValidate(response)) {
                    throw new PluginException(LinkStatus.ERROR_CAPTCHA);
                }
                // they have no script value and our form parser adds the input field when it shouldn't
                while (captcha.hasInputFieldByName("recaptcha_response_field")) {
                    captcha.remove("recaptcha_response_field");
                }
                captcha.put("recaptcha_challenge_field", rc.getChallenge());
                captcha.put("recaptcha_response_field", Encoding.urlEncode(response));
                ifr.submitForm(captcha);
                if (ifr.getFormbyProperty("id", "captcha-form") != null) {
                    logger.warning("Wrong captcha");
                    throw new PluginException(LinkStatus.ERROR_CAPTCHA);
                } else if (ifr.containsHTML(">window\\.parent\\.location\\.reload\\(true\\);<")) {
                    // they show z again after captcha...
                    getPage(ibr.getURL());
                    // above request saves, as it re-enters this method!
                    return;
                } else {
                    // shouldn't happen???
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
            }
        }

        // get cookies we want/need.
        // refresh these with every getPage/postPage/submitForm?
        final Cookies add = ibr.getCookies(ibr.getHost());
        for (final Cookie c : add.getCookies()) {
            if (new Regex(c.getKey(), icRequiredCookies).matches()) {
                cookies.add(c);
            }
        }
    }

    /**
     * <a href="https://kb.sucuri.net/cloudproxy/index">CloudProxy</a> antiDDoS method by <a href="https://sucuri.net/">Sucuri</a>,
     *
     * @author raztoki
     * @throws Exception
     */
    private void processSucuri(final Browser ibr, final Cookies cookies) throws Exception {
        if (ibr.containsHTML("<title>You are being redirected\\.\\.\\.</title>")) {
            // S = base encoded, the js is to just undo that.
            final String base64 = ibr.getRegex("(?i-)S\\s*=\\s*'([^']+)';").getMatch(0);
            if (base64 == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            String decode = Encoding.Base64Decode(base64);
            decode = decode.replace("location.reload();", "").replace("document.cookie", "y");

            Object result = new Object();
            if (base64 != null) {
                try {
                    final ScriptEngineManager manager = jd.plugins.hoster.DummyScriptEnginePlugin.getScriptEngineManager(this);
                    final ScriptEngine engine = manager.getEngineByName("javascript");
                    engine.eval("document = \"\";");
                    engine.eval(decode);
                    final ConsString y = (ConsString) engine.get("y");
                    ibr.setCookie(ibr.getHost(), y.toString().split("=")[0], y.toString().split("=")[1]);
                } catch (final Throwable e) {
                    e.printStackTrace();
                }
                ibr.getPage(ibr.getURL());
            }
        }
        // get cookies we want/need.
        // refresh these with every getPage/postPage/submitForm?
        final Cookies add = ibr.getCookies(ibr.getHost());
        for (final Cookie c : add.getCookies()) {
            if (new Regex(c.getKey(), suRequiredCookies).matches()) {
                cookies.add(c);
            }
        }
    }

    /**
     * returns true if browser contains cookies that match expected
     *
     * @author raztoki
     * @param ibr
     * @return
     */
    protected boolean containsIncapsulaCookies(final Browser ibr) {
        final Cookies add = ibr.getCookies(ibr.getHost());
        for (final Cookie c : add.getCookies()) {
            if (new Regex(c.getKey(), icRequiredCookies).matches()) {
                return true;
            }
        }
        return false;
    }

    /**
     *
     * @author raztoki
     */
    @SuppressWarnings("unused")
    private boolean requestHeadersHasKeyNValueStartsWith(final Browser ibr, final String k, final String v) {
        if (k == null || v == null || ibr == null || ibr.getHttpConnection() == null) {
            return false;
        }
        if (ibr.getHttpConnection().getHeaderField(k) != null && ibr.getHttpConnection().getHeaderField(k).toLowerCase(Locale.ENGLISH).startsWith(v.toLowerCase(Locale.ENGLISH))) {
            return true;
        }
        return false;
    }

    /**
     *
     * @author raztoki
     */
    private boolean requestHeadersHasKeyNValueContains(final Browser ibr, final String k, final String v) {
        if (k == null || v == null || ibr == null || ibr.getHttpConnection() == null) {
            return false;
        }
        if (ibr.getHttpConnection().getHeaderField(k) != null && ibr.getHttpConnection().getHeaderField(k).toLowerCase(Locale.ENGLISH).contains(v.toLowerCase(Locale.ENGLISH))) {
            return true;
        }
        return false;
    }

    /**
     * Wrapper to return all browser cookies except cloudflare session cookies.
     *
     * @param host
     * @return
     */
    protected final HashMap<String, String> fetchCookies(final String host) {
        return fetchCookies(br, host);
    }

    /**
     * Generic method return all browser cookies except cloudflare session cookies.
     *
     * @param br
     * @param host
     * @return
     */
    protected final HashMap<String, String> fetchCookies(final Browser br, final String host) {
        final HashMap<String, String> cookies = new HashMap<String, String>();
        final Cookies add = br.getCookies(host);
        for (final Cookie c : add.getCookies()) {
            if (!c.getKey().matches(cfRequiredCookies + "|" + icRequiredCookies + "|" + suRequiredCookies)) {
                cookies.put(c.getKey(), c.getValue());
            }
        }
        return cookies;
    }

    /**
     * Validates string to series of conditions, null, whitespace, or "". This saves effort factor within if/for/while statements
     *
     * @param s
     *            Imported String to match against.
     * @return <b>true</b> on valid rule match. <b>false</b> on invalid rule match.
     * @author raztoki
     */
    protected boolean inValidate(final String s) {
        if (s == null || s.matches("\\s+") || s.equals("")) {
            return true;
        } else {
            return false;
        }
    }

    /**
     * Wrapper<br/>
     * Tries to return value of key from JSon response, from String source.
     *
     * @author raztoki
     */
    protected final String getJson(final String source, final String key) {
        return jd.plugins.hoster.K2SApi.JSonUtils.getJson(source, key);
    }

    /**
     * Wrapper<br/>
     * Tries to return value of key from JSon response, from default 'br' Browser.
     *
     * @author raztoki
     */
    protected final String getJson(final String key) {
        return jd.plugins.hoster.K2SApi.JSonUtils.getJson(br.toString(), key);
    }

    /**
     * Wrapper<br/>
     * Tries to return value of key from JSon response, from provided Browser.
     *
     * @author raztoki
     */
    protected final String getJson(final Browser ibr, final String key) {
        return jd.plugins.hoster.K2SApi.JSonUtils.getJson(ibr.toString(), key);
    }

    /**
     * Wrapper<br/>
     * Tries to return value given JSon Array of Key from JSon response provided String source.
     *
     * @author raztoki
     */
    protected final String getJsonArray(final String source, final String key) {
        return jd.plugins.hoster.K2SApi.JSonUtils.getJsonArray(source, key);
    }

    /**
     * Wrapper<br/>
     * Tries to return value given JSon Array of Key from JSon response provided Browser.
     *
     * @author raztoki
     */
    protected final String getJsonArray(final Browser ibr, final String key) {
        return jd.plugins.hoster.K2SApi.JSonUtils.getJsonArray(ibr.toString(), key);
    }

    /**
     * Wrapper<br/>
     * Tries to return value given JSon Array of Key from JSon response, from default 'br' Browser.
     *
     * @author raztoki
     */
    protected final String getJsonArray(final String key) {
        return jd.plugins.hoster.K2SApi.JSonUtils.getJsonArray(br.toString(), key);
    }

    /**
     * Wrapper<br/>
     * Tries to return String[] value from provided JSon Array
     *
     * @author raztoki
     * @param source
     * @return
     */
    protected final String[] getJsonResultsFromArray(final String source) {
        return jd.plugins.hoster.K2SApi.JSonUtils.getJsonResultsFromArray(source);
    }

    /**
     * Wrapper<br/>
     * Tries to gather nested \"key\":{.*?} from default Browser
     *
     * @author raztoki
     * @param key
     * @return
     */
    protected final String getJsonNested(final String key) {
        return jd.plugins.hoster.K2SApi.JSonUtils.getJsonNested(br.toString(), key);
    }

    /**
     * Wrapper<br/>
     * Tries to gather nested \"key\":{.*?} from imported Browser
     *
     * @author raztoki
     * @param key
     * @return
     */
    protected final String getJsonNested(final Browser ibr, final String key) {
        return jd.plugins.hoster.K2SApi.JSonUtils.getJsonNested(ibr.toString(), key);
    }

    /**
     * Wrapper<br/>
     * Tries to gather nested \"key\":{.*?} from source
     *
     * @author raztoki
     * @param key
     * @return
     */
    protected final String getJsonNested(final String source, final String key) {
        return jd.plugins.hoster.K2SApi.JSonUtils.getJsonNested(source, key);
    }

    /**
     * Wrapper<br/>
     * Creates and or Ammends Strings ready for JSon requests, with the correct JSon formatting and escaping.
     *
     * @author raztoki
     * @param source
     * @param key
     * @param value
     * @return
     */
    protected final String ammendJson(final String source, final String key, final Object value) {
        return jd.plugins.hoster.K2SApi.JSonUtils.ammendJson(source, key, value);
    }

    /**
     * supports cloudflare email protection crap. because email could be multiple times on a page and to reduce false positives input the
     * specified component to decode.
     *
     *
     * @author raztoki
     * @return
     */
    public static final String getStringFromCloudFlareEmailProtection(final String input) {
        // js component. hardcoded for now.
        final String a = new Regex(input, "data-cfemail\\s*=\\s*\"([a-f0-9]+)\"").getMatch(0);
        Object result = new Object();
        if (a != null) {
            Context cx = null;
            try {
                cx = ContextFactory.getGlobal().enterContext();
                ScriptableObject scope = cx.initStandardObjects();
                result = cx.evaluateString(scope, "var e, r, n, i, a = '" + a + "';if (a) { for (e = \"\", r = parseInt(a.substr(0, 2), 16), n = 2; a.length - n; n += 2) { i = parseInt(a.substr(n, 2), 16) ^ r; e += String.fromCharCode(i); } }", "<cmd>", 1, null);
            } catch (final Throwable e) {
                e.printStackTrace();
            } finally {
                Context.exit();
            }
        }
        return result != null ? result.toString() : null;
    }

}
