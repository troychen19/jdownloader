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
import java.util.LinkedHashMap;

import jd.PluginWrapper;
import jd.controlling.ProgressController;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.FilePackage;
import jd.plugins.PluginForDecrypt;
import jd.plugins.hoster.DummyScriptEnginePlugin;

@DecrypterPlugin(revision = "$Revision$", interfaceVersion = 3, names = { "artstation.com" }, urls = { "https?://(?:www\\.)?artstation\\.com/(?:artist|artwork)/[^/]+" }, flags = { 0 })
public class ArtstationCom extends PluginForDecrypt {

    public ArtstationCom(PluginWrapper wrapper) {
        super(wrapper);
    }

    private static final String TYPE_ARTIST = "https?://(?:www\\.)?artstation\\.com/artist/[^/]+";
    private static final String TYPE_ALBUM  = "https?://(?:www\\.)?artstation\\.com/artwork/[A-Z0-9]+";

    @SuppressWarnings({ "unchecked", "rawtypes" })
    public ArrayList<DownloadLink> decryptIt(CryptedLink param, ProgressController progress) throws Exception {
        ArrayList<DownloadLink> decryptedLinks = new ArrayList<DownloadLink>();
        final String parameter = param.toString();
        br.getPage(parameter);
        if (br.getHttpConnection().getResponseCode() == 404) {
            decryptedLinks.add(this.createOfflinelink(parameter));
            return decryptedLinks;
        }
        final FilePackage fp = FilePackage.getInstance();
        LinkedHashMap<String, Object> json;
        String full_name;
        if (parameter.matches(TYPE_ARTIST)) {
            final String username = parameter.substring(parameter.lastIndexOf("/"));
            jd.plugins.hoster.ArtstationCom.setHeaders(this.br);
            this.br.getPage("https://www.artstation.com/users/" + username + ".json");
            json = (LinkedHashMap<String, Object>) jd.plugins.hoster.DummyScriptEnginePlugin.jsonToJavaObject(br.toString());
            full_name = (String) json.get("full_name");
            final short entries_per_page = 50;
            int entries_total = (int) DummyScriptEnginePlugin.toLong(json.get("projects_count"), 0);
            int offset = 0;
            int page = 1;

            fp.setName(full_name);

            do {
                this.br.getPage("/users/" + username + "/projects.json?randomize=false&page=" + page);
                json = (LinkedHashMap<String, Object>) jd.plugins.hoster.DummyScriptEnginePlugin.jsonToJavaObject(br.toString());
                final ArrayList<Object> ressourcelist = (ArrayList) json.get("data");
                for (final Object resource : ressourcelist) {
                    json = (LinkedHashMap<String, Object>) resource;
                    final String title = (String) json.get("title");
                    final String id = (String) json.get("hash_id");
                    final String description = (String) json.get("description");
                    if (inValidate(id)) {
                        return null;
                    }
                    final String url_content = "https://artstation.com/artwork/" + id;
                    final DownloadLink dl = createDownloadlink(url_content);
                    String filename;
                    if (!inValidate(title)) {
                        filename = full_name + "_" + id + "_" + title + ".jpg";
                    } else {
                        filename = full_name + "_" + id + ".jpg";
                    }
                    filename = encodeUnicode(filename);
                    dl.setContentUrl(url_content);
                    if (description != null) {
                        dl.setComment(description);
                    }
                    dl._setFilePackage(fp);
                    dl.setLinkID(id);
                    dl.setName(filename);
                    dl.setProperty("full_name", full_name);
                    dl.setAvailable(true);
                    decryptedLinks.add(dl);
                    distribute(dl);
                    offset++;
                }

                if (ressourcelist.size() < entries_per_page) {
                    /* Fail safe */
                    break;
                }

                page++;

            } while (decryptedLinks.size() < entries_total);
        } else {
            final String project_id = new Regex(parameter, "([A-Za-z0-9]+)$").getMatch(0);
            if (inValidate(project_id)) {
                return decryptedLinks;
            }
            jd.plugins.hoster.ArtstationCom.setHeaders(this.br);
            this.br.getPage("https://www.artstation.com/projects/" + project_id + ".json");
            json = (LinkedHashMap<String, Object>) jd.plugins.hoster.DummyScriptEnginePlugin.jsonToJavaObject(br.toString());
            final ArrayList<Object> resource_data_list = (ArrayList<Object>) json.get("assets");
            full_name = (String) DummyScriptEnginePlugin.walkJson(json, "user/full_name");
            fp.setName(project_id);

            for (final Object jsono : resource_data_list) {
                json = (LinkedHashMap<String, Object>) jsono;
                final String url = (String) json.get("image_url");
                final String fid = Long.toString(DummyScriptEnginePlugin.toLong(json.get("id"), -1));
                final String title = (String) json.get("title");
                if (fid.equals("-1") || url == null) {
                    continue;
                }
                String filename = "";
                if (!inValidate(title)) {
                    filename += fid + "_" + title;
                } else {
                    filename += fid;
                }
                if (!inValidate(full_name)) {
                    filename = full_name + "_" + filename;
                }
                filename = Encoding.htmlDecode(filename);
                filename = filename.trim();
                filename = encodeUnicode(filename);
                String ext = url.substring(url.lastIndexOf("."));
                /* Make sure that we get a correct extension */
                if (ext == null || !ext.matches("\\.[A-Za-z0-9]{3,5}")) {
                    ext = jd.plugins.hoster.ArtstationCom.default_Extension;
                }
                if (!filename.endsWith(ext)) {
                    filename += ext;
                }

                final DownloadLink dl = this.createDownloadlink(url);
                dl._setFilePackage(fp);
                dl.setAvailable(true);
                dl.setFinalFileName(filename);
                dl.setProperty("decrypterfilename", filename);
                decryptedLinks.add(dl);

            }
        }

        return decryptedLinks;
    }

    /**
     * Validates string to series of conditions, null, whitespace, or "". This saves effort factor within if/for/while statements
     *
     * @param s
     *            Imported String to match against.
     * @return <b>true</b> on valid rule match. <b>false</b> on invalid rule match.
     * @author raztoki
     */
    public static boolean inValidate(final String s) {
        if (s == null || s.matches("\\s+") || s.equals("")) {
            return true;
        } else {
            return false;
        }
    }

    /** Avoid chars which are not allowed in filenames under certain OS' */
    private static String encodeUnicode(final String input) {
        String output = input;
        output = output.replace(":", ";");
        output = output.replace("|", "¦");
        output = output.replace("<", "[");
        output = output.replace(">", "]");
        output = output.replace("/", "⁄");
        output = output.replace("\\", "∖");
        output = output.replace("*", "#");
        output = output.replace("?", "¿");
        output = output.replace("!", "¡");
        output = output.replace("\"", "'");
        return output;
    }
}
