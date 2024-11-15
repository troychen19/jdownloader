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

import java.util.ArrayList;
import java.util.List;

import org.jdownloader.captcha.v2.challenge.hcaptcha.CaptchaHelperCrawlerPluginHCaptcha;

import jd.PluginWrapper;
import jd.controlling.ProgressController;
import jd.http.Browser;
import jd.nutils.encoding.Encoding;
import jd.parser.html.Form;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;

@DecrypterPlugin(revision = "$Revision$", interfaceVersion = 3, names = {}, urls = {})
public class ClicksflyCom extends MightyScriptAdLinkFly {
    public ClicksflyCom(PluginWrapper wrapper) {
        super(wrapper);
    }

    private static List<String[]> getPluginDomains() {
        final List<String[]> ret = new ArrayList<String[]>();
        ret.add(new String[] { "clicksfly.com", "clkfly.pw", "clk.asia", "enit.in" });
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
        final List<String> ret = new ArrayList<String>();
        for (final String[] domains : getPluginDomains()) {
            ret.add("https?://(?:www\\.)?" + buildHostsPatternPart(domains) + "/(entrar/[A-Za-z0-9\\-]+|(?!full)[a-zA-Z0-9]{2,}/?)");
        }
        return ret.toArray(new String[0]);
    }

    @Override
    public ArrayList<DownloadLink> decryptIt(final CryptedLink param, ProgressController progress) throws Exception {
        return super.decryptIt(param, progress);
    }

    @Override
    protected ArrayList<DownloadLink> handlePreCrawlProcess(final CryptedLink param) throws Exception {
        final ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
        final boolean followRedirectsOld = br.isFollowingRedirects();
        br.setFollowRedirects(true);
        br.getPage(this.getContentURL(param));
        Form form = br.getForm(0);
        form.setAction(Encoding.urlDecode(form.getInputField("url").getValue(), true));
        submitForm(form);
        Form aliasForm = getAliasForm(br);
        if (aliasForm != null) {
            /* Ads + captcha */
            logger.info("alias form detected");
            final String hcaptchaResponse = new CaptchaHelperCrawlerPluginHCaptcha(this, br).getToken();
            aliasForm.put("h-captcha-response", Encoding.urlEncode(hcaptchaResponse));
            br.submitForm(aliasForm);
            aliasForm = getAliasForm(br);
            if (aliasForm != null) {
                /* Back to original website -> Wait time -> Final link */
                logger.info("alias form detected again, this time without captcha");
                br.submitForm(aliasForm);
                aliasForm = getAliasForm(br);
                if (aliasForm != null) {
                    logger.info("alias form detected again?! Possible plugin failure!");
                }
            }
        }
        if (br.getURL().matches("(?i)https?://[^/]+/?$")) {
            /* Redirect to mainpage -> Invalid link */
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        br.setFollowRedirects(followRedirectsOld);
        return ret;
        // return super.handlePreCrawlProcess(param);
    }

    private Form getAliasForm(final Browser br) {
        return br.getFormbyKey("alias");
    }

    @Override
    protected String getSpecialReferer() {
        /* Pre-set Referer to skip multiple ad pages e.g. clk.asia -> set referer -> clk.asia */
        /* Possible other fake blog domains: skincarie.com, howifx.com */
        // Last updated: 2023-02-22
        return "https://howifx.com/";
    }
}
