package net.kdt.pojavlaunch;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;

import net.kdt.pojavlaunch.customcontrols.keyboard.TouchCharInput;

/**
 * Elorin's game screen. It reuses the upstream Java display and JVM launch, then
 * replaces the touch handling and on-screen buttons with game-oriented controls:
 *
 * Touch mode (default): tap = left click where you touch, long-press = right click,
 * drag = left-button drag, two-finger drag = camera (arrow keys).
 * Trackpad mode: one finger moves a pointer, tap = left click at the pointer,
 * long-press = right click, L / R buttons hold the mouse buttons for dragging.
 *
 * The top bar holds a keyboard show/hide button, the mode switch, a one-shot
 * right-tap toggle, Esc, and a menu for less common actions.
 */
public class ElorinGameActivity extends JavaGUILauncherActivity {
    private static final String PREFS = "elorin_controls";
    /** Long enough that clients which poll mouse state every game tick still see the press. */
    private static final long CLICK_HOLD_MS = 60;
    private static final long LONG_PRESS_MS = 450;
    private static final float TRACKPAD_SPEED = 1.6f;

    private FrameLayout root;
    private int logIndex;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    private View canvas;
    private TouchCharInput keyboardInput;
    private ImageView pointer;
    private LinearLayout mouseButtons;
    private LinearLayout arrowPad;
    private Button modeButton;
    private Button rightTapButton;
    private Button keyboardButton;

    private boolean trackpad;
    private boolean rightTapArmed;
    private boolean keyboardOpen;
    private float cursorX, cursorY;

    // Gesture state for the single-finger path.
    private boolean pending;
    private boolean leftHeld;
    private boolean longPressFired;
    private float downX, downY, lastX, lastY;
    private int touchSlop;

    // Two-finger camera state.
    private boolean camera;
    private float cameraStartX, cameraStartY;
    private int heldHorizontal, heldVertical;

    private final Runnable longPress = () -> {
        if (!pending || camera) return;
        pending = false;
        longPressFired = true;
        click(AWTInputEvent.BUTTON3_DOWN_MASK);
        if (canvas != null) canvas.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
    };

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (isFinishing()) return;
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();

        canvas = findViewById(R.id.installmod_surfaceview);
        keyboardInput = findViewById(R.id.awt_touch_char);

        // Remove the upstream buttons; their functions are re-provided below.
        ViewGroup upstream = (ViewGroup) canvas.getParent();
        for (int i = 0; i < upstream.getChildCount(); i++) {
            View child = upstream.getChildAt(i);
            if (child instanceof Button) child.setVisibility(View.GONE);
        }
        findViewById(R.id.main_touchpad).setVisibility(View.GONE);
        canvas.setOnTouchListener(null);

        // Add the controls to the upstream root, below its log viewer, so "View log"
        // still covers the game and receives touches.
        FrameLayout root = (FrameLayout) upstream.getParent();
        logIndex = root.indexOfChild(findViewById(R.id.launcherLoggerView));
        this.root = root;

        View touchSurface = new View(this);
        touchSurface.setOnTouchListener((v, e) -> { onGameTouch(e); return true; });
        addControl(touchSurface, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        pointer = new ImageView(this);
        pointer.setImageResource(R.drawable.ic_mouse_pointer);
        pointer.setVisibility(View.GONE);
        addControl(pointer, new FrameLayout.LayoutParams(dp(18), dp(27)));

        addControl(buildTopBar(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END));

        mouseButtons = new LinearLayout(this);
        mouseButtons.setOrientation(LinearLayout.HORIZONTAL);
        mouseButtons.addView(holdButton("L", () -> mouse(AWTInputEvent.BUTTON1_DOWN_MASK, true),
                () -> mouse(AWTInputEvent.BUTTON1_DOWN_MASK, false)), square(64));
        mouseButtons.addView(holdButton("R", () -> mouse(AWTInputEvent.BUTTON3_DOWN_MASK, true),
                () -> mouse(AWTInputEvent.BUTTON3_DOWN_MASK, false)), square(64));
        FrameLayout.LayoutParams mouseParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        mouseParams.setMargins(0, 0, dp(12), dp(12));
        addControl(mouseButtons, mouseParams);

        arrowPad = buildArrowPad();
        FrameLayout.LayoutParams arrowParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        arrowParams.setMargins(0, 0, dp(12), dp(88));
        addControl(arrowPad, arrowParams);

        trackpad = prefs.getBoolean("trackpad", false);
        arrowPad.setVisibility(prefs.getBoolean("arrows", true) ? View.VISIBLE : View.GONE);
        canvas.post(() -> {
            cursorX = canvas.getX() + canvas.getWidth() / 2f;
            cursorY = canvas.getY() + canvas.getHeight() / 2f;
            applyMode();
        });
    }

    private void addControl(View view, FrameLayout.LayoutParams params) {
        if (logIndex < 0) root.addView(view, params);
        else root.addView(view, logIndex++, params);
    }

    // ---- Touch handling ----------------------------------------------------------

    private void onGameTouch(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                camera = false;
                longPressFired = false;
                pending = true;
                downX = lastX = e.getX();
                downY = lastY = e.getY();
                if (!trackpad) moveCursor(downX, downY);
                handler.postDelayed(longPress, LONG_PRESS_MS);
                break;

            case MotionEvent.ACTION_POINTER_DOWN:
                if (e.getPointerCount() == 2) startCamera(e);
                break;

            case MotionEvent.ACTION_MOVE:
                if (camera) { updateCamera(e); break; }
                float x = e.getX(), y = e.getY();
                boolean beyondSlop = Math.hypot(x - downX, y - downY) > touchSlop;
                if (pending && beyondSlop) {
                    pending = false;
                    handler.removeCallbacks(longPress);
                    if (!trackpad && !longPressFired) {
                        // Direct mode drag: hold the left button from the touch-down point.
                        mouse(AWTInputEvent.BUTTON1_DOWN_MASK, true);
                        leftHeld = true;
                    }
                }
                if (trackpad) {
                    moveCursor(cursorX + (x - lastX) * TRACKPAD_SPEED, cursorY + (y - lastY) * TRACKPAD_SPEED);
                } else if (!pending) {
                    moveCursor(x, y);
                }
                lastX = x;
                lastY = y;
                break;

            case MotionEvent.ACTION_POINTER_UP:
                if (camera) releaseCameraKeys();
                break;

            case MotionEvent.ACTION_UP:
                handler.removeCallbacks(longPress);
                if (camera) {
                    releaseCameraKeys();
                    camera = false;
                } else if (pending && !longPressFired) {
                    boolean right = rightTapArmed;
                    if (right) setRightTap(false);
                    click(right ? AWTInputEvent.BUTTON3_DOWN_MASK : AWTInputEvent.BUTTON1_DOWN_MASK);
                }
                if (leftHeld) {
                    mouse(AWTInputEvent.BUTTON1_DOWN_MASK, false);
                    leftHeld = false;
                }
                pending = false;
                break;

            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(longPress);
                releaseCameraKeys();
                camera = false;
                if (leftHeld) mouse(AWTInputEvent.BUTTON1_DOWN_MASK, false);
                leftHeld = false;
                pending = false;
                break;
        }
    }

    private void startCamera(MotionEvent e) {
        handler.removeCallbacks(longPress);
        pending = false;
        if (leftHeld) {
            mouse(AWTInputEvent.BUTTON1_DOWN_MASK, false);
            leftHeld = false;
        }
        camera = true;
        cameraStartX = (e.getX(0) + e.getX(1)) / 2f;
        cameraStartY = (e.getY(0) + e.getY(1)) / 2f;
    }

    private void updateCamera(MotionEvent e) {
        if (e.getPointerCount() < 2) return;
        float dx = (e.getX(0) + e.getX(1)) / 2f - cameraStartX;
        float dy = (e.getY(0) + e.getY(1)) / 2f - cameraStartY;
        int deadZone = dp(36);
        int horizontal = dx < -deadZone ? AWTInputEvent.VK_LEFT : dx > deadZone ? AWTInputEvent.VK_RIGHT : 0;
        int vertical = dy < -deadZone ? AWTInputEvent.VK_UP : dy > deadZone ? AWTInputEvent.VK_DOWN : 0;
        heldHorizontal = swapKey(heldHorizontal, horizontal);
        heldVertical = swapKey(heldVertical, vertical);
    }

    private void releaseCameraKeys() {
        heldHorizontal = swapKey(heldHorizontal, 0);
        heldVertical = swapKey(heldVertical, 0);
    }

    private static int swapKey(int held, int wanted) {
        if (held == wanted) return held;
        if (held != 0) AWTInputBridge.sendKey(' ', held, 0);
        if (wanted != 0) AWTInputBridge.sendKey(' ', wanted, 1);
        return wanted;
    }

    // ---- Mouse output ------------------------------------------------------------

    private void moveCursor(float x, float y) {
        float left = canvas.getX(), top = canvas.getY();
        cursorX = Math.max(left, Math.min(left + canvas.getWidth() - 1, x));
        cursorY = Math.max(top, Math.min(top + canvas.getHeight() - 1, y));
        pointer.setX(cursorX);
        pointer.setY(cursorY);
        sendScaledMousePosition(cursorX, cursorY);
    }

    private void mouse(int button, boolean down) {
        AWTInputBridge.sendMousePress(button, down);
    }

    private void click(int button) {
        sendScaledMousePosition(cursorX, cursorY);
        mouse(button, true);
        handler.postDelayed(() -> mouse(button, false), CLICK_HOLD_MS);
    }

    // ---- Controls ----------------------------------------------------------------

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(4), dp(4), dp(4), dp(4));

        keyboardButton = button("⌨ Keyboard", v -> toggleKeyboard());
        modeButton = button("", v -> { trackpad = !trackpad; prefs.edit().putBoolean("trackpad", trackpad).apply(); applyMode(); });
        rightTapButton = button("", v -> setRightTap(!rightTapArmed));
        setRightTap(false);
        bar.addView(keyboardButton);
        bar.addView(modeButton);
        bar.addView(rightTapButton);
        bar.addView(button("Esc", v -> AWTInputBridge.sendKey(' ', AWTInputEvent.VK_ESCAPE)));
        bar.addView(button("☰", this::showMenu));
        return bar;
    }

    private LinearLayout buildArrowPad() {
        LinearLayout pad = new LinearLayout(this);
        pad.setOrientation(LinearLayout.VERTICAL);
        pad.setGravity(Gravity.CENTER_HORIZONTAL);
        pad.addView(keyButton("▲", AWTInputEvent.VK_UP), square(48));
        LinearLayout middle = new LinearLayout(this);
        middle.addView(keyButton("◀", AWTInputEvent.VK_LEFT), square(48));
        middle.addView(keyButton("▼", AWTInputEvent.VK_DOWN), square(48));
        middle.addView(keyButton("▶", AWTInputEvent.VK_RIGHT), square(48));
        pad.addView(middle);
        return pad;
    }

    private void showMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        boolean arrows = arrowPad.getVisibility() == View.VISIBLE;
        menu.getMenu().add(0, 1, 0, arrows ? "Hide camera arrows" : "Show camera arrows");
        menu.getMenu().add(0, 2, 1, "Enter");
        menu.getMenu().add(0, 3, 2, "Copy");
        menu.getMenu().add(0, 4, 3, "Paste");
        menu.getMenu().add(0, 5, 4, "View log");
        menu.getMenu().add(0, 6, 5, "Force close");
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    arrowPad.setVisibility(arrows ? View.GONE : View.VISIBLE);
                    prefs.edit().putBoolean("arrows", !arrows).apply();
                    return true;
                case 2: AWTInputBridge.sendKey(' ', AWTInputEvent.VK_ENTER); return true;
                case 3: performCopy(null); return true;
                case 4: performPaste(null); return true;
                case 5: openLogOutput(null); return true;
                case 6: forceClose(null); return true;
            }
            return false;
        });
        menu.show();
    }

    private void applyMode() {
        modeButton.setText(trackpad ? "Mode: Trackpad" : "Mode: Touch");
        pointer.setVisibility(trackpad ? View.VISIBLE : View.GONE);
        mouseButtons.setVisibility(trackpad ? View.VISIBLE : View.GONE);
        moveCursor(cursorX, cursorY);
    }

    private void setRightTap(boolean armed) {
        rightTapArmed = armed;
        rightTapButton.setText(armed ? "Right-tap: ON" : "Right-tap");
        rightTapButton.setAlpha(armed ? 1f : 0.8f);
    }

    private void toggleKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboardOpen) {
            keyboardInput.disable();
            imm.hideSoftInputFromWindow(keyboardInput.getWindowToken(), 0);
            keyboardOpen = false;
        } else {
            keyboardInput.enable();
            keyboardInput.post(() -> imm.showSoftInput(keyboardInput, InputMethodManager.SHOW_FORCED));
            keyboardOpen = true;
        }
        keyboardButton.setText(keyboardOpen ? "⌨ Hide" : "⌨ Keyboard");
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // TouchCharInput disables itself when focus moves away; keep the label in step.
        if (!hasFocus && keyboardOpen && keyboardButton != null) {
            keyboardOpen = false;
            keyboardButton.setText("⌨ Keyboard");
        }
    }

    @Override
    protected void onPause() {
        // Never leave a button or key stuck down when the app goes to the background.
        handler.removeCallbacksAndMessages(null);
        if (leftHeld) mouse(AWTInputEvent.BUTTON1_DOWN_MASK, false);
        leftHeld = false;
        releaseCameraKeys();
        super.onPause();
    }

    private Button button(String text, View.OnClickListener listener) {
        Button b = styled(text);
        b.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        params.setMargins(dp(2), 0, dp(2), 0);
        b.setLayoutParams(params);
        return b;
    }

    @SuppressLint("ClickableViewAccessibility")
    private Button holdButton(String text, Runnable down, Runnable up) {
        Button b = styled(text);
        b.setTextSize(20);
        b.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: v.setPressed(true); down.run(); return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: v.setPressed(false); up.run(); return true;
            }
            return true;
        });
        return b;
    }

    private Button keyButton(String text, int keyCode) {
        return holdButton(text, () -> AWTInputBridge.sendKey(' ', keyCode, 1),
                () -> AWTInputBridge.sendKey(' ', keyCode, 0));
    }

    private Button styled(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTextColor(Color.WHITE);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(10), 0, dp(10), 0);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0x99000000);
        background.setStroke(dp(1), 0xCCFFFFFF);
        background.setCornerRadius(dp(8));
        b.setBackground(background);
        b.setAlpha(0.8f);
        return b;
    }

    private LinearLayout.LayoutParams square(int sizeDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp));
        params.setMargins(dp(3), dp(3), dp(3), dp(3));
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
