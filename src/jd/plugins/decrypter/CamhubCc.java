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

import org.jdownloader.plugins.controller.LazyPlugin;

import jd.PluginWrapper;
import jd.controlling.ProgressController;
import jd.parser.Regex;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;

@DecrypterPlugin(revision = "$Revision$", interfaceVersion = 2, names = { "camhub.cc" }, urls = { "https?://(?:www\\.)?camhub\\.cc/videos/\\d+/([a-z0-9\\-]+)/" })
public class CamhubCc extends PornEmbedParser {
    public CamhubCc(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public LazyPlugin.FEATURE[] getFeatures() {
        return new LazyPlugin.FEATURE[] { LazyPlugin.FEATURE.XXX };
    }
    /* DEV NOTES */
    /* Porn_plugin */
    /* Tags: camhub.world (sister-site - their "selfhosted content" is hosted there) */

    public ArrayList<DownloadLink> decryptIt(CryptedLink param, ProgressController progress) throws Exception {
        ArrayList<DownloadLink> decryptedLinks = new ArrayList<DownloadLink>();
        br.setFollowRedirects(true);
        final String parameter = param.toString();
        br.getPage(parameter);
        if (br.getHttpConnection().getResponseCode() == 404) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        /* Get filename from URL */
        String filename = new Regex(parameter, this.getSupportedLinks()).getMatch(0).replace("-", " ");
        filename = filename.trim();
        /* Remove eventually existing hash from filename */
        final String removeMe = new Regex(filename, "( ?[a-f0-9]{16})$").getMatch(0);
        if (removeMe != null) {
            filename = filename.replace(removeMe, "");
        }
        final String selfEmbeddedURL = br.getRegex("(https?://(?:www\\.)?camhub\\.world/embed/\\d+)").getMatch(0);
        if (selfEmbeddedURL != null) {
            /* Selfhosted but embedded on sister-site. */
            final DownloadLink link = this.createDownloadlink(selfEmbeddedURL);
            link.setFinalFileName(filename + ".mp4");
            decryptedLinks.add(link);
        } else {
            /* Selfhosted without embed */
            final DownloadLink link = this.createDownloadlink(param.getCryptedUrl());
            link.setName(filename + ".mp4");
            decryptedLinks.add(link);
        }
        // decryptedLinks.addAll(findEmbedUrls(filename));
        return decryptedLinks;
    }
}