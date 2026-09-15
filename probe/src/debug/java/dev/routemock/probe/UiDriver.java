package dev.routemock.probe;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
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
            AccessibilityNodeInfo target = findExact(root, label);
            if (target == null) {
                root.recycle();
                finishWithError(result, "Locator not found: " + label);
                return;
            }

            boolean acted;
            if (OPERATION_CLICK.equals(operation)) {
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
