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
import java.util.Map;

import jd.PluginWrapper;
import jd.controlling.ProgressController;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForDecrypt;
import jd.plugins.PluginForHost;
import jd.plugins.download.HashInfo;
import jd.utils.JDUtilities;

import org.appwork.storage.JSonStorage;
import org.appwork.storage.TypeRef;
import org.appwork.utils.StringUtils;

@DecrypterPlugin(revision = "$Revision$", interfaceVersion = 2, names = { "keep2share.cc" }, urls = { "https?://((www|new|spa)\\.)?(keep2share|k2s|k2share|keep2s|keep2)\\.cc/file/(info/)?[a-z0-9]+" })
public class Keep2ShareCcDecrypter extends PluginForDecrypt {
    public Keep2ShareCcDecrypter(PluginWrapper wrapper) {
        super(wrapper);
    }

    public ArrayList<DownloadLink> decryptIt(CryptedLink param, ProgressController progress) throws Exception {
        final ArrayList<DownloadLink> decryptedLinks = new ArrayList<DownloadLink>();
        final PluginForHost plugin = JDUtilities.getNewPluginForHostInstance("keep2share.cc");
        plugin.setBrowser(br);
        plugin.setLogger(getLogger());
        if (plugin == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        br = ((jd.plugins.hoster.Keep2ShareCc) plugin).newWebBrowser(true);
        // set cross browser support
        ((jd.plugins.hoster.Keep2ShareCc) plugin).setBrowser(br);
        final String fuid = ((jd.plugins.hoster.Keep2ShareCc) plugin).getFUID(param.getCryptedUrl());
        ((jd.plugins.hoster.Keep2ShareCc) plugin).postPageRaw(br, "/getfilesinfo", "{\"ids\":[\"" + fuid + "\"]}", null);
        Map<String, Object> response = JSonStorage.restoreFromString(br.toString(), TypeRef.HASHMAP);
        boolean folderHandling = false;
        if ("success".equals(response.get("status"))) {
            final List<Map<String, Object>> files = (List<Map<String, Object>>) response.get("files");
            if (files != null) {
                for (Map<String, Object> file : files) {
                    final Boolean isFolder = (Boolean) file.get("is_folder");
                    final Boolean isAvailable = (Boolean) file.get("is_available");
                    final String id = (String) file.get("id");
                    final String name = (String) file.get("name");
                    final String md5 = (String) file.get("md5");
                    final String access = (String) file.get("access");
                    final String size = file.get("size").toString();
                    if (Boolean.FALSE.equals(isFolder)) {
                        final DownloadLink link = createDownloadlink("https://k2s.cc/file/" + id);
                        if (StringUtils.isNotEmpty(name)) {
                            link.setName(name);
                        }
                        if (StringUtils.isNotEmpty(size)) {
                            link.setVerifiedFileSize(Long.parseLong(size));
                        }
                        link.setHashInfo(HashInfo.parse(md5));
                        link.setAvailable(Boolean.TRUE.equals(isAvailable));
                        link.setProperty("access", access);
                        decryptedLinks.add(link);
                    } else if (StringUtils.equals(id, fuid)) {
                        folderHandling = true;
                        break;
                    }
                }
            }
        }
        if (folderHandling) {
            // ask for own id/credentials
            br.postPage("https://api.k2s.cc/v1/auth/token", "{\"grant_type\":\"client_credentials\",\"client_id\":\"k2s_web_app\",\"client_secret\":\"pjc8pyZv7vhscexepFNzmu4P\"}");
            int offset = 0;
            int itemsCount = 0;
            while (!isAbort()) {
                br.getPage("https://api.k2s.cc/v1/files?limit=20&offset=" + offset + "&sort=-createdAt&folderId=" + fuid + "&withFolders=true");
                response = JSonStorage.restoreFromString(br.toString(), TypeRef.HASHMAP);
                final Number total = (Number) response.get("total");
                final List<Map<String, Object>> items = (List<Map<String, Object>>) response.get("items");
                if (items != null && items.size() > 0) {
                    itemsCount += items.size();
                    for (Map<String, Object> file : items) {
                        final Boolean isFile = "file".equals(file.get("type"));
                        final Boolean isAvailable = !(Boolean) file.get("isDeleted");
                        final String id = (String) file.get("id");
                        final String name = (String) file.get("name");
                        final String access = (String) file.get("accessType");
                        final String size = file.get("size").toString();
                        if (Boolean.TRUE.equals(isFile)) {
                            final DownloadLink link = createDownloadlink("https://k2s.cc/file/" + id);
                            if (StringUtils.isNotEmpty(name)) {
                                link.setName(name);
                            }
                            if (StringUtils.isNotEmpty(size)) {
                                link.setVerifiedFileSize(Long.parseLong(size));
                            }
                            link.setAvailable(Boolean.TRUE.equals(isAvailable));
                            link.setProperty("access", access);
                            decryptedLinks.add(link);
                        }
                    }
                } else {
                    break;
                }
                if (itemsCount >= total.longValue()) {
                    // break;
                }
                offset += 20;
            }
        }
        return decryptedLinks;
    }
}
