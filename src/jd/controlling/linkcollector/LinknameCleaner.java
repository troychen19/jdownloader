package jd.controlling.linkcollector;

import java.util.regex.Pattern;

import jd.controlling.packagecontroller.AbstractNode;

import org.appwork.utils.Regex;
import org.jdownloader.controlling.filter.CompiledFiletypeFilter;
import org.jdownloader.controlling.filter.CompiledFiletypeFilter.ArchiveExtensions;
import org.jdownloader.controlling.filter.CompiledFiletypeFilter.ExtensionsFilterInterface;

public class LinknameCleaner {
    public static final Pattern   pat0     = Pattern.compile("(.*)(\\.|_|-)pa?r?t?\\.?[0-9]+.(rar|rev|exe)($|\\.html?)", Pattern.CASE_INSENSITIVE);
    public static final Pattern   pat1     = Pattern.compile("(.*)(\\.|_|-)part\\.?[0]*[1].(rar|rev|exe)($|\\.html?)", Pattern.CASE_INSENSITIVE);
    public static final Pattern   pat3     = Pattern.compile("(.*)\\.(?:rar|rev)($|\\.html?)", Pattern.CASE_INSENSITIVE);
    public static final Pattern   pat4     = Pattern.compile("(.*)\\.r\\d+($|\\.html?)", Pattern.CASE_INSENSITIVE);
    public static final Pattern   pat5     = Pattern.compile("(.*)(\\.|_|-)\\d+($|\\.html?)", Pattern.CASE_INSENSITIVE);
    public static final Pattern   par2     = Pattern.compile("(.*?)(\\.vol\\d+\\.par2$|\\.vol\\d+(?:\\+|-)\\d+\\.par2$|\\.par2$)", Pattern.CASE_INSENSITIVE);
    public static final Pattern   par      = Pattern.compile("(.*?)(\\.p\\d+$|\\.par$)", Pattern.CASE_INSENSITIVE);
    public static final Pattern[] rarPats  = new Pattern[] { pat0, pat1, pat3, pat4, pat5, par2, par };
    public static final Pattern   pat6     = Pattern.compile("(.*)\\.zip($|\\.html?)", Pattern.CASE_INSENSITIVE);
    public static final Pattern   pat7     = Pattern.compile("(.*)\\.z\\d+($|\\.html?)", Pattern.CASE_INSENSITIVE);
    public static final Pattern   pat8     = Pattern.compile("(?is).*\\.7z\\.[\\d]+($|\\.html?)", Pattern.CASE_INSENSITIVE);
    public static final Pattern   pat9     = Pattern.compile("(.*)\\.a.($|\\.html?)", Pattern.CASE_INSENSITIVE);
    public static final Pattern[] zipPats  = new Pattern[] { pat6, pat7, pat8, pat9 };
    public static final Pattern   pat10    = Pattern.compile("(.*)\\._((_[a-z]{1})|([a-z]{2}))(\\.|$)");
    public static Pattern         pat11    = null;
    public static Pattern[]       ffsjPats = null;
    static {
        try {
            /* this should be done on a better way with next major update */
            pat11 = Pattern.compile("(.+)(" + jd.plugins.hoster.DirectHTTP.ENDINGS + "$)", Pattern.CASE_INSENSITIVE);
            ffsjPats = new Pattern[] { pat10, pat11 };
        } catch (final Throwable e) {
            /* not loaded yet */
        }
    }
    public static final Pattern   pat12    = Pattern.compile("(CD\\d+)", Pattern.CASE_INSENSITIVE);
    public static final Pattern   pat13    = Pattern.compile("(part\\d+)", Pattern.CASE_INSENSITIVE);
    public static final Pattern   pat17    = Pattern.compile("(.+)\\.\\d+\\.xtm($|\\.html?)");
    public static final Pattern   pat18    = Pattern.compile("(.*)\\.isz($|\\.html?)", Pattern.CASE_INSENSITIVE);
    public static final Pattern   pat19    = Pattern.compile("(.*)\\.i\\d{2}$", Pattern.CASE_INSENSITIVE);
    public static final Pattern[] iszPats  = new Pattern[] { pat18, pat19 };

    public static enum EXTENSION_SETTINGS {
        KEEP,
        REMOVE_KNOWN,
        REMOVE_ALL
    }

    public static String cleanFileName(String name, boolean splitUpperLowerCase, boolean ignoreArchiveFilters, final EXTENSION_SETTINGS extensionSettings, boolean cleanup) {
        return cleanFileName(null, name, splitUpperLowerCase, ignoreArchiveFilters, extensionSettings, cleanup);
    }

    public static String cleanFileName(AbstractNode node, String name, boolean splitUpperLowerCase, boolean ignoreArchiveFilters, final EXTENSION_SETTINGS extensionSettings, boolean cleanup) {
        if (name == null) {
            return null;
        }
        boolean extensionStilExists = true;
        String before = name;
        if (!ignoreArchiveFilters) {
            /** remove rar extensions */
            for (Pattern Pat : rarPats) {
                name = getNameMatch(name, Pat);
                if (!before.equals(name)) {
                    extensionStilExists = false;
                    break;
                }
            }
            if (extensionStilExists) {
                /**
                 * remove 7zip/zip and hjmerge extensions
                 */
                before = name;
                for (Pattern Pat : zipPats) {
                    name = getNameMatch(name, Pat);
                    if (!before.equals(name)) {
                        extensionStilExists = false;
                        break;
                    }
                }
            }
            if (extensionStilExists) {
                /**
                 * remove isz extensions
                 */
                before = name;
                for (Pattern Pat : iszPats) {
                    name = getNameMatch(name, Pat);
                    if (!before.equals(name)) {
                        extensionStilExists = false;
                        break;
                    }
                }
            }
            if (extensionStilExists) {
                before = name;
                /* xtremsplit */
                name = getNameMatch(name, pat17);
                if (!before.equals(name)) {
                    extensionStilExists = false;
                }
            }
            if (extensionStilExists && ffsjPats != null) {
                /**
                 * FFSJ splitted files
                 *
                 * */
                before = name;
                for (Pattern Pat : ffsjPats) {
                    name = getNameMatch(name, Pat);
                    if (!before.equalsIgnoreCase(name)) {
                        extensionStilExists = false;
                        break;
                    }
                }
            }
        }
        /**
         * remove CDx,Partx
         */
        String tmpname = cutNameMatch(name, pat12);
        if (tmpname.length() > 3) {
            name = tmpname;
        }
        tmpname = cutNameMatch(name, pat13);
        if (tmpname.length() > 3) {
            name = tmpname;
        }
        /* remove extension */
        if (EXTENSION_SETTINGS.REMOVE_ALL.equals(extensionSettings) || EXTENSION_SETTINGS.REMOVE_KNOWN.equals(extensionSettings)) {
            while (true) {
                final int lastPoint = name.lastIndexOf(".");
                if (lastPoint > 0) {
                    final int extLength = (name.length() - (lastPoint + 1));
                    final String ext = name.substring(lastPoint + 1);
                    final ExtensionsFilterInterface knownExt = CompiledFiletypeFilter.getExtensionsFilterInterface(ext);
                    if (knownExt != null && !ArchiveExtensions.NUM.equals(knownExt)) {
                        /* make sure to cut off only known extensions */
                        name = name.substring(0, lastPoint);
                    } else if (extLength <= 4 && EXTENSION_SETTINGS.REMOVE_ALL.equals(extensionSettings) && ext.matches("^[0-9a-zA-z]+$")) {
                        /* make sure to cut off only known extensions */
                        if (extensionStilExists) {
                            name = name.substring(0, lastPoint);
                        } else {
                            break;
                        }
                    } else {
                        break;
                    }
                } else {
                    break;
                }
            }
        }
        /* remove ending ., - , _ */
        int removeIndex = -1;
        for (int i = name.length() - 1; i >= 0; i--) {
            final char c = name.charAt(i);
            if (c == ',' || c == '_' || c == '-') {
                removeIndex = i;
            } else {
                break;
            }
        }
        if (removeIndex > 0) {
            name = name.substring(0, removeIndex);
        }
        /* if enabled, replace dots and _ with spaces and do further clean ups */
        if (cleanup && org.jdownloader.settings.staticreferences.CFG_GENERAL.CLEAN_UP_FILENAMES.isEnabled()) {
            StringBuilder sb = new StringBuilder();
            char[] cs = name.toCharArray();
            char lastChar = 'a';
            for (int i = 0; i < cs.length; i++) {
                // splitFileNamesLikeThis to "split File Names Like This"
                if (splitUpperLowerCase && i > 0 && Character.isUpperCase(cs[i]) && Character.isLowerCase(cs[i - 1])) {
                    if (lastChar != ' ') {
                        sb.append(' ');
                    }
                    lastChar = ' ';
                }
                switch (cs[i]) {
                case '_':
                case '.':
                    if (lastChar != ' ') {
                        sb.append(' ');
                    }
                    lastChar = ' ';
                    break;
                default:
                    lastChar = cs[i];
                    sb.append(cs[i]);
                }
            }
            name = sb.toString();
        }
        return name.trim();
    }

    private static String getNameMatch(String name, Pattern pattern) {
        String match = new Regex(name, pattern).getMatch(0);
        if (match != null) {
            return match;
        }
        return name;
    }

    public static int comparepackages(String a, String b) {
        int c = 0;
        String aa = a.toLowerCase();
        String bb = b.toLowerCase();
        for (int i = 0; i < Math.min(aa.length(), bb.length()); i++) {
            if (aa.charAt(i) == bb.charAt(i)) {
                c++;
            }
        }
        if (Math.min(aa.length(), bb.length()) == 0) {
            return 0;
        }
        return c * 100 / Math.max(aa.length(), bb.length());
    }

    private static String cutNameMatch(String name, Pattern pattern) {
        if (name == null) {
            return null;
        }
        String match = new Regex(name, pattern).getMatch(0);
        if (match != null) {
            int firstpos = name.indexOf(match);
            String tmp = name.substring(0, firstpos);
            int lastpos = name.indexOf(match) + match.length();
            if (!(lastpos > name.length())) {
                tmp = tmp + name.substring(lastpos);
            }
            name = tmp;
            /* remove seq. dots */
            name = name.replaceAll("\\.{2,}", ".");
            name = name.replaceAll("\\.{2,}", ".");
        }
        return name;
    }
}
