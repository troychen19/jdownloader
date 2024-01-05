package jd.plugins.hoster;

import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.appwork.storage.TypeRef;
import org.appwork.utils.StringUtils;
import org.appwork.utils.encoding.URLEncode;
import org.appwork.utils.formatter.SizeFormatter;
import org.appwork.utils.formatter.TimeFormatter;
import org.appwork.utils.net.URLHelper;
import org.jdownloader.plugins.components.usenet.UsenetAccountConfigInterface;
import org.jdownloader.plugins.components.usenet.UsenetServer;
import org.jdownloader.scripting.JavaScriptEngineFactory;
import org.jdownloader.settings.GraphicalUserInterfaceSettings.SIZEUNIT;
import org.jdownloader.settings.staticreferences.CFG_GUI;

import jd.PluginWrapper;
import jd.controlling.proxy.AbstractProxySelectorImpl;
import jd.http.Browser;
import jd.http.Cookies;
import jd.http.requests.PostRequest;
import jd.nutils.encoding.Encoding;
import jd.parser.html.Form;
import jd.plugins.Account;
import jd.plugins.AccountInfo;
import jd.plugins.AccountInvalidException;
import jd.plugins.DownloadLink;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;

@HostPlugin(revision = "$Revision$", interfaceVersion = 3, names = { "usenext.com" }, urls = { "" })
public class UsenextCom extends UseNet {
    public UsenextCom(PluginWrapper wrapper) {
        super(wrapper);
        this.enablePremium("https://www.usenext.com/signup");
    }

    @Override
    public String getAGBLink() {
        return "https://www.usenext.com/terms";
    }

    private final String FLATRATE_DOMAIN = "flat.usenext.de";

    public static interface UsenextConfigInterface extends UsenetAccountConfigInterface {
    };

    @Override
    public void update(final DownloadLink downloadLink, final Account account, long bytesTransfered) throws PluginException {
        final UsenetServer server = getLastUsedUsenetServer();
        /**
         * If the "flatrate domain" is in use, do not substract traffic from users' account. </br>
         * Only substract traffic if no domain is given or a non-flatrate domain is given.
         */
        if (server == null || !StringUtils.equalsIgnoreCase(FLATRATE_DOMAIN, server.getHost())) {
            super.update(downloadLink, account, bytesTransfered);
        }
    }

    @Override
    public int getMaxSimultanDownload(DownloadLink link, Account account, AbstractProxySelectorImpl proxy) {
        if (account != null) {
            final UsenetAccountConfigInterface config = getAccountJsonConfig(account);
            if (config != null && StringUtils.equalsIgnoreCase(FLATRATE_DOMAIN, config.getHost())) {
                /* Flatrate domain does not use up users' traffic but therefore has a connection limit in place. */
                return 4;
            }
        }
        return super.getMaxSimultanDownload(link, account, proxy);
    }

    private long parseNumber(Map<String, Object> map, String id, long def) throws Exception {
        final Object value = map.get(id);
        if (value == null) {
            if (def == -1) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT, "not available:" + id);
            } else {
                return def;
            }
        }
        String unit = (String) map.get("unitResourceStringKey");
        if (StringUtils.contains(unit, "GIGA") || StringUtils.equals(unit, "UNX_UNIT_GIGABYTES")) {
            unit = "GB";
        } else if (StringUtils.contains(unit, "MEGA")) {
            unit = "MB";
        } else if (StringUtils.contains(unit, "TERA")) {
            unit = "TB";
        } else if (StringUtils.equals(unit, "UNX_UNIT_BYTES")) {
            unit = "B";
        } else {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT, "unsupported:" + unit);
        }
        return SizeFormatter.getSize(value + unit);
    }

    @Override
    public AccountInfo fetchAccountInfo(final Account account) throws Exception {
        setBrowserExclusive();
        final AccountInfo ai = new AccountInfo();
        br.setFollowRedirects(true);
        final Cookies cookies = account.loadCookies("");
        boolean freshLoginNeeded = true;
        final String dashboardUrlRelative = "/ma";
        if (cookies != null) {
            br.setCookies(cookies);
            getPage(br, "https://www." + getHost() + dashboardUrlRelative);
            if (this.isLoggedinHTML(br)) {
                logger.info("Cookie login successful");
                freshLoginNeeded = false;
            } else {
                logger.info("Cookie login failed");
                br.clearCookies(null);
                account.clearCookies("");
            }
        }
        if (freshLoginNeeded) {
            logger.info("Performing full login");
            getPage(br, "https://www." + getHost() + "/");
            String clientID = br.getRegex("src\\s*=\\s*\"(/App[^\"]*\\.js)\"").getMatch(0);
            if (clientID != null) {
                final Browser brc = br.cloneBrowser();
                brc.getPage(clientID);
                clientID = brc.getRegex("client_id\\s*:\\s*\"(.*?)\"").getMatch(0);
            }
            if (clientID == null) {
                logger.warning("Fallback to static clientID value");
                clientID = "852f41f8997141c5b9b59e6d15e03f33"; // 2023-01-04
            }
            getPage(br, "https://auth." + getHost() + "/login?culture=de-DE&client_id=" + URLEncode.encodeURIComponent(clientID) + "&CustomCSS=https%3A%2F%2Fwww.usenext.com%2Fauth-css%2Fauth.override.css&returnUrl=https%3A%2F%2Fwww.usenext.com%2F%3Fclient_id%3D" + URLEncode.encodeURIComponent(clientID));
            final Form login = br.getFormbyKey("username");
            if (login == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            login.put("username", Encoding.urlEncode(account.getUser()));
            login.put("password", Encoding.urlEncode(account.getPass()));
            submitForm(br, login);
            getPage(br, "https://www." + getHost() + dashboardUrlRelative);
            if (!isLoggedinHTML(br)) {
                throw new AccountInvalidException();
            }
        }
        /* At this point login was successful and all that's left to do is to obtain account information. */
        final String api_url = "https://janus.usenext.com";
        account.saveCookies(br.getCookies(br.getHost()), "");
        final PostRequest postRequest = br.createJSonPostRequest(URLHelper.parseLocation(new URL(api_url), "/graphql"),
                "{\"operationName\":\"DashboardInformation\",\"variables\":{},\"query\":\"query DashboardInformation {\\n  radiusData {\\n    volume {\\n      remaining\\n      total\\n      unitResourceStringKey\\n    }\\n    extraBoost {\\n      remaining\\n      total\\n      unitResourceStringKey\\n    }\\n  }\\n  cancellationInformation {\\n    isContractLocked\\n    hasWithdrawableCancellation\\n    isServiceDenied\\n    isInCancellationPeriod\\n    cancellationProcess {\\n      createDate\\n    }\\n  }\\n  currentServiceRoundUpgradeData {\\n    hasPendingUpgrade\\n    isLastUpgrade\\n    accountingPeriod {\\n      remaining\\n      total\\n      unitResourceStringKey\\n    }\\n  }\\n  serviceInformation {\\n    currentServiceRound {\\n      currEndDate\\n      startDate\\n      article {\\n        id\\n        name\\n        articleTypeId\\n        priceNet\\n        priceGross\\n        volumeGb\\n        runtime\\n        runtimeUnit\\n      }\\n      invoice {\\n        id\\n        createDate\\n        uuid\\n        invoiceStatePaths {\\n          invoiceStateId\\n          isCurrent\\n        }\\n      }\\n    }\\n    nextServiceRoundBeginDate\\n    nextArticle {\\n      id\\n      name\\n      articleTypeId\\n      priceNet\\n      priceGross\\n      volumeGb\\n      runtime\\n      runtimeUnit\\n    }\\n  }\\n}\\n\"}");
        postRequest.getHeaders().put("x-ui-language", "en-US");
        postRequest.getHeaders().put("Origin", "https://www." + br.getHost());
        br.setCurrentURL("https://www." + br.getHost() + "/");
        sendRequest(postRequest);
        final Map<String, Object> json = restoreFromString(br.getRequest().getHtmlCode(), TypeRef.MAP);
        final Map<String, Object> volume = (Map<String, Object>) JavaScriptEngineFactory.walkJson(json, "data/radiusData/volume");
        final long trafficTotal = parseNumber(volume, "total", -1);
        final long trafficRemaining = parseNumber(volume, "remaining", -1);
        final Map<String, Object> extraBoost = (Map<String, Object>) JavaScriptEngineFactory.walkJson(json, "data/radiusData/extraBoost");
        String accountStatusAdditionalText = null;
        if (extraBoost != null) {
            final long boostTotal = parseNumber(extraBoost, "total", -1);
            final long boostRemaining = parseNumber(extraBoost, "remaining", -1);
            ai.setTrafficMax(trafficTotal + boostTotal);
            ai.setTrafficLeft(trafficRemaining + boostRemaining);
            final SIZEUNIT maxSizeUnit = (SIZEUNIT) CFG_GUI.MAX_SIZE_UNIT.getValue();
            accountStatusAdditionalText = "Boost remaining: " + SIZEUNIT.formatValue(maxSizeUnit, boostRemaining) + "/" + SIZEUNIT.formatValue(maxSizeUnit, boostTotal);
        } else {
            ai.setTrafficMax(trafficTotal);
            ai.setTrafficLeft(trafficRemaining);
        }
        final Map<String, Object> currentServiceRound = (Map<String, Object>) JavaScriptEngineFactory.walkJson(json, "data/serviceInformation/currentServiceRound");
        final String currEndDate = (String) currentServiceRound.get("currEndDate");
        final Date expireDate = TimeFormatter.parseDateString(currEndDate);
        String accountStatusPackageText = null;
        if (expireDate != null) {
            ai.setValidUntil(expireDate.getTime());
            accountStatusPackageText = (String) JavaScriptEngineFactory.walkJson(currentServiceRound, "article/name");
        }
        if (accountStatusPackageText == null) {
            accountStatusPackageText = account.getType().getLabel();
        }
        account.setMaxSimultanDownloads(30);
        account.setRefreshTimeout(2 * 60 * 60 * 1000l);
        ai.setProperty("multiHostSupport", Arrays.asList(new String[] { "usenet" }));
        if (accountStatusAdditionalText != null) {
            ai.setStatus(accountStatusPackageText + " | " + accountStatusAdditionalText);
        } else {
            ai.setStatus(accountStatusPackageText);
        }
        return ai;
    }

    private boolean isLoggedinHTML(final Browser br) {
        return br.containsHTML("https?://[^/]+/signout\"");
    }

    @Override
    public List<UsenetServer> getAvailableUsenetServer() {
        /* Current list of servers can be found here: https://www.usenext.com/en-US/support -> See "How do I set up my newsreader" */
        final List<UsenetServer> ret = new ArrayList<UsenetServer>();
        ret.addAll(UsenetServer.createServerList("flat.usenext.de", false, 119, 443));
        ret.addAll(UsenetServer.createServerList("flat.usenext.de", true, 563));
        ret.addAll(UsenetServer.createServerList("high.usenext.de", false, 119, 443));
        ret.addAll(UsenetServer.createServerList("high.usenext.de", true, 563));
        /*
         * 2023-01-04: Moved entries for news.usenext.de to bottom as their FAQ does not list this entry anymore but it still seems to work.
         */
        ret.addAll(UsenetServer.createServerList("news.usenext.de", false, 119, 443));
        ret.addAll(UsenetServer.createServerList("news.usenext.de", true, 563));
        return ret;
    }
}
