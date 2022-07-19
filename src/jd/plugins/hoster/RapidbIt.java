//jDownloader - Downloadmanager
//Copyright (C) 2013  JD-Team support@jdownloader.org
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
package jd.plugins.hoster;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.appwork.storage.JSonMapperException;
import org.appwork.storage.JSonStorage;
import org.appwork.storage.TypeRef;
import org.appwork.utils.DebugMode;
import org.appwork.utils.Exceptions;
import org.appwork.utils.StringUtils;
import org.appwork.utils.parser.UrlQuery;
import org.jdownloader.plugins.controller.LazyPlugin;

import jd.PluginWrapper;
import jd.http.Browser;
import jd.plugins.Account;
import jd.plugins.Account.AccountType;
import jd.plugins.AccountInfo;
import jd.plugins.AccountInvalidException;
import jd.plugins.AccountUnavailableException;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;
import jd.plugins.components.MultiHosterManagement;

@HostPlugin(revision = "$Revision$", interfaceVersion = 3, names = { "rapidb.it" }, urls = { "" })
public class RapidbIt extends PluginForHost {
    private static final String          API_BASE               = "https://rapidb.it/api";
    private static MultiHosterManagement mhm                    = new MultiHosterManagement("rapidb.it");
    private static final boolean         resume                 = true;
    private static final int             maxchunks              = 0;
    private static final String          PROPERTY_ACCOUNT_TOKEN = "login_token";

    @SuppressWarnings("deprecation")
    public RapidbIt(PluginWrapper wrapper) {
        super(wrapper);
        this.enablePremium("https://" + this.getHost() + "/pl/buy/level");
    }

    @Override
    public String getAGBLink() {
        return "https://" + this.getHost() + "/pl/page/tos";
    }

    private Browser prepBR(final Browser br) {
        br.setCookiesExclusive(true);
        br.setFollowRedirects(true);
        br.getHeaders().put("User-Agent", "JDownloader");
        return br;
    }

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws IOException, PluginException {
        return AvailableStatus.UNCHECKABLE;
    }

    @Override
    public boolean canHandle(final DownloadLink link, final Account account) throws Exception {
        if (account == null) {
            return false;
        } else {
            return super.canHandle(link, account);
        }
    }

    @Override
    public void handleFree(final DownloadLink link) throws Exception, PluginException {
        throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
    }

    @Override
    public void handlePremium(final DownloadLink link, final Account account) throws Exception {
        throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_ONLY);
    }

    @Override
    public LazyPlugin.FEATURE[] getFeatures() {
        return new LazyPlugin.FEATURE[] { LazyPlugin.FEATURE.MULTIHOST };
    }

    @Override
    public void handleMultiHost(final DownloadLink link, final Account account) throws Exception {
        mhm.runCheck(account, link);
        this.login(account, false);
        if (!DebugMode.TRUE_IN_IDE_ELSE_FALSE) {
            /* TODO: This plugin is still under development */
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        } else {
            /* Only for debugging */
            br.setAllowedResponseCodes(500);
        }
        if (!attemptStoredDownloadurlDownload(link)) {
            final Map<String, Object> postdata = new HashMap<String, Object>();
            postdata.put("url", link.getDefaultPlugin().buildExternalDownloadURL(link, this));
            /* This timestamp is later used to notify the user that everything requested within a specified timespan has been downloaded. */
            postdata.put("group_id", System.currentTimeMillis() / 1000);
            postdata.put("notif_db", false);
            postdata.put("notif_email", false);
            br.postPageRaw(API_BASE + "/services/downloadfile", JSonStorage.serializeToJson(postdata));
            this.checkErrors(account, link);
            final Map<String, Object> entries = JSonStorage.restoreFromString(br.toString(), TypeRef.HASHMAP);
            handleErrorMap(account, link, entries);
            final UrlQuery query = new UrlQuery();
            query.add("id", entries.get("file_id").toString());
            // query.add("id", "6272");
            query.add("filehosting_id", entries.get("filehosting_id").toString());
            // query.add("filename", entries.get("filename"));
            query.add("sort", "id");
            query.add("order", "asc");
            query.add("offset", "0");
            query.add("limit", "1");
            br.getPage(API_BASE + "/files?" + query.toString());
            final Map<String, Object> dlresponse = JSonStorage.restoreFromString(br.toString(), TypeRef.HASHMAP);
            handleErrorMap(account, link, dlresponse);
            final List<Map<String, Object>> files = (List<Map<String, Object>>) dlresponse.get("result");
            final Map<String, Object> file = files.get(0);
            final String dllink = (String) file.get("download_url");
            if (StringUtils.isEmpty(dllink)) {
                mhm.handleErrorGeneric(account, link, "dllinknull", 50, 5 * 60 * 1000l);
            }
            link.setProperty(this.getHost() + "directlink", dllink);
            dl = jd.plugins.BrowserAdapter.openDownload(br, link, dllink, resume, maxchunks);
            if (!this.looksLikeDownloadableContent(dl.getConnection())) {
                try {
                    br.followConnection(true);
                } catch (final IOException e) {
                    logger.log(e);
                }
                mhm.handleErrorGeneric(account, link, "unknown_dl_error", 10, 5 * 60 * 1000l);
            }
        }
        this.dl.startDownload();
    }

    private boolean attemptStoredDownloadurlDownload(final DownloadLink link) throws Exception {
        final String directurlproperty = this.getHost() + "directlink";
        final String url = link.getStringProperty(directurlproperty);
        if (StringUtils.isEmpty(url)) {
            return false;
        }
        boolean valid = false;
        try {
            final Browser brc = br.cloneBrowser();
            dl = new jd.plugins.BrowserAdapter().openDownload(brc, link, url, resume, maxchunks);
            if (this.looksLikeDownloadableContent(dl.getConnection())) {
                valid = true;
                return true;
            } else {
                link.removeProperty(directurlproperty);
                brc.followConnection(true);
                throw new IOException();
            }
        } catch (final Throwable e) {
            logger.log(e);
            return false;
        } finally {
            if (!valid) {
                try {
                    dl.getConnection().disconnect();
                } catch (Throwable ignore) {
                }
                dl = null;
            }
        }
    }

    @Override
    public AccountInfo fetchAccountInfo(final Account account) throws Exception {
        final AccountInfo ai = new AccountInfo();
        login(account, true);
        final Map<String, Object> user = JSonStorage.restoreFromString(br.toString(), TypeRef.HASHMAP);
        br.getPage(API_BASE + "/system/config");
        final Map<String, Object> apiconfig = JSonStorage.restoreFromString(br.toString(), TypeRef.HASHMAP);
        ai.setCreateTime(((Number) user.get("created")).longValue());
        final int level_id = ((Number) user.get("level_id")).intValue();
        final List<Map<String, Object>> levels = (List<Map<String, Object>>) apiconfig.get("levels");
        /* Information about users' current package. */
        final Map<String, Object> level = levels.get(level_id - 1);
        if (level_id == 0) {
            /* Free */
            account.setType(AccountType.FREE);
        } else {
            /* Bronze, Silver, Gold, Platinum */
            account.setType(AccountType.PREMIUM);
        }
        ai.setStatus(level.get("name").toString());
        account.setMaxSimultanDownloads(((Number) level.get("max_sim_downloads")).intValue());
        /* Traffic the user bought in this package */
        final long trafficMax = ((Number) user.get("points")).longValue();
        /* Bought traffic + (daily_free_traffic_mb - daily_used_traffic_bytes) */
        ai.setTrafficLeft(trafficMax + (((Number) level.get("points_free_mb")).longValue() * 1000 * 1000) + ((Number) user.get("points_free_used")).longValue());
        ai.setTrafficMax(trafficMax);
        final ArrayList<String> supportedHosts = new ArrayList<String>();
        final List<Map<String, Object>> filehostings = (List<Map<String, Object>>) apiconfig.get("filehostings");
        for (final Map<String, Object> filehosting : filehostings) {
            final List<String> domains = (List<String>) filehosting.get("domains");
            final String name = filehosting.get("name").toString();
            if (((Number) filehosting.get("status")).intValue() == 1) {
                supportedHosts.addAll(domains);
            } else {
                logger.info("Skipping currently unsupported/offline host: " + name);
            }
        }
        ai.setMultiHostSupport(this, supportedHosts);
        account.setConcurrentUsePossible(true);
        return ai;
    }

    private void login(final Account account, final boolean validateLogins) throws IOException, PluginException, InterruptedException {
        synchronized (account) {
            try {
                prepBR(this.br);
                if (account.hasProperty(PROPERTY_ACCOUNT_TOKEN)) {
                    logger.info("Trying to login via token");
                    br.getHeaders().put("Auth", account.getStringProperty(PROPERTY_ACCOUNT_TOKEN));
                    if (!validateLogins) {
                        /* Do not verify logins */
                        return;
                    } else {
                        logger.info("Validating login token...");
                        br.getPage(API_BASE + "/users/me");
                        try {
                            checkErrors(account, null);
                            logger.info("Token login successful");
                            return;
                        } catch (final PluginException e) {
                            logger.exception("Token login failed", e);
                        }
                    }
                }
                logger.info("Performing full login");
                final Map<String, Object> postdata = new HashMap<String, Object>();
                postdata.put("email", account.getUser());
                postdata.put("password", account.getPass());
                postdata.put("googleauth", null);
                postdata.put("never_expires", true);
                br.postPageRaw(API_BASE + "/users/login", JSonStorage.serializeToJson(postdata));
                final Map<String, Object> entries = JSonStorage.restoreFromString(br.toString(), TypeRef.HASHMAP);
                final String token = (String) entries.get("access_token");
                if (StringUtils.isEmpty(token)) {
                    throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_DISABLE);
                }
                br.getHeaders().put("Auth", token);
                account.setProperty(PROPERTY_ACCOUNT_TOKEN, token);
            } catch (PluginException e) {
                if (e.getLinkStatus() == LinkStatus.ERROR_PREMIUM) {
                    account.removeProperty(PROPERTY_ACCOUNT_TOKEN);
                }
                throw e;
            }
        }
    }

    private void checkErrors(final Account account, final DownloadLink link) throws PluginException, InterruptedException {
        try {
            final Object jsonO = JSonStorage.restoreFromString(br.toString(), TypeRef.OBJECT);
            if (jsonO == null || !(jsonO instanceof Map)) {
                return;
            }
            handleErrorMap(account, link, (Map<String, Object>) jsonO);
        } catch (final JSonMapperException jme) {
            if (this.getDownloadLink() != null) {
                mhm.handleErrorGeneric(account, this.getDownloadLink(), "Bad API answer", 50, 5 * 60 * 1000l);
            } else {
                throw Exceptions.addSuppressed(new AccountUnavailableException("Bad API answer", 1 * 60 * 1000l), jme);
            }
        }
    }

    private void handleErrorMap(final Account account, final DownloadLink link, final Map<String, Object> entries) throws PluginException, InterruptedException {
        final Object errorO = entries.get("error");
        if (errorO == null) {
            /* No error */
            return;
        }
        final int errorcode = ((Number) errorO).intValue();
        if (errorcode == 0) {
            /* No error */
            return;
        }
        if (link != null) {
            mhm.handleErrorGeneric(account, this.getDownloadLink(), "Error " + errorcode, 50, 1 * 60 * 1000l);
        } else {
            throw new AccountInvalidException("Error " + errorcode);
        }
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetDownloadlink(final DownloadLink link) {
    }
}