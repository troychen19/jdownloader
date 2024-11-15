package org.jdownloader.captcha.v2.solver.solver9kw;

import java.awt.Color;
import java.awt.Point;
import java.io.IOException;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JProgressBar;

import org.appwork.swing.components.tooltips.PanelToolTip;
import org.appwork.swing.components.tooltips.ToolTipController;
import org.appwork.swing.components.tooltips.TooltipPanel;
import org.appwork.utils.swing.EDTRunner;
import org.appwork.utils.swing.SwingUtils;
import org.appwork.utils.swing.locator.AbstractLocator;
import org.jdownloader.DomainInfo;
import org.jdownloader.gui.IconKey;
import org.jdownloader.gui.translate._GUI;
import org.jdownloader.images.NewTheme;
import org.jdownloader.updatev2.gui.LAFOptions;

import jd.gui.swing.jdgui.components.premiumbar.ServicePanel;
import net.miginfocom.swing.MigLayout;

public class ServicePanel9kwTooltip extends PanelToolTip {
    private Color               color;
    private ServicePanel        owner;
    private JComponent          activeComponent;
    private NineKwSolverService service;

    public Point getDesiredLocation(JComponent activeComponent, Point ttPosition) {
        if (activeComponent != null) {
            this.activeComponent = activeComponent;
        }
        ttPosition.y = this.activeComponent.getLocationOnScreen().y - getPreferredSize().height;
        ttPosition.x = this.activeComponent.getLocationOnScreen().x;
        return AbstractLocator.correct(ttPosition, getPreferredSize());
    }

    public ServicePanel9kwTooltip(ServicePanel owner, final NineKwSolverService service) {
        super(new TooltipPanel("ins 0,wrap 1", "[grow,fill]", "[grow,fill]"));
        this.service = service;
        this.owner = owner;
        color = (LAFOptions.getInstance().getColorForTooltipForeground());
        JProgressBar progress = new JProgressBar();
        progress.setIndeterminate(true);
        panel.setLayout(new MigLayout("ins 0,wrap 1", "[grow,fill]", "[]"));
        final DomainInfo di = DomainInfo.getInstance("9kw.eu");
        final Icon icon = di.getFavIcon();
        JLabel header = new JLabel("9kw Captcha Solver", icon, JLabel.LEFT);
        header.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, LAFOptions.getInstance().getColorForTooltipForeground()));
        SwingUtils.toBold(header);
        header.setForeground(LAFOptions.getInstance().getColorForTooltipForeground());
        panel.add(header, "gapbottom 5,spanx");
        panel.add(progress);
        // panel.setPreferredSize(new Dimension(300, 100));
        new Thread() {
            public void run() {
                try {
                    final NineKWAccount account = service.loadAccount();
                    new EDTRunner() {
                        @Override
                        protected void runInEDT() {
                            if (!isShowing()) {
                                return;
                            }
                            panel.removeAll();
                            // panel.setPreferredSize(null);
                            panel.setLayout(new MigLayout("ins 0,wrap 2", "[][grow,align right]", "[]0"));
                            if (!account.isValid()) {
                                // panel.setLayout(new MigLayout("ins 0,wrap 1", "[grow,fill]", "[]"));
                                JLabel header = new JLabel("9kw Captcha Solver", icon, JLabel.LEFT);
                                header.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, LAFOptions.getInstance().getColorForTooltipForeground()));
                                SwingUtils.toBold(header);
                                header.setForeground(LAFOptions.getInstance().getColorForTooltipForeground());
                                panel.add(header, "gapbottom 5,spanx");
                                panel.add(lbl(_GUI.T.ServicePanel9kwTooltip_runInEDT_error2(""), NewTheme.I().getIcon(IconKey.ICON_ERROR, 18), JLabel.LEFT));
                                panel.add(lbl(account.getError()), "gapleft 22");
                            } else {
                                JLabel header = new JLabel("9kw Captcha Solver", icon, JLabel.LEFT);
                                header.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, LAFOptions.getInstance().getColorForTooltipForeground()));
                                SwingUtils.toBold(header);
                                header.setForeground(LAFOptions.getInstance().getColorForTooltipForeground());
                                panel.add(header, "spanx,gapbottom 5,pushx,growx");
                                panel.add(lbl("Requested/Send:", NewTheme.I().getIcon(IconKey.ICON_QUESTION, 18), JLabel.LEFT));
                                panel.add(lbl(account.getRequests() + "/" + account.getSend() + " Captcha(s)"));
                                panel.add(lbl("Solved:", NewTheme.I().getIcon(IconKey.ICON_OK, 18), JLabel.LEFT));
                                panel.add(lbl(account.getSolved() + " Captcha(s)"));
                                panel.add(lbl("Error/Send:", NewTheme.I().getIcon(IconKey.ICON_ERROR, 18), JLabel.LEFT));
                                panel.add(lbl(account.getSkipped() + "/" + account.getSendError() + " Captcha(s)"));
                                panel.add(lbl("Feedback: ", NewTheme.I().getIcon(IconKey.ICON_LOGIN, 18), JLabel.LEFT));
                                panel.add(lbl("OK:" + account.getOK() + " NotOK:" + account.getNotOK() + " Unused:" + account.getUnused()));
                                JLabel header2 = new JLabel("Account", icon, JLabel.LEFT);
                                header2.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, LAFOptions.getInstance().getColorForTooltipForeground()));
                                SwingUtils.toBold(header2);
                                header2.setForeground(LAFOptions.getInstance().getColorForTooltipForeground());
                                panel.add(header2, "spanx,gapbottom 5,pushx,growx");
                                panel.add(lbl(_GUI.T.ServicePanel9kwTooltip_runInEDT_credits_(), NewTheme.I().getIcon(IconKey.ICON_MONEY, 18), JLabel.LEFT));
                                panel.add(lbl(account.getCreditBalance() + ""));
                                panel.add(lbl(_GUI.T.ServicePanel9kwTooltip_runInEDT_solved(), NewTheme.I().getIcon(IconKey.ICON_LOGIN, 18), JLabel.LEFT));
                                panel.add(lbl(account.getSolved9kw() + ""));
                                panel.add(lbl(_GUI.T.ServicePanel9kwTooltip_runInEDT_answered(), NewTheme.I().getIcon(IconKey.ICON_LOGOUT, 18), JLabel.LEFT));
                                panel.add(lbl(account.getAnswered9kw() + ""));
                            }
                            JLabel header3 = new JLabel("Server", icon, JLabel.LEFT);
                            header3.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, LAFOptions.getInstance().getColorForTooltipForeground()));
                            SwingUtils.toBold(header3);
                            header3.setForeground(LAFOptions.getInstance().getColorForTooltipForeground());
                            panel.add(header3, "spanx,gapbottom 5,pushx,growx");
                            panel.add(lbl(_GUI.T.ServicePanel9kwTooltip_runInEDT_avgsolvetime(), NewTheme.I().getIcon(IconKey.ICON_TRUE, 18), JLabel.LEFT));
                            panel.add(lbl(account.getAvgSolvtime() + "s"));
                            panel.add(lbl(_GUI.T.ServicePanel9kwTooltip_runInEDT_queue(), NewTheme.I().getIcon(IconKey.ICON_TRUE, 18), JLabel.LEFT));
                            panel.add(lbl(account.getQueue() + "|" + account.getQueue1() + "|" + account.getQueue2()));
                            panel.add(lbl(_GUI.T.ServicePanel9kwTooltip_runInEDT_worker(), NewTheme.I().getIcon(IconKey.ICON_TRUE, 18), JLabel.LEFT));
                            panel.add(lbl(account.getWorker()));
                            panel.add(lbl(_GUI.T.ServicePanel9kwTooltip_runInEDT_inwork(), NewTheme.I().getIcon(IconKey.ICON_TRUE, 18), JLabel.LEFT));
                            panel.add(lbl(account.getInWork()));
                            JLabel header4 = new JLabel("Workerdetails", icon, JLabel.LEFT);
                            header4.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, LAFOptions.getInstance().getColorForTooltipForeground()));
                            SwingUtils.toBold(header4);
                            header4.setForeground(LAFOptions.getInstance().getColorForTooltipForeground());
                            panel.add(header4, "spanx,gapbottom 5,pushx,growx");
                            panel.add(lbl(_GUI.T.ServicePanel9kwTooltip_runInEDT_worker_text() + "/" + _GUI.T.ServicePanel9kwTooltip_runInEDT_worker_textonly() + ":", NewTheme.I().getIcon(IconKey.ICON_TRUE, 18), JLabel.LEFT));
                            panel.add(lbl(account.getWorkerText() + "/" + account.getWorkerTextOnly()));
                            panel.add(lbl(_GUI.T.ServicePanel9kwTooltip_runInEDT_worker_mouse() + "/" + _GUI.T.ServicePanel9kwTooltip_runInEDT_worker_multimouse() + ":", NewTheme.I().getIcon(IconKey.ICON_TRUE, 18), JLabel.LEFT));
                            panel.add(lbl(account.getWorkerMouse() + "/" + account.getWorkerMultiMouse()));
                            panel.add(lbl(_GUI.T.ServicePanel9kwTooltip_runInEDT_worker_confirm() + "/" + _GUI.T.ServicePanel9kwTooltip_runInEDT_worker_audio() + ":", NewTheme.I().getIcon(IconKey.ICON_TRUE, 18), JLabel.LEFT));
                            panel.add(lbl(account.getWorkerConfirm() + "/" + account.getWorkerAudio()));
                            panel.add(lbl(_GUI.T.ServicePanel9kwTooltip_runInEDT_worker_rotate() + "/" + _GUI.T.ServicePanel9kwTooltip_runInEDT_worker_puzzle() + ":", NewTheme.I().getIcon(IconKey.ICON_TRUE, 18), JLabel.LEFT));
                            panel.add(lbl(account.getWorkerRotate() + "/" + account.getWorkerPuzzle()));
                            panel.add(lbl(_GUI.T.ServicePanel9kwTooltip_runInEDT_worker_special() + ":", NewTheme.I().getIcon(IconKey.ICON_TRUE, 18), JLabel.LEFT));
                            panel.add(lbl(account.getWorkerSpecial()));
                            // panel.revalidate();
                            // revalidate();
                            // repaint();
                            ToolTipController.getInstance().show(ServicePanel9kwTooltip.this);
                        }
                    };
                } catch (IOException e) {
                    new EDTRunner() {
                        @Override
                        protected void runInEDT() {
                            panel.removeAll();
                            panel.add(lbl(_GUI.T.ServicePanel9kwTooltip_runInEDT_error()));
                            panel.repaint();
                        }
                    };
                }
            }
        }.start();
    }

    private JLabel lbl(String string, Icon icon, int left) {
        JLabel ret = new JLabel(string, icon, left);
        ret.setForeground(LAFOptions.getInstance().getColorForTooltipForeground());
        return ret;
    }

    private JLabel lbl(Object string) {
        return lbl(string + "", null, JLabel.LEADING);
    }
}
