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

import org.appwork.utils.formatter.SizeFormatter;

import jd.PluginWrapper;
import jd.controlling.ProgressController;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.FilePackage;
import jd.plugins.PluginForDecrypt;

@DecrypterPlugin(revision = "$Revision$", interfaceVersion = 3, names = { "commons.wikimedia.org" }, urls = { "https?://commons\\.wikimedia\\.org/wiki/Category:Pictures_by_User:[A-Za-z0-9\\-_]+" })
public class CommonsWikimediaOrg extends PluginForDecrypt {
    public CommonsWikimediaOrg(PluginWrapper wrapper) {
        super(wrapper);
    }

    public ArrayList<DownloadLink> decryptIt(CryptedLink param, ProgressController progress) throws Exception {
        final ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
        final String parameter = param.getCryptedUrl();
        final String username = new Regex(parameter, "Category:Pictures_by_User:(.+)").getMatch(0);
        br.getPage(parameter);
        if (br.getHttpConnection().getResponseCode() == 404) {
            ret.add(this.createOfflinelink(parameter));
            return ret;
        }
        final FilePackage fp = FilePackage.getInstance();
        fp.setName(username);
        final String total_number_of_items_str = this.br.getRegex("out of ([0-9,]+) total").getMatch(0);
        if (total_number_of_items_str == null) {
            return null;
        }
        final int total_number_of_items = Integer.parseInt(total_number_of_items_str.replace(",", ""));
        short max_items_per_page = 200;
        String filename_of_last_object_on_recent_page = "";
        int page = 1;
        do {
            this.br.getPage("https://commons.wikimedia.org/w/index.php?title=Category:Pictures_by_User:" + username + "&filefrom=" + filename_of_last_object_on_recent_page);
            final String[] htmls = this.br.getRegex("(<li class=\"gallerybox\".*?</div></li>)").getColumn(0);
            if (htmls == null || htmls.length == 0) {
                break;
            }
            final int items_found = htmls.length;
            for (final String html : htmls) {
                filename_of_last_object_on_recent_page = new Regex(html, "title=\"File:([^<>\"]*?)\"").getMatch(0);
                final String filesize = new Regex(html, "(\\d+(?:\\.\\d{1,2})? (?:KB|MB))").getMatch(0);
                final String url = new Regex(html, "\"(/wiki/File:[^<>\"]*?)\"").getMatch(0);
                if (filename_of_last_object_on_recent_page == null || filesize == null || url == null) {
                    return null;
                }
                final DownloadLink dl = createDownloadlink("https://commons.wikimedia.org" + url);
                dl.setName(Encoding.htmlDecode(filename_of_last_object_on_recent_page).trim());
                dl.setDownloadSize(SizeFormatter.getSize(filesize));
                dl.setAvailable(true);
                dl._setFilePackage(fp);
                ret.add(dl);
                distribute(dl);
            }
            logger.info("Crawled page " + page + " | Found items so far: " + ret.size() + "/" + total_number_of_items);
            if (this.isAbort()) {
                logger.info("Stopping because: Crawler aborted by user");
                break;
            } else if (ret.size() >= total_number_of_items) {
                logger.info("Stopping because: Found all items");
                break;
            } else if (items_found < max_items_per_page) {
                logger.info("Stopping because: Current page contains less items than: " + max_items_per_page);
                break;
            }
        } while (true);
        if (ret.size() == 0) {
            logger.warning("Decrypter broken for link: " + parameter);
            return null;
        }
        return ret;
    }
}
