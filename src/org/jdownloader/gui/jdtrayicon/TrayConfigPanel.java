package org.jdownloader.gui.jdtrayicon;

import org.appwork.storage.config.handler.KeyHandler;
import org.jdownloader.extensions.ExtensionConfigPanel;
import org.jdownloader.gui.jdtrayicon.translate._TRAY;
import org.jdownloader.settings.staticreferences.CFG_GUI;

import jd.gui.swing.jdgui.views.settings.components.Checkbox;
import jd.gui.swing.jdgui.views.settings.components.ComboBox;
import jd.gui.swing.jdgui.views.settings.components.PasswordInput;

public class TrayConfigPanel extends ExtensionConfigPanel<TrayExtension> {
    public TrayConfigPanel(final TrayExtension trayExtension) {
        super(trayExtension);
  
        this.extension = trayExtension;
       
        /* Tray configuration */
        @SuppressWarnings("unchecked")
        KeyHandler<OnCloseAction> keyHandler = CFG_TRAY_CONFIG.SH.getKeyHandler("OnCloseAction", KeyHandler.class);
        addPair(_TRAY.T.plugins_optional_JDLightTray_closetotray2(), null, new ComboBox<OnCloseAction>(keyHandler, new OnCloseAction[] { OnCloseAction.ASK, OnCloseAction.TO_TRAY, OnCloseAction.TO_TASKBAR, OnCloseAction.EXIT }, new String[] { OnCloseAction.ASK.getTranslation(), OnCloseAction.TO_TRAY.getTranslation(), OnCloseAction.TO_TASKBAR.getTranslation(), OnCloseAction.EXIT.getTranslation() }));
        KeyHandler<OnMinimizeAction> keyHandler2 = CFG_TRAY_CONFIG.SH.getKeyHandler("OnMinimizeAction", KeyHandler.class);
        addPair(_TRAY.T.plugins_optional_JDLightTray_minimizetotray(), null, new ComboBox<OnMinimizeAction>(keyHandler2, new OnMinimizeAction[] { OnMinimizeAction.TO_TRAY, OnMinimizeAction.TO_TASKBAR }, new String[] { OnMinimizeAction.TO_TRAY.getTranslation(), OnMinimizeAction.TO_TASKBAR.getTranslation() }));
        addPair(_TRAY.T.plugins_optional_JDLightTray_startMinimized(), null, new Checkbox(CFG_TRAY_CONFIG.START_MINIMIZED_ENABLED));
        addPair(_TRAY.T.plugins_optional_JDLightTray_singleClick(), null, new Checkbox(CFG_TRAY_CONFIG.TOOGLE_WINDOW_STATUS_WITH_SINGLE_CLICK_ENABLED));
        addPair(_TRAY.T.plugins_optional_JDLightTray_tooltip(), null, new Checkbox(CFG_TRAY_CONFIG.TOOL_TIP_ENABLED));
        addPair(_TRAY.T.plugins_optional_JDLightTray_clipboardindicator(), null, new Checkbox(CFG_TRAY_CONFIG.TRAY_ICON_CLIPBOARD_INDICATOR));
        addPair(_TRAY.T.plugins_optional_JDLightTray_hideifframevisible(), null, new Checkbox(CFG_TRAY_CONFIG.TRAY_ONLY_VISIBLE_IF_WINDOW_IS_HIDDEN_ENABLED));
        addPair(_TRAY.T.plugins_optional_JDLightTray_passwordRequired(), CFG_GUI.PASSWORD_PROTECTION_ENABLED, new PasswordInput(CFG_GUI.PASSWORD));
    }

    @Override
    public void save() {
    }

    private void updateHeaders(boolean b) {
    }

    @Override
    public void updateContents() {
    }
}
