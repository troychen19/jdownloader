//    jDownloader - Downloadmanager
//    Copyright (C) 2013  JD-Team support@jdownloader.org
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
package jd.plugins.hoster;

import java.io.IOException;

import jd.PluginWrapper;
import jd.http.Browser;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;
import jd.plugins.components.PluginJSonUtils;

import org.appwork.utils.StringUtils;

/**
 * indexes!
 *
 * @author raztoki
 */
@HostPlugin(revision = "$Revision$", interfaceVersion = 2, names = { "cloudup.com" }, urls = { "https://(?:www\\.)?cloudup\\.com/(i[a-zA-Z0-9_\\-]{10})" })
public class CloudUpCom extends PluginForHost {
    private String  csrfToken      = null;
    private String  mydb_socket_id = null;
    private Browser ajax           = null;

    private void ajaxGetPage(final String page) throws IOException {
        ajax = br.cloneBrowser();
        ajax.getHeaders().put("Accept", "*/*");
        if (mydb_socket_id != null) {
            ajax.getHeaders().put("X-MyDB-SocketId", mydb_socket_id);
        }
        if (csrfToken != null) {
            ajax.getHeaders().put("X-CSRF-Token", csrfToken);
        }
        ajax.getPage(page);
    }

    public CloudUpCom(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public String getAGBLink() {
        return "https://cloudup.com/terms";
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return -1;
    }

    @Override
    public String getLinkID(final DownloadLink link) {
        final String linkid = getFID(link);
        if (linkid != null) {
            return this.getHost() + "://" + linkid;
        } else {
            return super.getLinkID(link);
        }
    }

    private String getFID(final DownloadLink link) {
        return new Regex(link.getPluginPatternMatcher(), this.getSupportedLinks()).getMatch(0);
    }

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws Exception {
        this.setBrowserExclusive();
        br.getPage(link.getPluginPatternMatcher());
        // offline check
        if (br.getHttpConnection().getResponseCode() == 404) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        // get the json url coded info
        final String preloader = br.getRegex("JSON\\.parse\\(decodeURIComponent\\('(.*?)'\\)\\)").getMatch(0);
        if (preloader == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        // we need some session info here, these are not actually verified....
        mydb_socket_id = br.getRegex("mydb_socket_id\\s*=\\s*('|\")(.*?)\\1").getMatch(1);
        csrfToken = br.getRegex("csrfToken\\s*=\\s*('|\")(.*?)\\1").getMatch(1);
        ajaxGetPage("/files/" + new Regex(link.getDownloadURL(), "/([^/]+)$").getMatch(0) + "?mydb=1");
        // is file
        final String file = PluginJSonUtils.getJsonValue(ajax, "type");
        if (!"file".equals(file)) {
            // we don't support non file entries
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        // filename
        final String filename = PluginJSonUtils.getJsonValue(ajax, "filename");
        // filesize
        final String filesize = PluginJSonUtils.getJsonValue(ajax, "size");
        // error handling
        if (StringUtils.isEmpty(filename)) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        link.setName(filename);
        if (filesize != null) {
            link.setDownloadSize(Long.parseLong(filesize));
        }
        // removed?
        final String removed = PluginJSonUtils.getJsonValue(ajax, "removed");
        if (PluginJSonUtils.parseBoolean(removed)) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        } else {
            return AvailableStatus.TRUE;
        }
    }

    @Override
    public void handleFree(final DownloadLink link) throws Exception {
        requestFileInformation(link);
        final String dllink;
        final boolean useNewWay = true;
        if (useNewWay) {
            /* 2021-10-06 */
            dllink = "https://" + this.getHost() + "/files/" + this.getFID(link) + "/download";
        } else {
            final String remote = PluginJSonUtils.getJsonValue(ajax, "remote");
            final String filename = PluginJSonUtils.getJsonValue(ajax, "filename");
            if (remote == null || filename == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            dllink = "https://cldup.com/" + remote + "?download=" + Encoding.urlEncode(filename);
        }
        dl = jd.plugins.BrowserAdapter.openDownload(br, link, dllink, true, 0);
        if (!this.looksLikeDownloadableContent(dl.getConnection())) {
            br.followConnection(true);
            if (br.containsHTML(">The download of this file has been disabled")) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            } else {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
        }
        dl.startDownload();
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
    }

    @Override
    public void resetPluginGlobals() {
    }
}