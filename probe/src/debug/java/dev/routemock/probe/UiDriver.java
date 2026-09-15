package dev.routemock.probe;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * Small debug-only locator driver for black-box checks of the independent probe APK.
 * It intentionally takes an immediate accessibility snapshot instead of waiting for idle because
 * the receiver is expected to update continuously.
 */
public final class UiDriver extends Instrumentation {
    private static final String OPERATION_DUMP = "dump";
    private static final String OPERATION_CLICK = "click";
    private static final String OPERATION_TEXT = "text";
    private static final int ROOT_RETRY_COUNT = 5;
    private static final long ROOT_RETRY_DELAY_MS = 200L;
    private Bundle instrumentationArguments;

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        instrumentationArguments = arguments;
        start();
    }

    @Override
    public void onStart() {
        super.onStart();
        Bundle result = new Bundle();
        try {
            Bundle arguments = instrumentationArguments;
            String operation = value(arguments, "operation", OPERATION_DUMP);
            if ("sleep".equals(operation) || "wake".equals(operation)) {
                UiAutomation automation = getUiAutomation();
                boolean acted;
                if ("sleep".equals(operation)) {
                    acted = automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN);
                } else {
                    long now = SystemClock.uptimeMillis();
                    boolean down = automation.injectInputEvent(new KeyEvent(now, now,
                            KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_WAKEUP, 0), true);
                    boolean up = automation.injectInputEvent(new KeyEvent(now, SystemClock.uptimeMillis(),
                            KeyEvent.ACTION_UP, KeyEvent.KEYCODE_WAKEUP, 0), true);
                    acted = down && up;
                }
                if (acted) {
                    result.putString("result", "success");
                    finishSuccess(result);
                } else {
                    finishWithError(result, "Global screen action failed: " + operation);
                }
                return;
            }
            AccessibilityNodeInfo root = rootWithShortRetry();
            if (root == null) {
                finishWithError(result, "Accessibility root was not ready");
                return;
            }

            if (OPERATION_DUMP.equals(operation)) {
                result.putString("result", "success");
                result.putString("hierarchy", toXml(root));
                finishSuccess(result);
                root.recycle();
                return;
            }

            String label = value(arguments, "label", "");
            if (label.isEmpty()) {
                root.recycle();
                finishWithError(result, "Missing exact locator label");
                return;
            }
            boolean mapGesture = "pan-map".equals(operation) || "pinch-map".equals(operation);
            AccessibilityNodeInfo target = findExact(root, label);
            if (target == null) {
                root.recycle();
                finishWithError(result, "Locator not found: " + label);
                return;
            }

            boolean acted;
            if (mapGesture) {
                acted = gestureMap(target, operation, value(arguments, "value", "in"));
            } else if (OPERATION_CLICK.equals(operation)) {
                acted = clickNodeOrClickableParent(target);
            } else if (OPERATION_TEXT.equals(operation)) {
                acted = setText(target, value(arguments, "value", ""));
            } else if ("progress".equals(operation)) {
                Bundle progress = new Bundle();
                progress.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,
                        Float.parseFloat(value(arguments, "value", "0")));
                acted = target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(), progress);
            } else if ("scroll-forward".equals(operation) || "scroll-backward".equals(operation)) {
                boolean scrolled = target.performAction("scroll-forward".equals(operation)
                        ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
                result.putBoolean("scrolled", scrolled);
                acted = true; // Reaching a scroll boundary is a successful no-op dispatch.
            } else {
                if (target != root) {
                    target.recycle();
                }
                root.recycle();
                finishWithError(result, "Unsupported operation: " + operation);
                return;
            }
            if (target != root) {
                target.recycle();
            }
            root.recycle();
            if (!acted) {
                finishWithError(result, "Action failed for locator: " + label);
            } else {
                result.putString("result", "success");
                result.putString("operation", operation);
                result.putString("label", label);
                finishSuccess(result);
            }
        } catch (RuntimeException error) {
            finishWithError(result, error.toString());
        }
    }

    private AccessibilityNodeInfo rootWithShortRetry() {
        UiAutomation automation = getUiAutomation();
        for (int attempt = 0; attempt < ROOT_RETRY_COUNT; attempt++) {
            AccessibilityNodeInfo root = automation.getRootInActiveWindow();
            if (root != null) {
                return root;
            }
            SystemClock.sleep(ROOT_RETRY_DELAY_MS);
        }
        return null;
    }

    private static String value(Bundle arguments, String key, String fallback) {
        if (arguments == null) {
            return fallback;
        }
        String value = arguments.getString(key);
        return value == null ? fallback : value;
    }

    private static AccessibilityNodeInfo findExact(AccessibilityNodeInfo node, String label) {
        if (label.equals(stringValue(node.getContentDescription()))
                || label.equals(stringValue(node.getText()))
                || label.equals(stringValue(node.getViewIdResourceName()))) {
            return node;
        }
        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo child = node.getChild(index);
            if (child == null) {
                continue;
            }
            AccessibilityNodeInfo match = findExact(child, label);
            if (match != null) {
                if (match != child) {
                    child.recycle();
                }
                return match;
            }
            child.recycle();
        }
        return null;
    }

    private static Rect mapBounds(AccessibilityNodeInfo node) {
        Rect bounds = new Rect();
        boolean labeledMap = "步行路線地圖".equals(stringValue(node.getContentDescription()))
                || "步行路線地圖".equals(stringValue(node.getText()));
        boolean nativeMap = "android.webkit.WebView".equals(stringValue(node.getClassName()));
        if (!labeledMap || (!nativeMap && !"map".equals(stringValue(node.getViewIdResourceName())))
                || !node.isVisibleToUser() || !node.isEnabled()) {
            return bounds;
        }
        node.getBoundsInScreen(bounds);
        AccessibilityNodeInfo current = node;
        try {
            while (current != null) {
                if ("android.webkit.WebView".equals(stringValue(current.getClassName()))) {
                    Rect webBounds = new Rect();
                    current.getBoundsInScreen(webBounds);
                    if (!current.isVisibleToUser() || !current.isEnabled() || !bounds.intersect(webBounds)) {
                        bounds.setEmpty();
                    }
                    return bounds;
                }
                AccessibilityNodeInfo parent = current.getParent();
                if (current != node) {
                    current.recycle();
                }
                current = parent;
            }
            bounds.setEmpty();
            return bounds;
        } finally {
            if (current != null && current != node) {
                current.recycle();
            }
        }
    }

    private boolean gestureMap(AccessibilityNodeInfo node, String operation, String direction) {
        boolean pinch = "pinch-map".equals(operation);
        if (pinch && !"in".equals(direction) && !"out".equals(direction)) {
            throw new IllegalArgumentException("pinch-map value must be in or out");
        }
        Rect bounds = mapBounds(node);
        if (bounds.isEmpty()) {
            return false;
        }
        UiAutomation automation = getUiAutomation();
        long downTime = SystemClock.uptimeMillis();
        float y = bounds.exactCenterY();
        float initialRadius = "out".equals(direction) ? .25f : .12f;
        float finalRadius = "out".equals(direction) ? .12f : .25f;
        float x = bounds.left + bounds.width() * (pinch ? .5f - initialRadius : .4f);
        float secondX = bounds.left + bounds.width() * (.5f + initialRadius);
        int pointerCount = 1;
        boolean completed = false;
        try {
            if (!injectTouch(automation, downTime, MotionEvent.ACTION_DOWN, 1, x, secondX, y)) {
                return false;
            }
            if (pinch) {
                pointerCount = 2;
                if (!injectTouch(automation, downTime, MotionEvent.ACTION_POINTER_DOWN
                        | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, x, secondX, y)) {
                    return false;
                }
            }
            for (int step = 1; step <= 20; step++) {
                SystemClock.sleep(20L);
                float fraction = step / 20f;
                float radius = initialRadius + (finalRadius - initialRadius) * fraction;
                x = bounds.left + bounds.width() * (pinch ? .5f - radius : .4f + .2f * fraction);
                secondX = bounds.left + bounds.width() * (.5f + radius);
                if (!injectTouch(automation, downTime, MotionEvent.ACTION_MOVE,
                        pointerCount, x, secondX, y)) {
                    return false;
                }
            }
            if (pinch) {
                if (!injectTouch(automation, downTime, MotionEvent.ACTION_POINTER_UP
                        | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, x, secondX, y)) {
                    return false;
                }
                pointerCount = 1;
            }
            completed = injectTouch(automation, downTime, MotionEvent.ACTION_UP, 1, x, secondX, y);
            return completed;
        } finally {
            if (!completed) {
                injectTouch(automation, downTime, MotionEvent.ACTION_CANCEL, pointerCount, x, secondX, y);
            }
        }
    }

    private static boolean injectTouch(UiAutomation automation, long downTime, int action,
            int pointerCount, float x, float secondX, float y) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[pointerCount];
        MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[pointerCount];
        for (int index = 0; index < pointerCount; index++) {
            properties[index] = new MotionEvent.PointerProperties();
            properties[index].id = index;
            properties[index].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coordinates[index] = new MotionEvent.PointerCoords();
            coordinates[index].x = index == 0 ? x : secondX;
            coordinates[index].y = y;
            coordinates[index].pressure = 1f;
            coordinates[index].size = 1f;
        }
        MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                pointerCount, properties, coordinates, 0, 0, 1f, 1f, 0, 0,
                InputDevice.SOURCE_TOUCHSCREEN, 0);
        try {
            return automation.injectInputEvent(event, true);
        } finally {
            event.recycle();
        }
    }

    private static boolean clickNodeOrClickableParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        while (current != null) {
            if (current.isClickable()) {
                boolean clicked = current.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                if (current != node) {
                    current.recycle();
                }
                return clicked;
            }
            AccessibilityNodeInfo parent = current.getParent();
            if (current != node) {
                current.recycle();
            }
            current = parent;
        }
        return false;
    }

    private static boolean setText(AccessibilityNodeInfo node, String value) {
        Bundle arguments = new Bundle();
        arguments.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments);
    }

    private static String stringValue(CharSequence value) {
        return value == null ? "" : value.toString();
    }

    private static String toXml(AccessibilityNodeInfo node) {
        StringBuilder xml = new StringBuilder();
        appendXml(node, xml);
        return xml.toString();
    }

    private static void appendXml(AccessibilityNodeInfo node, StringBuilder xml) {
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        xml.append("<node")
                .append(" class=\"").append(escape(node.getClassName())).append('\"')
                .append(" text=\"").append(escape(node.getText())).append('\"')
                .append(" content-desc=\"").append(escape(node.getContentDescription())).append('\"')
                .append(" resource-id=\"").append(escape(node.getViewIdResourceName())).append('\"')
                .append(" clickable=\"").append(node.isClickable()).append('\"')
                .append(" checked=\"").append(node.isChecked()).append('\"')
                .append(" scrollable=\"").append(node.isScrollable()).append('\"')
                .append(" enabled=\"").append(node.isEnabled()).append('\"')
                .append(" bounds=\"").append(boundsToString(bounds)).append("\">");
        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo child = node.getChild(index);
            if (child == null) {
                continue;
            }
            appendXml(child, xml);
            child.recycle();
        }
        xml.append("</node>");
    }

    private static String boundsToString(Rect bounds) {
        return "[" + bounds.left + "," + bounds.top + "]["
                + bounds.right + "," + bounds.bottom + "]";
    }

    private static String escape(CharSequence value) {
        String text = stringValue(value);
        return text.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private void finishSuccess(Bundle result) {
        sendStatus(0, result);
        finish(Activity.RESULT_OK, result);
    }

    private void finishWithError(Bundle result, String message) {
        result.putString("result", "error");
        result.putString("error", message);
        sendStatus(0, result);
        finish(Activity.RESULT_CANCELED, result);
    }
}
