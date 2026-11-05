import javax.microedition.lcdui.*;
import javax.microedition.midlet.MIDlet;
import javax.microedition.rms.RecordStore;

/** DerpCraft Remote: controls the game on the PC over home Wi-Fi. Landscape 432x240. */
public class DerpRemote extends MIDlet implements CommandListener {
    Display display;
    Pad pad;
    Net net;

    private Form setup;
    private TextField tfHost, tfPort, tfPin;
    private ChoiceGroup cgFmt, cgRot;
    private final Command cmdConnect = new Command("Подключить", Command.OK, 1);
    private final Command cmdExit = new Command("Выход", Command.EXIT, 2);
    private final Command cmdSend = new Command("Отправить", Command.OK, 1);
    private final Command cmdBack = new Command("Назад", Command.BACK, 2);
    private TextBox input;
    /** What the text box is for: 0 command, 1 chat message, 2 text into the open game menu. */
    private int inputMode;
    private boolean connecting;

    protected void startApp() {
        if (display != null) { if (pad != null && display.getCurrent() == pad) pad.repaint(); return; }
        display = Display.getDisplay(this);
        net = new Net(this);
        pad = new Pad(this);
        load();
        showSetup(null);
    }

    protected void pauseApp() { if (net != null) net.releaseAll(); }

    protected void destroyApp(boolean unconditional) {
        if (net != null) { net.releaseAll(); net.stop(); }
    }

    void exit() {
        destroyApp(true);
        notifyDestroyed();
    }

    // ------------------------------------------------------------------------------------------ settings
    private void load() {
        try {
            RecordStore rs = RecordStore.openRecordStore("derpremote", true);
            if (rs.getNumRecords() > 0) {
                java.util.Vector v = Net.split(new String(rs.getRecord(1), "UTF-8"), '\n');
                if (v.size() > 0) net.host = (String) v.elementAt(0);
                if (v.size() > 1) net.port = Integer.parseInt((String) v.elementAt(1));
                if (v.size() > 2) net.pin = (String) v.elementAt(2);
                if (v.size() > 3) net.png = "png".equals(v.elementAt(3));
                if (v.size() > 4) pad.setRotateMode(Integer.parseInt((String) v.elementAt(4)));
            }
            rs.closeRecordStore();
        } catch (Exception e) { }
        if (net.host.length() == 0) net.host = "192.168.1.";
    }

    private void save() {
        try {
            byte[] b = (net.host + "\n" + net.port + "\n" + net.pin + "\n" + (net.png ? "png" : "jpg") + "\n" + pad.rotateMode).getBytes("UTF-8");
            RecordStore rs = RecordStore.openRecordStore("derpremote", true);
            if (rs.getNumRecords() > 0) rs.setRecord(1, b, 0, b.length); else rs.addRecord(b, 0, b.length);
            rs.closeRecordStore();
        } catch (Exception e) { }
    }

    // ------------------------------------------------------------------------------------------ screens
    void showSetup(String error) {
        net.releaseAll();
        net.wantStatus = false;
        net.autoFrame = false;
        setup = new Form("DerpCraft Remote");
        if (error != null) setup.append(new StringItem("Ошибка: ", error + "\n"));
        setup.append(new StringItem(null, "Адрес и PIN видны в игре: в главном меню внизу слева, или команда /remote\n"));
        tfHost = new TextField("IP компьютера", net.host, 40, TextField.ANY);
        tfPort = new TextField("Порт", "" + net.port, 5, TextField.NUMERIC);
        tfPin = new TextField("PIN", net.pin, 8, TextField.NUMERIC);
        cgFmt = new ChoiceGroup("Видоискатель", Choice.EXCLUSIVE, new String[]{"JPEG (быстро)", "PNG (если JPEG не показывается)"}, null);
        cgFmt.setSelectedIndex(net.png ? 1 : 0, true);
        setup.append(tfHost);
        setup.append(tfPort);
        setup.append(tfPin);
        cgRot = new ChoiceGroup("Альбомный экран", Choice.EXCLUSIVE, new String[]{"Повернуть вправо (если экран стоит)", "Повернуть влево", "Не поворачивать"}, null);
        cgRot.setSelectedIndex(pad.rotateMode, true);
        setup.append(cgFmt);
        setup.append(cgRot);
        setup.addCommand(cmdConnect);
        setup.addCommand(cmdExit);
        setup.setCommandListener(this);
        display.setCurrent(setup);
    }

    void showPad() { display.setCurrent(pad); pad.repaint(); }

    /** Native text entry: command (with or without /), chat message, or text typed into the open menu. */
    void askText(int mode, String initial) {
        inputMode = mode;
        String title = mode == 0 ? "Команда (/time set day ...)" : mode == 1 ? "Сообщение в чат" : "Текст в меню игры";
        input = new TextBox(title, initial, 256, TextField.ANY);
        input.addCommand(cmdSend);
        input.addCommand(cmdBack);
        input.setCommandListener(this);
        display.setCurrent(input);
    }

    public void commandAction(Command c, Displayable d) {
        if (c == cmdExit) { exit(); return; }
        if (d == setup && c == cmdConnect) {
            if (connecting) return;
            net.host = tfHost.getString().trim();
            try { net.port = Integer.parseInt(tfPort.getString().trim()); } catch (Exception e) { net.port = 25580; }
            net.pin = tfPin.getString().trim();
            net.png = cgFmt.getSelectedIndex() == 1;
            pad.setRotateMode(cgRot.getSelectedIndex());
            save();
            connecting = true;
            Alert a = new Alert("Подключение", "Связь с " + net.host + ":" + net.port + " ...", null, AlertType.INFO);
            a.setTimeout(Alert.FOREVER);
            display.setCurrent(a, setup);
            net.start();
            net.send("/ping", Net.PING);
            return;
        }
        if (d == input) {
            if (c == cmdSend) {
                String t = input.getString();
                if (t.length() > 0) {
                    if (inputMode == 0) { net.cmd(t); pad.lastCommand = t; }
                    else if (inputMode == 1) net.sendFirst("/say?t=" + Net.enc(t));
                    else net.sendFirst("/type?t=" + Net.enc(t));
                }
            }
            showPad();
        }
    }

    /** Answer to /ping (network thread). */
    void onPing(boolean ok, String error) {
        connecting = false;
        if (ok) { pad.connected(); showPad(); }
        else showSetup(error == null || error.length() == 0 ? "нет ответа" : error);
    }
}
