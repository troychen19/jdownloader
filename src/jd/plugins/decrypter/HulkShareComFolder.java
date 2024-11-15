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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.regex.Pattern;

import jd.PluginWrapper;
import jd.controlling.ProgressController;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.FilePackage;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForDecrypt;

@DecrypterPlugin(revision = "$Revision$", interfaceVersion = 2, names = { "hulkshare.com" }, urls = { "https?://(?:www\\.)?(?:hulkshare\\.com|hu\\.lk)/[A-Za-z0-9_\\-]+(?:/[^<>\"/]+)?" })
public class HulkShareComFolder extends PluginForDecrypt {
    public HulkShareComFolder(PluginWrapper wrapper) {
        super(wrapper);
    }

    private static final String HULKSHAREDOWNLOADLINK = "^https?://[^/]+/([a-z0-9]{12})$";
    private static final String TYPE_SECONDSINGLELINK = "https?://[^/]+/[a-z0-9\\-_]+/[^<>\"/]+";
    private static final String TYPE_PLAYLIST         = "https?://[^/]+/playlist/\\d+(.+)?";

    public ArrayList<DownloadLink> decryptIt(final CryptedLink param, ProgressController progress) throws Exception {
        ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
        String parameter = param.getCryptedUrl().replaceFirst("hu\\.lk/", "hulkshare\\.com/");
        final String fid = new Regex(parameter, "(?i)hulkshare\\.com/dl/([a-z0-9]{12})").getMatch(0);
        if (fid != null) {
            parameter = "https://www." + this.getHost() + "/" + fid;
        } else if (!parameter.matches(HULKSHAREDOWNLOADLINK) && !parameter.matches(TYPE_PLAYLIST)) {
        }
        if (parameter.matches("https?://(?:www\\.)?(hulkshare\\.com|hu\\/lk)/(static|browse|images|terms|contact|audible|search|people|upload|featured|mobile|group|explore|sitemaps).*?")) {
            logger.info("Invalid link: " + parameter);
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        } else if (new Regex(parameter, HULKSHAREDOWNLOADLINK).matches()) {
            ret.add(createDownloadlink(parameter.replace("hulkshare.com/", "hulksharedecrypted.com/")));
            return ret;
        }
        br.setFollowRedirects(true);
        br.setCookie(this.getHost(), "lang", "english");
        // They can have huge pages, allow double of the normal load limit
        br.setLoadLimit(br.getLoadLimit() * 2);
        br.getPage(parameter);
        final String longLink = br.getRegex("longLink = \\'(https?://(?:www\\.)?hulkshare\\.com/[a-z0-9]{12})\\'").getMatch(0);
        if ((new Regex(parameter, Pattern.compile(TYPE_SECONDSINGLELINK, Pattern.CASE_INSENSITIVE)).matches()) && longLink != null) {
            /* We have a single mp3 link */
            ret.add(createDownloadlink(longLink.replace("hulkshare.com/", "hulksharedecrypted.com/")));
            return ret;
        } else if (!(new Regex(parameter, Pattern.compile(TYPE_PLAYLIST, Pattern.CASE_INSENSITIVE)).matches()) && (new Regex(parameter, Pattern.compile(TYPE_SECONDSINGLELINK, Pattern.CASE_INSENSITIVE)).matches()) && longLink == null) {
            /*
             * Either an offline singleLink or a 'wrong' user-link e.g. http://www.hulkshare.com/any_user_name/followers --> Try to correct
             * that - assume we have a user-link - if not it does not matter - it'll simply be offline then
             */
            final String username = new Regex(parameter, "http://[^/]+/([^/]+)").getMatch(0);
            parameter = "http://www." + this.getHost() + "/" + username;
            this.br.getPage(parameter);
        }
        if (br.getHttpConnection().getContentType().equals("text/javascript") || br.getHttpConnection().getContentType().equals("text/css") || this.br.getURL().contains("/404.php")) {
            logger.info("Invalid link: " + parameter);
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        if (br.containsHTML("class=\"bigDownloadBtn") || br.containsHTML(">\\s*The owner of this file doesn\\'t allow downloading")) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        } else if (br.containsHTML("You have reached the download\\-limit")) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        } else if (this.br.getHttpConnection().getResponseCode() == 404 || br.containsHTML(">Page not found") || br.containsHTML(">This file has been subject to a DMCA notice") || br.containsHTML("<h2>Error</h2>") || br.containsHTML(">We\\'re sorry but this page is not accessible") || br.containsHTML(">Error<")) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        // Mainpage
        if (br.containsHTML("<title>Online Music, Free Internet Radio, Discover Artists \\- Hulkshare")) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        } else if (br.containsHTML("class=\"nhsUploadLink signupPopLink\">Sign up for Hulkshare<")) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        } else if (new Regex(parameter, Pattern.compile(TYPE_PLAYLIST, Pattern.CASE_INSENSITIVE)).matches()) {
            String fpName = br.getRegex("property=\"og:title\" content=\"([^<>\"]*?)\"").getMatch(0);
            if (fpName == null) {
                fpName = "hulkshare.com playlist - " + new Regex(parameter, "(\\d+)$").getMatch(0);
            }
            final String pllist = br.getRegex("class=\"newPlayer\" id=\"hsPlayer[A-Za-z0-9\\-_]+\" pid=\"\\d+\" rel=\"([^<>\"]*?)\"").getMatch(0);
            if (pllist == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            final String[] playlistids = pllist.split(",");
            for (final String pllistid : playlistids) {
                ret.add(createDownloadlink("http://hulksharedecrypted.com/playlistsong/" + pllistid));
            }
            final FilePackage fp = FilePackage.getInstance();
            fp.setName(Encoding.htmlDecode(fpName.trim()));
            fp.addLinks(ret);
            return ret;
        }
        /* Maybe album? */
        final String uid = br.getRegex("\\?uid=(\\d+)").getMatch(0);
        if (uid == null) {
            if (longLink != null && longLink.matches(HULKSHAREDOWNLOADLINK)) {
                /* We have a single mp3 link */
                ret.add(createDownloadlink(longLink.replace("hulkshare.com/", "hulksharedecrypted.com/")));
                return ret;
            } else {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
        }
        final int count_tracks = Integer.parseInt(br.getRegex(">All Music <i>(\\d+)</i>").getMatch(0));
        if (count_tracks == 0) {
            final DownloadLink dl = createDownloadlink("directhttp://" + parameter);
            dl.setProperty("offline", true);
            dl.setAvailable(false);
            ret.add(dl);
            return ret;
        }
        final int entries_per_page = 25;
        final BigDecimal bd = new BigDecimal((double) count_tracks / entries_per_page);
        final int max_loads = bd.setScale(0, BigDecimal.ROUND_UP).intValue();
        final FilePackage fp = FilePackage.getInstance();
        fp.setName(new Regex(parameter, "hulkshare\\.com/(.+)").getMatch(0));
        br.getHeaders().put("X-Requested-With", "XMLHttpRequest");
        for (int i = 1; i <= max_loads; i++) {
            logger.info("Decrypting page " + i + " of " + max_loads);
            String linktext;
            if (i == 1) {
                linktext = br.getRegex("<ul class=\"nhsBrowseItems\">(.*?</li>)[\t\n\r ]+</ul>").getMatch(0);
            } else {
                br.postPage("https://www." + this.getHost() + "/userPublic.php", "ajax_pagination=1&uid=" + uid + "&page=" + i + "&fav=0&isvid=0&fld_id=0&per_page=" + entries_per_page + "&up=0&is_following=1&type=music&last_create_from_previous_list=");
                br.getRequest().setHtmlCode(br.toString().replace("\\", ""));
                linktext = br.getRegex("\"html\":\"(.+)\\}$").getMatch(0);
            }
            if (linktext == null) {
                // do not return null; as this could be a false positive - probably we simply decrypted everything possible
                break;
            }
            final String[] linkinfo = linktext.split("class=\"nhsBrowseBlock\"");
            if (linkinfo == null || linkinfo.length == 0) {
                // do not return null; as this could be a false positive - probably we simply decrypted everything possible
                break;
            }
            int added_links_count = 0;
            for (String slinkinfo : linkinfo) {
                final String fcode = new Regex(slinkinfo, "id=\"filecode\\-([a-z0-9]{12})\"").getMatch(0);
                if (fcode != null) {
                    if (fcode.equals(fid)) {
                        continue;
                    }
                    final Regex more_info = new Regex(slinkinfo, "class=\"nhsTrackTitle nhsClear\" href=\"(http://[^<>\"]*?)\">([^<>\"]*?)</a>");
                    final String trackname = more_info.getMatch(1);
                    final DownloadLink fina = createDownloadlink("http://hulksharedecrypted.com/" + fcode);
                    if (trackname != null) {
                        fina.setName(Encoding.htmlDecode(trackname.trim()) + ".mp3");
                        fina.setAvailable(true);
                    }
                    fina._setFilePackage(fp);
                    fina.setLinkID("hulksharecom_" + fcode);
                    distribute(fina);
                    ret.add(fina);
                    added_links_count++;
                }
            }
            if (added_links_count != entries_per_page) {
                logger.info("We got less links than entries_per_page --> Probably we're done - stopping");
                break;
            } else if (this.isAbort()) {
                logger.info("Decryption aborted for link: " + parameter);
                return ret;
            }
        }
        if (ret.size() == 0) {
            logger.warning("Possible Plugin Defect, please confirm in your browser. If there are files present within '" + parameter + "' please report this issue to JDownloader Development Team!");
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        fp.addLinks(ret);
        return ret;
    }

    /* NO OVERRIDE!! */
    public boolean hasCaptcha(CryptedLink link, jd.plugins.Account acc) {
        return false;
    }
}