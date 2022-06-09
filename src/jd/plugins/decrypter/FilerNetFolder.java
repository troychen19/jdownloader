//    jDownloader - Downloadmanager
//    Copyright (C) 2008  JD-Team support@jdownloader.org
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
package jd.plugins.decrypter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.appwork.storage.JSonStorage;
import org.appwork.storage.TypeRef;

import jd.PluginWrapper;
import jd.controlling.ProgressController;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterException;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.FilePackage;
import jd.plugins.LinkStatus;
import jd.plugins.PluginDependencies;
import jd.plugins.PluginException;
import jd.plugins.PluginForDecrypt;
import jd.plugins.hoster.FilerNet;

@DecrypterPlugin(revision = "$Revision$", interfaceVersion = 2, names = {}, urls = {})
@PluginDependencies(dependencies = { FilerNet.class })
public class FilerNetFolder extends PluginForDecrypt {
    public FilerNetFolder(PluginWrapper wrapper) {
        super(wrapper);
    }

    public static List<String[]> getPluginDomains() {
        return FilerNet.getPluginDomains();
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
            ret.add("https?://(?:www\\.)?" + buildHostsPatternPart(domains) + "/folder/([a-z0-9]+)");
        }
        return ret.toArray(new String[0]);
    }

    public ArrayList<DownloadLink> decryptIt(final CryptedLink param, ProgressController progress) throws Exception {
        final ArrayList<DownloadLink> decryptedLinks = new ArrayList<DownloadLink>();
        br.setFollowRedirects(false);
        br.getHeaders().put("User-Agent", "JDownloader");
        final String folderID = new Regex(param.getCryptedUrl(), this.getSupportedLinks()).getMatch(0);
        br.getPage(FilerNet.API_BASE + "/folder/" + folderID + ".json");
        Map<String, Object> entries = JSonStorage.restoreFromString(br.toString(), TypeRef.HASHMAP);
        int code = ((Number) entries.get("code")).intValue();
        if (code == 506) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        if (code == 201) {
            /* Password protected folder */
            for (int i = 1; i <= 3; i++) {
                final String passCode = getUserInput("Password?", param);
                br.getPage("/api/folder/" + folderID + ".json?password=" + Encoding.urlEncode(passCode));
                entries = JSonStorage.restoreFromString(br.toString(), TypeRef.HASHMAP);
                code = ((Number) entries.get("code")).intValue();
                if (code == 201) {
                    logger.info("Wrong password: " + passCode);
                    continue;
                }
                break;
            }
            if (code == 201) {
                throw new DecrypterException(DecrypterException.PASSWORD);
            }
        }
        final Map<String, Object> data = (Map<String, Object>) entries.get("data");
        final int count = ((Number) data.get("count")).intValue();
        if (count == 0) {
            logger.info("Empty folder");
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        String fpName = (String) data.get("name");
        if (fpName == null) {
            /* Fallback */
            fpName = "filer.net folder: " + folderID;
        }
        final List<Map<String, Object>> files = (List<Map<String, Object>>) data.get("files");
        for (final Map<String, Object> file : files) {
            final DownloadLink link = createDownloadlink(file.get("link").toString());
            link.setFinalFileName(file.get("name").toString());
            link.setVerifiedFileSize(((Number) file.get("size")).longValue());
            link.setAvailable(true);
            decryptedLinks.add(link);
        }
        final FilePackage fp = FilePackage.getInstance();
        fp.setName(fpName);
        fp.addLinks(decryptedLinks);
        return decryptedLinks;
    }

    private String getJson(final String parameter, final String source) {
        String result = new Regex(source, "\"" + parameter + "\":(\\d+)").getMatch(0);
        if (result == null) {
            result = new Regex(source, "\"" + parameter + "\":\"([^<>\"]*?)\"").getMatch(0);
        }
        return result;
    }

    /* NO OVERRIDE!! */
    public boolean hasCaptcha(CryptedLink link, jd.plugins.Account acc) {
        return false;
    }
}