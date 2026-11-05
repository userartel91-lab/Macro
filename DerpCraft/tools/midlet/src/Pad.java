import java.util.Hashtable;
import java.util.Vector;
import javax.microedition.lcdui.*;

/**
 * The remote itself: tabs of buttons drawn for a 432x240 landscape screen (scales to other sizes).
 * Touch: tap tabs and buttons, hold the hold-buttons. Keypad: * and # (or the soft keys) switch tabs, the
 * joystick moves between buttons, the centre key presses; on the Move tab 2/4/6/8 walk, 5 jumps.
 */
public class Pad extends Canvas {
    // button kinds
    static final int TAP = 0, HOLD = 1, CMD = 2, LOOK = 3, RAW = 4, SPECIAL = 5, SHIFT_TAP = 6, GESTURE = 7, FACE = 8;

    static class Btn {
        int col, row, cw, rh, kind, icon = -1;
        String label, arg;
        int x, y, w, h;
        Btn(int col, int row, int cw, int rh, String label, int kind, String arg) {
            this.col = col; this.row = row; this.cw = cw; this.rh = rh; this.label = label; this.kind = kind; this.arg = arg;
        }
    }

    static final String[] TABS = {"Мир", "Ход", "Камера", "Сцены", "Клав.", "Опции", "Чат", "Экран"};
    static final int T_WORLD = 0, T_MOVE = 1, T_CAM = 2, T_NPC = 3, T_KEYS = 4, T_OPTS = 5, T_CHAT = 6, T_VIEW = 7;
    static final int COLS = 8, ROWS = 5;

    // colours
    static final int BG = 0x16191E, PANEL = 0x262B33, BTN = 0x323944, BTN_HI = 0x4C8BF5, HOLD_ON = 0xE08A2E, TEXT = 0xFFFFFF,
            DIM = 0x9AA4B2, FOCUS = 0xFFD54A, GOOD = 0x4CC97A, BAD = 0xE5534B, CMD_C = 0x26402F, KEY_C = 0x2C3440, SPEC_C = 0x3D2F4A, HOLD_C = 0x1F3A44;

    final DerpRemote app;
    private final Btn[][] pages = new Btn[TABS.length][];
    private int tab = T_WORLD, focus;
    private Btn pressed;
    private final Font small = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_PLAIN, Font.SIZE_SMALL);
    private final Font bold = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_BOLD, Font.SIZE_SMALL);
    private final Font big = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_BOLD, Font.SIZE_LARGE);
    private Image icons22, icons16;
    static final int ICON_COLS = 12;

    // landscape on any phone: if the screen is portrait, everything is drawn into a 432x240-like picture and turned
    /** 0 = turn clockwise when the screen is portrait, 1 = counter-clockwise, 2 = never turn. */
    int rotateMode;
    private boolean rotated;
    private Image buffer;
    private int W, H, tabH = 22, statH = 18;

    // state from the game
    private Hashtable status = new Hashtable();
    private final Vector chat = new Vector();
    private String toast = "";
    private long toastTime;
    String lastCommand = "";

    // lists (keys, options, worlds)
    private Vector keyRows, optRows, worldRows;
    private int listSel, listTop;
    private boolean showWorlds, holdMode;

    // viewfinder
    private Image frame;
    private int fx, fy, fw, fh;
    private int clickButton;

    Pad(DerpRemote app) {
        this.app = app;
        setFullScreenMode(true);
        buildPages();
        try { icons22 = Image.createImage("/icons22.png"); } catch (Throwable t) { }
        try { icons16 = Image.createImage("/icons16.png"); } catch (Throwable t) { }
        if (icons16 == null) icons16 = icons22;
        if (icons22 == null) icons22 = icons16;
    }

    // ------------------------------------------------------------------------------------------ pages
    private void buildPages() {
        pages[T_WORLD] = new Btn[]{
                b(0, 0, "Утро", "morning", CMD, "time set day"), b(1, 0, "День", "noon", CMD, "time set noon"),
                b(2, 0, "Закат", "sunset", CMD, "time set sunset"), b(3, 0, "Вечер", "evening", CMD, "time set night"),
                b(4, 0, "Ночь", "midnight", CMD, "time set midnight"), b(5, 0, "Заря", "sunrise", CMD, "time set sunrise"),
                b(6, 0, "Стоп", "timestop", CMD, "time stop"), b(7, 0, "Идёт", "timeplay", CMD, "time start"),
                b(0, 1, "Креатив", "creative", CMD, "gamemode creative"), b(1, 1, "Выжив.", "survival", CMD, "gamemode survival"),
                b(2, 1, "Наблюд.", "spectator", CMD, "gamemode spectator"), b(3, 1, "Лечить", "heal", CMD, "heal"),
                b(4, 1, "-1 час", "clockminus", CMD, "time add -1000"), b(5, 1, "+1 час", "clockplus", CMD, "time add 1000"),
                b(6, 1, "x0.5", "fly", CMD, "speed 0.5"), b(7, 1, "x2", "fly", CMD, "speed 2"),
                b(0, 2, "HUD", "hud", TAP, "hideHud"), b(1, 2, "Скрин", "screenshot", TAP, "screenshot"),
                b(2, 2, "Вид F5", "perspective", TAP, "togglePerspective"), b(3, 2, "Плавно", "smooth", TAP, "smoothCamera"),
                b(4, 2, "F3", "debug", TAP, "debug"), b(5, 2, "Шейдеры", "shaders", TAP, "shaders"),
                b(6, 2, "Цвет", "grading", TAP, "grading"), b(7, 2, "Экран", "fullscreen", TAP, "fullscreen"),
                b(0, 3, "Инвент.", "inventory", TAP, "inventory"), b(1, 3, "3D Hub", "hub3d", TAP, "modelHub"),
                b(2, 3, "Esc", "esc", RAW, "256"), b(3, 3, "Enter", "enter", RAW, "257"),
                b(4, 3, "x1", "fly", CMD, "speed 1"), b(5, 3, "x5", "fly", CMD, "speed 5"),
                b(6, 3, "Команда", "terminal", SPECIAL, "cmd"), b(7, 3, "Повтор", "repeat", SPECIAL, "again"),
                b(0, 4, "Миры", "worlds", SPECIAL, "worlds"), b(1, 4, "В меню", "leave", SPECIAL, "leave"),
                b(2, 4, "Отпуст.", "release", SPECIAL, "release"), b(4, 4, "x10", "fly", CMD, "speed 10"),
                b(6, 4, "Связь", "wifi", SPECIAL, "setup"), b(7, 4, "Выход", "power", SPECIAL, "exit")};
        pages[T_MOVE] = new Btn[]{
                b(0, 0, "Присед", "sneak", HOLD, "sneak"), b(1, 0, "Вперёд", "go_up", HOLD, "forward"), b(2, 0, "Прыжок", "jump", HOLD, "jump"),
                b(0, 1, "Влево", "go_left", HOLD, "left"), b(1, 1, "Назад", "go_down", HOLD, "back"), b(2, 1, "Вправо", "go_right", HOLD, "right"),
                b(0, 2, "Бег", "sprint", HOLD, "sprint"), b(1, 2, "Зум", "zoom", HOLD, "zoom"), b(2, 2, "Чат", "chat", TAP, "chat"),
                b(3, 0, "Ломать", "attack", HOLD, "attack"), b(4, 0, "Класть", "place", HOLD, "use"),
                b(3, 1, "Взять", "pick", TAP, "pickItem"), b(4, 1, "Бросить", "drop", TAP, "drop"),
                b(3, 2, "Колесо", "wheelup", SPECIAL, "wheel+"), b(4, 2, "Колесо", "wheeldown", SPECIAL, "wheel-"),
                b(5, 0, "Быстро", "lookf_left", LOOK, "-15,0"), b(6, 0, "Вверх", "look_up", LOOK, "0,-4"), b(7, 0, "Быстро", "lookf_right", LOOK, "15,0"),
                b(5, 1, "Влево", "look_left", LOOK, "-5,0"), b(6, 1, "Вниз", "look_down", LOOK, "0,4"), b(7, 1, "Вправо", "look_right", LOOK, "5,0"),
                b(5, 2, "Вверх", "lookf_up", LOOK, "0,-10"), b(6, 2, "Прямо", "crosshair", SPECIAL, "levelview"), b(7, 2, "Вниз", "lookf_down", LOOK, "0,10"),
                b(0, 3, "1", null, TAP, "hotbar.1"), b(1, 3, "2", null, TAP, "hotbar.2"), b(2, 3, "3", null, TAP, "hotbar.3"), b(3, 3, "4", null, TAP, "hotbar.4"),
                b(4, 3, "5", null, TAP, "hotbar.5"), b(5, 3, "6", null, TAP, "hotbar.6"), b(6, 3, "7", null, TAP, "hotbar.7"), b(7, 3, "8", null, TAP, "hotbar.8"),
                b(0, 4, "9", null, TAP, "hotbar.9"), b(1, 4, "F4", "spectator", TAP, "spectator"), b(2, 4, "Вид F5", "perspective", TAP, "togglePerspective"),
                b(3, 4, "Esc", "esc", RAW, "256"), b(4, 4, "Отпуст.", "release", SPECIAL, "release")};
        pages[T_CAM] = new Btn[]{
                b(0, 0, "Свобод.", "freecam", TAP, "freeCamera"), b(1, 0, "Орбита", "orbit", TAP, "orbit"), b(2, 0, "Вокруг", "orbitme", SHIFT_TAP, "orbit"),
                b(3, 0, "Штатив", "tripod", TAP, "tripod"), b(4, 0, "Игрок", "playercam", CMD, "cam player"), b(5, 0, "Селфи", "selfie", TAP, "selfie"),
                b(6, 0, "Селфи 2", "selfiefree", TAP, "selfieFree"), b(7, 0, "F9", "panel", TAP, "cameraPanel"),
                b(0, 1, "Ключ", "keyadd", TAP, "pathKey"), b(1, 1, "Убрать", "keydel", TAP, "pathDelete"), b(2, 1, "Сброс", "trash", CMD, "cam clear"),
                b(3, 1, "Путь", "pathplay", TAP, "pathPlay"), b(4, 1, "5 с", "stopwatch", CMD, "cam play 5"), b(5, 1, "10 с", "stopwatch", CMD, "cam play 10"),
                b(6, 1, "20 с", "stopwatch", CMD, "cam play 20"), b(7, 1, "Петля", "loop", CMD, "cam play 10 on"),
                b(0, 2, "Зум +", "zoomin", HOLD, "zoomIn"), b(1, 2, "Зум -", "zoomout", HOLD, "zoomOut"), b(2, 2, "Зум 1x", "zoomreset", TAP, "zoomReset"),
                b(3, 2, "Крен", "rollleft", HOLD, "rollLeft"), b(4, 2, "Крен", "rollright", HOLD, "rollRight"), b(5, 2, "Ровно", "level", TAP, "level"),
                b(6, 2, "Сетка", "grid", TAP, "grid"), b(7, 2, "Рамка", "letterbox", CMD, "cam letterbox"),
                b(0, 3, "Глубина", "dof", TAP, "dof"), b(1, 3, "Фокус", "focus", TAP, "focus"), b(2, 3, "Авто", "autofocus", SHIFT_TAP, "focus"),
                b(3, 3, "f/1.4", "aperture", CMD, "cam f 1.4"), b(4, 3, "f/2.8", "aperture", CMD, "cam f 2.8"), b(5, 3, "f/8", "aperture", CMD, "cam f 8"),
                b(6, 3, "Тряска", "shake", CMD, "cam shake 0.3"), b(7, 3, "Без", "noshake", CMD, "cam shake 0"),
                b(0, 4, "18 мм", "lens", CMD, "cam lens 18"), b(1, 4, "35 мм", "lens", CMD, "cam lens 35"), b(2, 4, "50 мм", "lens", CMD, "cam lens 50"),
                b(3, 4, "85 мм", "lens", CMD, "cam lens 85"), b(4, 4, "135 мм", "lens", CMD, "cam lens 135"), b(5, 4, "Объект.", "lensreset", CMD, "cam lens 0"),
                b(6, 4, "Плавно", "easing", CMD, "cam easing 1"), b(7, 4, "Инфо", "info", CMD, "cam info")};
        Vector npc = new Vector();
        add(npc, b(0, 0, "Запись", "record", TAP, "record"));
        add(npc, b(1, 0, "Отмена", "reccancel", TAP, "recordCancel"));
        add(npc, b(2, 0, "Сцена", "scene", TAP, "scenePlay"));
        add(npc, b(3, 0, "Сначала", "rewind", CMD, "npc reset"));
        add(npc, b(4, 0, "Hub", "hub", TAP, "npcHub"));
        add(npc, b(5, 0, "Новый", "npcnew", CMD, "npc spawn"));
        add(npc, b(6, 0, "Список", "list", CMD, "npc list"));
        add(npc, b(7, 0, "Группы", "groups", CMD, "npc groups"));
        for (int i = 0; i < 8; i++) add(npc, b(i, 1, i == 0 ? "Без жеста" : "Жест " + i, "gesture", GESTURE, "" + i));
        add(npc, b(0, 2, "Жест 9", "gesture", GESTURE, "9"));
        for (int i = 0; i < 7; i++) add(npc, b(i + 1, 2, i == 0 ? "Обычное" : "Лицо " + (i + 1), "face", FACE, "" + (i == 0 ? 0 : i + 1)));
        add(npc, b(0, 3, "Ломать", "attack", HOLD, "attack"));
        add(npc, b(1, 3, "Класть", "place", HOLD, "use"));
        add(npc, b(2, 3, "Вперёд", "go_up", HOLD, "forward"));
        add(npc, b(3, 3, "Прыжок", "jump", HOLD, "jump"));
        add(npc, b(4, 3, "Присед", "sneak", HOLD, "sneak"));
        add(npc, b(5, 3, "Сцена?", "help", CMD, "npc scene"));
        add(npc, b(6, 3, "Мобы", "mobs", CMD, "mob spawning off"));
        add(npc, b(7, 3, "Отпуст.", "release", SPECIAL, "release"));
        pages[T_NPC] = toArray(npc);
        pages[T_KEYS] = new Btn[]{b(7, 0, "Обнов.", "refresh", SPECIAL, "loadkeys"), b(7, 1, "Вверх", "listup", SPECIAL, "up"),
                b(7, 2, "Держать", "hold", SPECIAL, "holdmode"), b(7, 3, "Вниз", "listdown", SPECIAL, "down"), b(7, 4, "Нажать", "tap", SPECIAL, "activate")};
        pages[T_OPTS] = new Btn[]{b(7, 0, "Обнов.", "refresh", SPECIAL, "loadopts"), b(7, 1, "Вверх", "listup", SPECIAL, "up"),
                b(7, 2, "Меньше", "minus", SPECIAL, "minus"), b(7, 3, "Вниз", "listdown", SPECIAL, "down"), b(7, 4, "Больше", "plus", SPECIAL, "plus")};
        pages[T_CHAT] = new Btn[]{b(7, 0, "Команда", "terminal", SPECIAL, "cmd"), b(7, 1, "Сказать", "say", SPECIAL, "say"),
                b(7, 2, "В меню", "keyboard", SPECIAL, "type"), b(7, 3, "Повтор", "repeat", SPECIAL, "again"), b(7, 4, "/help", "help", CMD, "help")};
        pages[T_VIEW] = new Btn[]{b(7, 0, "Живое", "auto", SPECIAL, "auto"), b(7, 1, "Крупно", "frame", SPECIAL, "viewsize"),
                b(7, 2, "ЛКМ", "mouse_l", SPECIAL, "clickbtn"), b(7, 3, "Esc", "esc", RAW, "256"), b(7, 4, "Enter", "enter", RAW, "257")};
    }

    /** Icon names in the order of res/icons22.png and icons16.png (tools/midlet/icons/gen.py prints this line). */
    static final String ICONS = "sunrise,morning,noon,sunset,evening,midnight,timestop,timeplay,clockminus,clockplus,creative,survival,spectator,heal,fly,hud,screenshot,perspective,smooth,debug,shaders,grading,fullscreen,inventory,hub3d,esc,enter,terminal,repeat,worlds,leave,release,wifi,power,go_up,go_down,go_left,go_right,jump,sneak,sprint,attack,place,pick,drop,wheelup,wheeldown,look_left,lookf_left,look_right,lookf_right,look_up,lookf_up,look_down,lookf_down,crosshair,chat,zoom,freecam,orbit,orbitme,tripod,playercam,selfie,selfiefree,panel,keyadd,keydel,trash,pathplay,stopwatch,loop,zoomin,zoomout,zoomreset,rollleft,rollright,level,grid,letterbox,dof,focus,autofocus,aperture,shake,noshake,lens,lensreset,easing,info,record,reccancel,scene,rewind,hub,npcnew,list,groups,gesture,face,mobs,refresh,listup,listdown,hold,tap,minus,plus,say,keyboard,help,frame,auto,mouse,mouse_l,mouse_r";

    private static int iconIndex(String name) {
        if (name == null) return -1;
        int i = 0, start = 0;
        while (start <= ICONS.length()) {
            int e = ICONS.indexOf(',', start);
            if (e < 0) e = ICONS.length();
            if (ICONS.substring(start, e).equals(name)) return i;
            i++;
            start = e + 1;
        }
        return -1;
    }

    private static Btn b(int c, int r, String label, String icon, int kind, String arg) {
        Btn b = new Btn(c, r, 1, 1, label, kind, arg);
        b.icon = iconIndex(icon);
        return b;
    }

    private static void add(Vector v, Btn b) { v.addElement(b); }

    private static Btn[] toArray(Vector v) {
        Btn[] a = new Btn[v.size()];
        v.copyInto(a);
        return a;
    }

    private void layout() {
        int sw = getWidth(), sh = getHeight();
        rotated = rotateMode != 2 && sh > sw;
        W = rotated ? sh : sw;
        H = rotated ? sw : sh;
        buffer = null;
        int top = tabH, area = H - tabH - statH;
        for (int p = 0; p < pages.length; p++) {
            Btn[] bs = pages[p];
            for (int i = 0; i < bs.length; i++) {
                Btn b = bs[i];
                b.x = b.col * W / COLS + 1;
                b.w = (b.col + b.cw) * W / COLS - b.col * W / COLS - 2;
                b.y = top + b.row * area / ROWS + 1;
                b.h = (b.row + b.rh) * area / ROWS - b.row * area / ROWS - 2;
            }
        }
        applyViewSize();
    }

    protected void sizeChanged(int w, int h) { layout(); redraw(); }

    /** Full redraw (everything); a new viewfinder frame only redraws the picture. */
    void redraw() { repaint(); }

    // ------------------------------------------------------------------------------------------ data from the network thread
    void connected() {
        app.net.wantStatus = true;
        app.net.lastStatus = 0;
        onMessage("Связь есть: " + app.net.host);
    }

    void onStatus(Hashtable h) { status = h; redraw(); }

    void onChat(String line, boolean toastIt) {
        synchronized (chat) {
            chat.addElement(line);
            while (chat.size() > 60) chat.removeElementAt(0);
        }
        if (toastIt) onMessage(line);
        else redraw();
    }

    void onMessage(String m) {
        toast = m;
        toastTime = System.currentTimeMillis();
        redraw();
    }

    void onRows(int kind, Vector rows) {
        if (kind == Net.KEYS) keyRows = rows;
        else if (kind == Net.OPTIONS) optRows = rows;
        else if (kind == Net.WORLDS) { worldRows = rows; showWorlds = true; listSel = 0; listTop = 0; }
        redraw();
    }

    /** "name=value" after changing an option. */
    void onOptionValue(String l) {
        int e = l.indexOf('=');
        if (optRows == null || e < 0) return;
        String n = l.substring(0, e), v = l.substring(e + 1);
        for (int i = 0; i < optRows.size(); i++) {
            Vector r = (Vector) optRows.elementAt(i);
            if (n.equals(r.elementAt(0))) r.setElementAt(v, 2);
        }
        redraw();
    }

    void onFrame(Image img) {
        boolean sameSize = frame != null && fw > 0 && img.getWidth() == fw && img.getHeight() == fh;
        frame = img;
        if (tab != T_VIEW || showWorlds) return;
        if (!sameSize) { redraw(); return; }
        // only the picture changed: repaint just its rectangle (much faster on a phone, above all when turned)
        if (!rotated) repaint(fx, fy, fw, fh);
        else if (rotateMode == 1) repaint(fy, W - fx - fw, fh, fw);
        else repaint(H - fy - fh, fx, fh, fw);
    }

    /** View sizes: whole area, medium, small (smaller decodes faster = more frames a second). */
    private int viewSize;
    static final String[] VIEW_SIZES = {"Крупно", "Средне", "Мелко"};

    private void applyViewSize() {
        int vw = 7 * W / COLS - 2, vh = H - tabH - statH - 2;
        int pct = viewSize == 0 ? 100 : viewSize == 1 ? 75 : 55;
        app.net.frameW = vw * pct / 100;
        app.net.frameH = vh * pct / 100;
    }

    // ------------------------------------------------------------------------------------------ drawing
    /** Re-lays out when the screen size or the turn changed (before drawing and before reading a touch or key). */
    private void checkSize() {
        int sw = getWidth(), sh = getHeight();
        boolean rot = rotateMode != 2 && sh > sw;
        if (rot != rotated || (rot ? W != sh || H != sw : W != sw || H != sh)) layout();
    }

    protected void paint(Graphics screen) {
        checkSize();
        if (tab == T_VIEW && !showWorlds && frame != null && fw == frame.getWidth() && fh == frame.getHeight() && clipInsideFrame(screen)) {
            if (!rotated) screen.drawImage(frame, fx, fy, Graphics.TOP | Graphics.LEFT);
            else if (rotateMode == 1) screen.drawRegion(frame, 0, 0, fw, fh, 6, fy, W - fx - fw, Graphics.TOP | Graphics.LEFT);
            else screen.drawRegion(frame, 0, 0, fw, fh, 5, H - fy - fh, fx, Graphics.TOP | Graphics.LEFT);
            return;
        }
        if (!rotated) { paintLandscape(screen); return; }
        if (buffer == null) buffer = Image.createImage(W, H);
        paintLandscape(buffer.getGraphics());
        // Sprite.TRANS_ROT90 = 5 (clockwise), TRANS_ROT270 = 6
        screen.drawRegion(buffer, 0, 0, W, H, rotateMode == 1 ? 6 : 5, 0, 0, Graphics.TOP | Graphics.LEFT);
    }

    /** True when the system asks to repaint only (part of) the picture: then only the new frame is drawn. */
    private boolean clipInsideFrame(Graphics g) {
        int x0, y0, w, h;
        if (!rotated) { x0 = fx; y0 = fy; w = fw; h = fh; }
        else if (rotateMode == 1) { x0 = fy; y0 = W - fx - fw; w = fh; h = fw; }
        else { x0 = H - fy - fh; y0 = fx; w = fh; h = fw; }
        int cx = g.getClipX(), cy = g.getClipY();
        return cx >= x0 && cy >= y0 && cx + g.getClipWidth() <= x0 + w && cy + g.getClipHeight() <= y0 + h;
    }

    /** Screen point -> point of the landscape picture. */
    private int lx(int x, int y) { return !rotated ? x : rotateMode == 1 ? W - 1 - y : y; }

    private int ly(int x, int y) { return !rotated ? y : rotateMode == 1 ? x : H - 1 - x; }

    /** Joystick directions turn with the picture. */
    private int turnAction(int ga) {
        if (!rotated) return ga;
        if (rotateMode == 1) return ga == UP ? RIGHT : ga == DOWN ? LEFT : ga == RIGHT ? DOWN : ga == LEFT ? UP : ga;
        return ga == UP ? LEFT : ga == DOWN ? RIGHT : ga == RIGHT ? UP : ga == LEFT ? DOWN : ga;
    }

    void setRotateMode(int m) { rotateMode = m; layout(); redraw(); }

    private void paintLandscape(Graphics g) {
        g.setColor(BG);
        g.fillRect(0, 0, W, H);
        drawTabs(g);
        if (showWorlds) drawList(g, worldRows, 2);
        else if (tab == T_KEYS) drawList(g, keyRows, 0);
        else if (tab == T_OPTS) drawList(g, optRows, 1);
        else if (tab == T_CHAT) drawChat(g);
        else if (tab == T_VIEW) drawView(g);
        if (!showWorlds) drawButtons(g);
        drawStatus(g);
    }

    private void drawTabs(Graphics g) {
        g.setFont(bold);
        for (int i = 0; i < TABS.length; i++) {
            int x = i * W / TABS.length, w = (i + 1) * W / TABS.length - x;
            g.setColor(i == tab ? BTN_HI : PANEL);
            g.fillRect(x, 0, w - 1, tabH - 1);
            g.setColor(i == tab ? TEXT : DIM);
            g.drawString(TABS[i], x + w / 2, (tabH - bold.getHeight()) / 2, Graphics.TOP | Graphics.HCENTER);
        }
    }

    /** Stream-Deck style tiles: coloured key, icon in the middle, caption under it; lit when the game is in that state. */
    private void drawButtons(Graphics g) {
        Btn[] bs = pages[tab];
        int fh = small.getHeight();
        for (int i = 0; i < bs.length; i++) {
            Btn b = bs[i];
            boolean held = b.kind == HOLD && app.net.isHeld(b.arg);
            boolean lit = highlighted(b);
            boolean down = b == pressed;
            int base = b.kind == CMD ? CMD_C : b.kind == SPECIAL ? SPEC_C : b.kind == HOLD || b.kind == LOOK ? HOLD_C : KEY_C;
            int c = held ? HOLD_ON : down ? BTN_HI : lit ? mix(base, BTN_HI, 45) : base;
            // key body with a lighter top edge and a dark bottom edge (a physical key)
            g.setColor(0x0B0D10);
            g.fillRoundRect(b.x, b.y + 1, b.w, b.h, 10, 10);
            g.setColor(c);
            g.fillRoundRect(b.x, b.y, b.w, b.h - 1, 10, 10);
            g.setColor(mix(c, 0xFFFFFF, 18));
            g.drawLine(b.x + 4, b.y + 1, b.x + b.w - 5, b.y + 1);
            if (lit && !down && !held) {
                g.setColor(BTN_HI);
                g.drawRoundRect(b.x, b.y, b.w - 1, b.h - 2, 10, 10);
                g.drawRoundRect(b.x + 1, b.y + 1, b.w - 3, b.h - 4, 8, 8);
            }
            if (i == focus && !hasPointerEvents()) {
                g.setColor(FOCUS);
                g.drawRoundRect(b.x, b.y, b.w - 1, b.h - 2, 10, 10);
                g.drawRoundRect(b.x + 1, b.y + 1, b.w - 3, b.h - 4, 8, 8);
            }
            String label = fit(labelOf(b), b.w - 4);
            int icon = iconOf(b);
            if (icon < 0) {
                // no icon (hotbar slots): a big number
                g.setFont(big);
                g.setColor(TEXT);
                g.drawString(label, b.x + b.w / 2, b.y + (b.h - big.getHeight()) / 2, Graphics.TOP | Graphics.HCENTER);
                continue;
            }
            int room = b.h - fh - 2;
            Image sheet = room >= 22 ? icons22 : icons16;
            int size = room >= 22 ? 22 : 16;
            boolean caption = room >= 14;
            int iy = caption ? b.y + 1 + (room - size) / 2 : b.y + (b.h - size) / 2;
            if (sheet != null) {
                g.drawRegion(sheet, (icon % ICON_COLS) * size, (icon / ICON_COLS) * size, size, size, 0,
                        b.x + b.w / 2, iy, Graphics.TOP | Graphics.HCENTER);
            }
            if (caption || sheet == null) {
                g.setFont(small);
                g.setColor(held || down ? TEXT : 0xDCE2EA);
                g.drawString(label, b.x + b.w / 2, sheet == null ? b.y + (b.h - fh) / 2 : b.y + b.h - fh - 1, Graphics.TOP | Graphics.HCENTER);
            }
        }
    }

    private static int mix(int a, int b, int pct) {
        int r = ((a >> 16) & 255) * (100 - pct) / 100 + ((b >> 16) & 255) * pct / 100;
        int gg = ((a >> 8) & 255) * (100 - pct) / 100 + ((b >> 8) & 255) * pct / 100;
        int bl = (a & 255) * (100 - pct) / 100 + (b & 255) * pct / 100;
        return r << 16 | gg << 8 | bl;
    }

    private int iconOf(Btn b) {
        if (b.kind == SPECIAL && b.arg.equals("clickbtn")) return iconIndex(clickButton == 0 ? "mouse_l" : "mouse_r");
        return b.icon;
    }

    /** Buttons that show the game's state (current mode, recording, playing...). */
    private boolean highlighted(Btn b) {
        if (b.kind == CMD) {
            if (b.arg.startsWith("gamemode ")) return b.arg.substring(9).equals(status.get("mode"));
            if (b.arg.equals("time stop")) return "0".equals(status.get("cycle"));
            if (b.arg.equals("time start")) return "1".equals(status.get("cycle"));
            if (b.arg.equals("cam player")) return "player".equals(status.get("cam"));
        }
        if (b.kind == TAP) {
            if (b.arg.equals("record")) return "1".equals(status.get("rec"));
            if (b.arg.equals("scenePlay")) return "1".equals(status.get("playing"));
            if (b.arg.equals("pathPlay")) return "1".equals(status.get("path"));
            if (b.arg.equals("freeCamera")) return "free".equals(status.get("cam"));
            if (b.arg.equals("orbit")) return "orbit".equals(status.get("cam"));
            if (b.arg.equals("tripod")) return "1".equals(status.get("tripod"));
            if (b.arg.equals("dof")) return "1".equals(status.get("dof"));
            if (b.arg.equals("hideHud")) return "0".equals(status.get("hud"));
            if (b.arg.equals("smoothCamera")) return "1".equals(status.get("smooth"));
            if (b.arg.equals("selfie")) return "1".equals(status.get("selfie"));
            if (b.arg.equals("selfieFree")) return "2".equals(status.get("selfie"));
            if (b.arg.startsWith("hotbar.")) return b.arg.substring(7).equals(status.get("slot"));
        }
        if (b.kind == SPECIAL && b.arg.equals("auto")) return app.net.autoFrame || app.net.streaming;
        if (b.kind == SPECIAL && b.arg.equals("holdmode")) return holdMode;
        return false;
    }

    private String labelOf(Btn b) {
        if (b.kind == SPECIAL && b.arg.equals("clickbtn")) return clickButton == 0 ? "ЛКМ" : "ПКМ";
        if (b.kind == SPECIAL && b.arg.equals("viewsize")) return VIEW_SIZES[viewSize];
        if (b.kind == TAP && b.arg.equals("pathKey")) {
            Object k = status.get("keys");
            return k == null || "0".equals(k) ? b.label : "Ключ " + k;
        }
        return b.label;
    }

    private String fit(String s, int w) {
        if (small.stringWidth(s) <= w) return s;
        while (s.length() > 1 && small.stringWidth(s + "..") > w) s = s.substring(0, s.length() - 1);
        return s + "..";
    }

    private int listRowsVisible() { return Math.max(1, (H - tabH - statH - 4) / rowH()); }

    private int rowH() { return small.getHeight() + 6; }

    /** kind 0 = keys, 1 = options, 2 = worlds. */
    private void drawList(Graphics g, Vector rows, int kind) {
        int x0 = 2, w = (kind == 2 ? W : 7 * W / COLS) - 4, y = tabH + 2;
        g.setFont(small);
        if (rows == null) {
            g.setColor(DIM);
            g.drawString(kind == 0 ? "Загрузка списка клавиш..." : kind == 1 ? "Загрузка настроек..." : "Загрузка миров...", x0 + 4, y + 4, Graphics.TOP | Graphics.LEFT);
            return;
        }
        if (kind == 2) {
            g.setColor(DIM);
            g.drawString("Выбери мир. Назад: нажми сюда, * или #", x0 + 4, y, Graphics.TOP | Graphics.LEFT);
            y += rowH();
        }
        int vis = listRowsVisible() - (kind == 2 ? 1 : 0);
        if (listSel < listTop) listTop = listSel;
        if (listSel >= listTop + vis) listTop = listSel - vis + 1;
        for (int i = listTop; i < rows.size() && i < listTop + vis; i++) {
            Vector r = (Vector) rows.elementAt(i);
            g.setColor(i == listSel ? BTN_HI : (i % 2 == 0 ? PANEL : 0x1E2229));
            g.fillRect(x0, y, w, rowH() - 1);
            g.setColor(TEXT);
            if (kind == 0) {
                String id = str(r, 0), cat = str(r, 1);
                String left = str(r, 2), right = str(r, 3);
                if (i == listTop || !cat.equals(str((Vector) rows.elementAt(i - 1), 1))) left = "[" + cat + "] " + left;
                if (app.net.isHeld(id)) { g.setColor(HOLD_ON); g.fillRect(x0, y, 6, rowH() - 1); g.setColor(TEXT); }
                g.drawString(fit(left, w - 80), x0 + 8, y + 3, Graphics.TOP | Graphics.LEFT);
                g.setColor(DIM);
                g.drawString(right, x0 + w - 4, y + 3, Graphics.TOP | Graphics.RIGHT);
            } else if (kind == 1) {
                String type = str(r, 1), v = str(r, 2);
                g.drawString(fit(str(r, 6), w - 90), x0 + 8, y + 3, Graphics.TOP | Graphics.LEFT);
                String shown = type.equals("bool") ? ("1".equals(v) ? "ВКЛ" : "выкл") : "< " + v + " >";
                g.setColor(type.equals("bool") ? ("1".equals(v) ? GOOD : DIM) : FOCUS);
                g.drawString(shown, x0 + w - 6, y + 3, Graphics.TOP | Graphics.RIGHT);
            } else {
                g.drawString(fit(str(r, 1), w - 100), x0 + 8, y + 3, Graphics.TOP | Graphics.LEFT);
                g.setColor(DIM);
                g.drawString(str(r, 2), x0 + w - 6, y + 3, Graphics.TOP | Graphics.RIGHT);
            }
            y += rowH();
        }
        // scroll bar
        if (rows.size() > vis) {
            int top = tabH + 2, hgt = vis * rowH();
            g.setColor(PANEL);
            g.fillRect(x0 + w, top, 3, hgt);
            g.setColor(DIM);
            g.fillRect(x0 + w, top + hgt * listTop / rows.size(), 3, Math.max(6, hgt * vis / rows.size()));
        }
    }

    private static String str(Vector r, int i) { return i < r.size() ? (String) r.elementAt(i) : ""; }

    private void drawChat(Graphics g) {
        int w = 7 * W / COLS - 6, bottom = H - statH - 2;
        g.setColor(PANEL);
        g.fillRect(2, tabH + 2, w + 2, bottom - tabH - 2);
        g.setFont(small);
        int fh = small.getHeight();
        Vector lines = new Vector();
        synchronized (chat) {
            for (int i = 0; i < chat.size(); i++) wrap((String) chat.elementAt(i), w - 6, lines);
        }
        int y = bottom - 2 - fh;
        g.setColor(TEXT);
        for (int i = lines.size() - 1; i >= 0 && y > tabH + 2; i--) {
            g.drawString((String) lines.elementAt(i), 6, y, Graphics.TOP | Graphics.LEFT);
            y -= fh;
        }
        if (chat.isEmpty()) {
            g.setColor(DIM);
            g.drawString("Здесь будет чат игры и ответы команд", 6, tabH + 6, Graphics.TOP | Graphics.LEFT);
        }
    }

    private void wrap(String s, int w, Vector out) {
        while (s.length() > 0) {
            int n = s.length();
            while (n > 1 && small.substringWidth(s, 0, n) > w) n--;
            if (n < s.length()) {
                int sp = s.lastIndexOf(' ', n);
                if (sp > n / 2) n = sp + 1;
            }
            out.addElement(s.substring(0, n));
            s = s.substring(n);
        }
    }

    private void drawView(Graphics g) {
        int aw = 7 * W / COLS - 2, ah = H - tabH - statH - 2, ax = 1, ay = tabH + 1;
        g.setColor(0x000000);
        g.fillRect(ax, ay, aw, ah);
        if (frame == null) {
            g.setFont(small);
            g.setColor(DIM);
            g.drawString("Видоискатель: «Кадр» или «Авто».", ax + 8, ay + 8, Graphics.TOP | Graphics.LEFT);
            g.drawString("Нажми на картинку = клик мышью", ax + 8, ay + 8 + small.getHeight(), Graphics.TOP | Graphics.LEFT);
            g.drawString("там (меню, кнопки, инвентарь).", ax + 8, ay + 8 + 2 * small.getHeight(), Graphics.TOP | Graphics.LEFT);
            fw = 0;
            return;
        }
        fw = frame.getWidth();
        fh = frame.getHeight();
        fx = ax + (aw - fw) / 2;
        fy = ay + (ah - fh) / 2;
        g.drawImage(frame, fx, fy, Graphics.TOP | Graphics.LEFT);
    }

    private void drawStatus(Graphics g) {
        int y = H - statH;
        g.setColor(PANEL);
        g.fillRect(0, y, W, statH);
        g.setFont(small);
        g.setColor(app.net.online() ? GOOD : BAD);
        g.fillArc(4, y + statH / 2 - 4, 8, 8, 0, 360);
        String text;
        if (System.currentTimeMillis() - toastTime < 3500 && toast.length() > 0) {
            g.setColor(toast.startsWith("!") ? BAD : FOCUS);
            text = toast;
        } else {
            g.setColor(TEXT);
            text = summary();
        }
        g.drawString(fit(text, W - 20), 16, y + (statH - small.getHeight()) / 2, Graphics.TOP | Graphics.LEFT);
    }

    private String summary() {
        Net n = app.net;
        if (tab == T_VIEW && (n.streaming || n.autoFrame))
            return "Видоискатель: " + (n.fps10 / 10) + "." + (n.fps10 % 10) + " к/с, " + (n.streaming ? "поток" : "по кадру (HTTP)")
                    + ", " + n.frameW + "x" + n.frameH + ".  Нажатие по картинке = клик";
        Object world = status.get("world");
        if (world == null) return app.net.online() ? "Связь есть" : "Нет связи с " + app.net.host;
        if (((String) world).length() == 0) return "В меню игры (вкладка Мир -> Миры)";
        StringBuffer sb = new StringBuffer();
        sb.append(world);
        try {
            int t = Integer.parseInt((String) status.get("time"));
            int hh = (t / 1000 + 6) % 24, mm = (t % 1000) * 60 / 1000;
            sb.append("  д").append(status.get("day")).append(' ').append(hh < 10 ? "0" : "").append(hh).append(':').append(mm < 10 ? "0" : "").append(mm);
        } catch (Exception e) { }
        String m = (String) status.get("mode");
        sb.append("  ").append("creative".equals(m) ? "Креатив" : "survival".equals(m) ? "Выживание" : "spectator".equals(m) ? "Наблюдатель" : m);
        sb.append("  кам:").append(status.get("cam"));
        if ("1".equals(status.get("rec"))) sb.append("  [REC]");
        sb.append("  ").append(status.get("focal")).append("мм");
        Object s = status.get("screen");
        if (s != null && ((String) s).length() > 0) sb.append("  [").append(s).append(']');
        return sb.toString();
    }

    // ------------------------------------------------------------------------------------------ actions
    private void press(Btn b) {
        Net n = app.net;
        switch (b.kind) {
            case TAP: n.tap(b.arg); break;
            case SHIFT_TAP: n.tapShift(b.arg); break;
            case HOLD: n.hold(b.arg, true); break;
            case CMD: n.cmd(b.arg); break;
            case RAW: n.raw(Integer.parseInt(b.arg)); break;
            case LOOK: {
                int c = b.arg.indexOf(',');
                n.lookX = Float.parseFloat(b.arg.substring(0, c));
                n.lookY = Float.parseFloat(b.arg.substring(c + 1));
                n.lastLook = 0;
                break;
            }
            case GESTURE: case FACE: {
                // hold the gesture / face key, press the number, let go (works while recording)
                String key = b.kind == GESTURE ? "gesture" : "face";
                n.sendFirst("/key?k=" + key + "&a=up");
                n.sendFirst("/raw?c=" + (48 + Integer.parseInt(b.arg)));
                n.sendFirst("/key?k=" + key + "&a=down");
                break;
            }
            case SPECIAL: special(b.arg); break;
        }
        redraw();
    }

    private void release(Btn b) {
        if (b == null) return;
        if (b.kind == HOLD) app.net.hold(b.arg, false);
        else if (b.kind == LOOK) { app.net.lookX = 0; app.net.lookY = 0; }
        redraw();
    }

    private void special(String a) {
        Net n = app.net;
        if (a.equals("cmd")) app.askText(0, lastCommand);
        else if (a.equals("say")) app.askText(1, "");
        else if (a.equals("type")) app.askText(2, "");
        else if (a.equals("again")) { if (lastCommand.length() > 0) n.cmd(lastCommand); else app.askText(0, ""); }
        else if (a.equals("worlds")) { worldRows = null; showWorlds = true; n.send("/worlds", Net.WORLDS); }
        else if (a.equals("leave")) { n.send("/leave", Net.OPEN); onMessage("Выход в меню, мир сохраняется..."); }
        else if (a.equals("release")) { n.releaseAll(); onMessage("Все клавиши отпущены"); }
        else if (a.equals("setup")) app.showSetup(null);
        else if (a.equals("exit")) app.exit();
        else if (a.equals("wheel+")) n.sendFirst("/scroll?d=1");
        else if (a.equals("wheel-")) n.sendFirst("/scroll?d=-1");
        else if (a.equals("levelview")) {
            try { n.sendFirst("/look?dx=0&dy=" + (-Integer.parseInt((String) status.get("pitch")))); } catch (Exception e) { }
        }
        else if (a.equals("loadkeys")) { keyRows = null; n.send("/keys", Net.KEYS); }
        else if (a.equals("loadopts")) { optRows = null; n.send("/options", Net.OPTIONS); }
        else if (a.equals("up")) moveSel(-1);
        else if (a.equals("down")) moveSel(1);
        else if (a.equals("holdmode")) holdMode = !holdMode;
        else if (a.equals("activate")) activateRow(0);
        else if (a.equals("minus")) activateRow(-1);
        else if (a.equals("plus")) activateRow(1);
        else if (a.equals("frame")) n.send("/frame?w=" + n.frameW + "&h=" + n.frameH + (n.png ? "&f=png" : "&q=55"), Net.FRAME);
        else if (a.equals("auto")) setLive(!(n.autoFrame || n.streaming));
        else if (a.equals("viewsize")) {
            viewSize = (viewSize + 1) % VIEW_SIZES.length;
            applyViewSize();
            frame = null;
            if (n.streaming) { restartLive = true; n.stopStream(); }
            else if (!n.autoFrame) special("frame");
            onMessage("Видоискатель: " + VIEW_SIZES[viewSize].toLowerCase() + " " + n.frameW + "x" + n.frameH);
        }
        else if (a.equals("clickbtn")) clickButton = 1 - clickButton;
    }

    private Vector currentRows() {
        if (showWorlds) return worldRows;
        return tab == T_KEYS ? keyRows : tab == T_OPTS ? optRows : null;
    }

    private void moveSel(int d) {
        Vector rows = currentRows();
        if (rows == null || rows.isEmpty()) return;
        listSel = Math.max(0, Math.min(rows.size() - 1, listSel + d));
        redraw();
    }

    /** dir: 0 = activate, -1 / +1 = smaller / bigger (options). */
    private void activateRow(int dir) {
        Vector rows = currentRows();
        if (rows == null || listSel >= rows.size()) return;
        Vector r = (Vector) rows.elementAt(listSel);
        Net n = app.net;
        if (showWorlds) {
            showWorlds = false;
            n.send("/open?w=" + Net.enc(str(r, 0)), Net.OPEN);
            onMessage("Открываю мир " + str(r, 1) + "...");
        } else if (tab == T_KEYS) {
            String id = str(r, 0);
            if (holdMode) n.hold(id, !n.isHeld(id));
            else n.tap(id);
        } else if (tab == T_OPTS) {
            String v = str(r, 1).equals("bool") ? "toggle" : dir < 0 ? "-" : "+";
            n.sendFirst("/option?n=" + Net.enc(str(r, 0)) + "&v=" + Net.enc(v));
        }
        redraw();
    }

    private boolean restartLive;

    /** The stream thread finished (network thread): start again with the new size if that was why it stopped. */
    void streamEnded() {
        if (restartLive && tab == T_VIEW) { restartLive = false; app.net.startStream(); }
        redraw();
    }

    /** Live viewfinder on / off: the socket stream, or frame by frame over HTTP if the phone has no sockets. */
    private void setLive(boolean on) {
        Net n = app.net;
        if (!on) { n.autoFrame = false; n.stopStream(); redraw(); return; }
        if (n.streamFailed) n.autoFrame = true;
        else n.startStream();
        redraw();
    }

    private void setTab(int t) {
        release(pressed);
        pressed = null;
        showWorlds = false;
        tab = (t + TABS.length) % TABS.length;
        focus = 0;
        listSel = 0;
        listTop = 0;
        Net n = app.net;
        n.wantLog = tab == T_CHAT;
        if (tab != T_VIEW) { n.autoFrame = false; n.stopStream(); }
        if (tab == T_KEYS && keyRows == null) n.send("/keys", Net.KEYS);
        if (tab == T_OPTS) n.send("/options", Net.OPTIONS);
        if (tab == T_CHAT) n.lastLog = 0;
        if (tab == T_VIEW) setLive(true);
        redraw();
    }

    // ------------------------------------------------------------------------------------------ touch
    private int downX, downY;

    protected void pointerPressed(int sx, int sy) {
        checkSize();
        int x = lx(sx, sy), y = ly(sx, sy);
        downX = x; downY = y;
        if (y < tabH) { setTab(x * TABS.length / Math.max(1, W)); return; }
        if (showWorlds || ((tab == T_KEYS || tab == T_OPTS) && x < 7 * W / COLS)) {
            int vis = listRowsVisible(), top = tabH + 2 + (showWorlds ? rowH() : 0);
            int i = listTop + (y - top) / rowH();
            Vector rows = currentRows();
            if (showWorlds && y < top) { showWorlds = false; redraw(); return; }
            if (y >= top && rows != null && i >= 0 && i < rows.size() && i < listTop + vis) {
                listSel = i;
                if (tab == T_OPTS && !showWorlds) {
                    int w = 7 * W / COLS;
                    activateRow(x < w / 2 ? -1 : 1);
                } else activateRow(0);
            }
            redraw();
            return;
        }
        if (tab == T_VIEW && fw > 0 && x >= fx && x < fx + fw && y >= fy && y < fy + fh) {
            int px = (x - fx) * 1000 / fw, py = (y - fy) * 1000 / fh;
            app.net.sendFirst("/click?x=" + (px / 1000) + "." + pad3(px % 1000) + "&y=" + (py / 1000) + "." + pad3(py % 1000) + "&b=" + clickButton);
            if (!app.net.autoFrame) app.net.send("/frame?w=" + app.net.frameW + "&h=" + app.net.frameH + (app.net.png ? "&f=png" : "&q=55"), Net.FRAME);
            onMessage("Клик " + (clickButton == 0 ? "ЛКМ" : "ПКМ"));
            return;
        }
        Btn[] bs = pages[tab];
        for (int i = 0; i < bs.length; i++) {
            Btn b = bs[i];
            if (x >= b.x && x < b.x + b.w && y >= b.y && y < b.y + b.h) {
                focus = i;
                pressed = b;
                press(b);
                return;
            }
        }
    }

    private static String pad3(int v) { return v < 10 ? "00" + v : v < 100 ? "0" + v : "" + v; }

    protected void pointerReleased(int x, int y) {
        Btn b = pressed;
        pressed = null;
        release(b);
    }

    protected void pointerDragged(int sx, int sy) {
        int y = ly(sx, sy);
        // swipe a list up / down
        if ((tab == T_KEYS || tab == T_OPTS || showWorlds) && Math.abs(y - downY) > rowH()) {
            Vector rows = currentRows();
            if (rows == null) return;
            listTop = Math.max(0, Math.min(Math.max(0, rows.size() - listRowsVisible()), listTop - (y - downY) / rowH()));
            if (listSel < listTop) listSel = listTop;
            if (listSel >= listTop + listRowsVisible()) listSel = listTop + listRowsVisible() - 1;
            downY = y;
            redraw();
        }
    }

    // ------------------------------------------------------------------------------------------ keypad
    private Btn keyHeld;

    protected void keyPressed(int code) {
        checkSize();
        if (code == KEY_STAR || code == -6) { setTab(tab - 1); return; }
        if (code == KEY_POUND || code == -7) { setTab(tab + 1); return; }
        // Move tab: number keys walk like WASD
        if (tab == T_MOVE && !showWorlds) {
            String id = null;
            switch (code) {
                case KEY_NUM2: id = "forward"; break;
                case KEY_NUM8: id = "back"; break;
                case KEY_NUM4: id = "left"; break;
                case KEY_NUM6: id = "right"; break;
                case KEY_NUM5: id = "jump"; break;
                case KEY_NUM0: id = "sprint"; break;
                case KEY_NUM7: id = "sneak"; break;
                case KEY_NUM9: id = "attack"; break;
            }
            if (id != null) { app.net.hold(id, true); redraw(); return; }
            if (code == KEY_NUM1) { app.net.lookX = -5; app.net.lastLook = 0; return; }
            if (code == KEY_NUM3) { app.net.lookX = 5; app.net.lastLook = 0; return; }
        }
        int ga = 0;
        try { ga = turnAction(getGameAction(code)); } catch (Exception e) { }
        boolean list = showWorlds || tab == T_KEYS || tab == T_OPTS;
        if (list && ga == UP) { moveSel(-1); return; }
        if (list && ga == DOWN) { moveSel(1); return; }
        if (list && tab == T_OPTS && !showWorlds && (ga == LEFT || ga == RIGHT)) { activateRow(ga == LEFT ? -1 : 1); return; }
        if (list && ga == FIRE) { activateRow(0); return; }
        if (ga == UP || ga == DOWN || ga == LEFT || ga == RIGHT) { moveFocus(ga); return; }
        if (ga == FIRE) {
            Btn[] bs = pages[tab];
            if (focus < bs.length) { keyHeld = bs[focus]; pressed = keyHeld; press(keyHeld); }
        }
    }

    protected void keyReleased(int code) {
        if (tab == T_MOVE) {
            String id = null;
            switch (code) {
                case KEY_NUM2: id = "forward"; break;
                case KEY_NUM8: id = "back"; break;
                case KEY_NUM4: id = "left"; break;
                case KEY_NUM6: id = "right"; break;
                case KEY_NUM5: id = "jump"; break;
                case KEY_NUM0: id = "sprint"; break;
                case KEY_NUM7: id = "sneak"; break;
                case KEY_NUM9: id = "attack"; break;
            }
            if (id != null) { app.net.hold(id, false); redraw(); return; }
            if (code == KEY_NUM1 || code == KEY_NUM3) { app.net.lookX = 0; return; }
        }
        if (keyHeld != null) {
            int ga = 0;
            try { ga = getGameAction(code); } catch (Exception e) { }
            if (ga == FIRE) { Btn b = keyHeld; keyHeld = null; pressed = null; release(b); }
        }
    }

    /** Joystick: jump to the nearest button in that direction. */
    private void moveFocus(int ga) {
        Btn[] bs = pages[tab];
        if (bs.length == 0) return;
        if (focus >= bs.length) focus = 0;
        Btn cur = bs[focus];
        int cx = cur.x + cur.w / 2, cy = cur.y + cur.h / 2, best = -1;
        long bestD = Long.MAX_VALUE;
        for (int i = 0; i < bs.length; i++) {
            if (i == focus) continue;
            int x = bs[i].x + bs[i].w / 2, y = bs[i].y + bs[i].h / 2, dx = x - cx, dy = y - cy;
            boolean ok = ga == LEFT ? dx < 0 && Math.abs(dy) <= -dx : ga == RIGHT ? dx > 0 && Math.abs(dy) <= dx
                    : ga == UP ? dy < 0 && Math.abs(dx) <= -dy * 2 : dy > 0 && Math.abs(dx) <= dy * 2;
            if (!ok) continue;
            long d = (long) dx * dx + (long) dy * dy * 2;
            if (d < bestD) { bestD = d; best = i; }
        }
        if (best >= 0) focus = best;
        redraw();
    }

    protected void hideNotify() {
        // a call or the menu covers the remote: never leave a key held in the game
        if (pressed != null) { release(pressed); pressed = null; }
        app.net.releaseAll();
    }
}
