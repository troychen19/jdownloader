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
import java.util.Collections;
import java.util.List;

import org.jdownloader.plugins.components.antiDDoSForDecrypt;

import jd.PluginWrapper;
import jd.controlling.ProgressController;
import jd.nutils.encoding.Encoding;
import jd.parser.html.HTMLParser;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.FilePackage;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;

@DecrypterPlugin(revision = "$Revision$", interfaceVersion = 2, names = {}, urls = {})
public class AnimeFrenzy extends antiDDoSForDecrypt {
    public AnimeFrenzy(PluginWrapper wrapper) {
        super(wrapper);
    }

    public static List<String[]> getPluginDomains() {
        final List<String[]> ret = new ArrayList<String[]>();
        // each entry in List<String[]> will result in one PluginForDecrypt, Plugin.getHost() will return String[0]->main domain
        ret.add(new String[] { "animefrenzy.vip", "animefrenzy.net", "animefrenzy.org" });
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
        return buildAnnotationUrls(getPluginDomains());
    }

    public static String[] buildAnnotationUrls(final List<String[]> pluginDomains) {
        final List<String> ret = new ArrayList<String>();
        for (final String[] domains : pluginDomains) {
            ret.add("https?://(?:www\\.)?" + buildHostsPatternPart(domains) + "/((?:anime|cartoon|watch|stream)/[^/]+|(?!player)[\\w\\-]+)");
        }
        return ret.toArray(new String[0]);
    }

    public ArrayList<DownloadLink> decryptIt(final CryptedLink param, ProgressController progress) throws Exception {
        ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
        br.setFollowRedirects(true);
        getPage(param.getCryptedUrl());
        if (br.getHttpConnection().getResponseCode() == 404) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        String fpName = br.getRegex("<title>(?:Watch\\s+)?([^<]+)\\s+(?:- Watch Anime Online|English\\s+[SD]ub\\s+)").getMatch(0);
        final ArrayList<String> links = new ArrayList<String>();
        Collections.addAll(links, br.getRegex("<li[^>]*>\\s*<a[^>]+href\\s*=\\s*[\"']([^\"']+/watch/[^\"']+)[\"']").getColumn(0));
        Collections.addAll(links, br.getRegex("<a[^>]+class\\s*=\\s*[\"']noepia[\"'][^>]+href\\s*=\\s*[\"']([^\"']+)[\"']").getColumn(0));
        String[][] hostLinks = br.getRegex("\\\"(?:host|id)\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"[^\\}]+\\\"(?:host|id)\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"[^\\}]+\\\"type\\\"\\s*:\\s*\\\"(?:subbed|cartoon)\\\"").getMatches();
        for (String[] hostLink : hostLinks) {
            links.add(buildEmbedURL(hostLink[0], hostLink[1]));
            links.add(buildEmbedURL(hostLink[1], hostLink[0]));
        }
        for (String link : links) {
            if (link != null) {
                link = br.getURL(link).toString();
                ret.add(createDownloadlink(link));
            }
        }
        /* 2022-03-24 */
        final String[] urls = HTMLParser.getHttpLinks(br.getRequest().getHtmlCode(), br.getURL());
        for (final String url : urls) {
            if (Gogoplay4Com.looksLikeSupportedPattern(url)) {
                ret.add(this.createDownloadlink(url));
            }
        }
        /* 2022-10-13: Look for selfhosted content */
        final String embedURL = br.getRegex("(/player/v\\d+[^\"']+)").getMatch(0);
        if (embedURL != null) {
            this.getPage(embedURL);
            final String hlsmaster = br.getRegex("file\\s*:\\s*\"(https?://[^\"]+\\.m3u8)\"").getMatch(0);
            if (hlsmaster != null) {
                ret.add(this.createDownloadlink(hlsmaster));
            }
        }
        if (fpName != null) {
            final FilePackage fp = FilePackage.getInstance();
            fp.setName(Encoding.htmlDecode(fpName).trim());
            fp.addLinks(ret);
        }
        return ret;
    }

    private String buildEmbedURL(String host, String id) {
        String result = null;
        if (host.equals("trollvid")) {
            result = "https//trollvid.net/embed/" + id;
        } else if (host.equals("mp4.sh")) {
            result = "https://trollvid.net/embedc/" + id;
        } else if (host.equals("mp4upload")) {
            result = "//www.mp4upload.com/embed-" + id + ".html";
        } else if (host.equals("xstreamcdn")) {
            result = "https://www.xstreamcdn.com/v/" + id;
        } else if (host.equals("vidstreaming")) {
            result = "https://vidstreaming.io/streaming.php?id=" + id;
        } else if (host.equalsIgnoreCase("vidstream") || host.equalsIgnoreCase("vidstream")) {
            result = "https://vidstreaming.io/download?id=" + id;
        } else if (host.equals("yare.wtf")) {
            result = "https://yare.wtf/vidstreaming/download/" + id;
        } else if (host.equals("facebook")) {
            result = "https://www.facebook.com/plugins/video.php?href=https%3A%2F%2Fwww.facebook.com%2Flayfon.alseif.16%2Fvideos%2F" + id + "%2F";
        } else if (host.equals("upload2")) {
            result = "https//upload2.com/embed/" + id;
        }
        return result;
    }
}