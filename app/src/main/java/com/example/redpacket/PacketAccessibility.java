package com.example.redpacket;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;
import java.util.List;

public class PacketAccessibility extends AccessibilityService {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastClick = 0;
    private boolean busy = false;
    private String lastFingerprint = "";
    private long lastFingerprintAt = 0;

    @Override public void onServiceConnected() {
        super.onServiceConnected();
        status("无障碍服务已连接");
    }

    private void status(String message) {
        getSharedPreferences("MainActivity", 0).edit()
            .putString("lastStatus", message)
            .putLong("lastStatusAt", System.currentTimeMillis()).apply();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null ||
                !"com.tencent.mm".contentEquals(event.getPackageName())) return;
        if (!getSharedPreferences("MainActivity", 0).getBoolean("enabled", false)) {
            status("已接收微信事件，但自动识别开关关闭");
            return;
        }
        if (busy || System.currentTimeMillis() - lastClick < 650) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) { status("收到微信事件，但无法读取窗口"); return; }

        List<AccessibilityNodeInfo> candidates = new ArrayList<>();
        collect(root, candidates, 0);
        if (candidates.isEmpty()) {
            status("收到微信事件，未识别到红包控件");
            return;
        }
        // 优先处理屏幕上位置靠下的红包，避免重复点击旧消息
        AccessibilityNodeInfo chosen = null;
        int bottom = -1;
        for (AccessibilityNodeInfo n : candidates) {
            Rect r = new Rect(); n.getBoundsInScreen(r);
            if (!r.isEmpty() && r.bottom > bottom) { chosen = n; bottom = r.bottom; }
        }
        if (chosen == null) return;
        Rect bounds = new Rect(); chosen.getBoundsInScreen(bounds);
        String fp = bounds.toShortString() + ":" + label(chosen);
        long now = System.currentTimeMillis();
        if (fp.equals(lastFingerprint) && now - lastFingerprintAt < 12000) return;
        lastFingerprint = fp;
        lastFingerprintAt = now;
        status("识别到红包控件，尝试点击");
        busy = true;
        final AccessibilityNodeInfo target = chosen;
        int delay = getSharedPreferences("MainActivity", 0).getInt("delay", 300);
        handler.postDelayed(() -> {
            try {
                if (!clickParent(target)) {
                    Rect rect = new Rect(); target.getBoundsInScreen(rect);
                    if (!rect.isEmpty()) tap(rect.centerX(), rect.centerY());
                }
                lastClick = System.currentTimeMillis();
            } catch (Exception e) {
                status("点击失败：" + e.getClass().getSimpleName());
            } finally { busy = false; }
        }, delay);
    }

    private String label(AccessibilityNodeInfo n) {
        String a = n.getText() == null ? "" : n.getText().toString();
        String b = n.getContentDescription() == null ? "" : n.getContentDescription().toString();
        return a + " " + b;
    }

    private void collect(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> out, int depth) {
        if (n == null || depth > 45) return;
        String s = label(n);
        boolean isPacket = s.contains("微信红包") || s.contains("领取红包") ||
            s.contains("恭喜发财") || s.contains("红包来了");
        boolean excluded = s.contains("已领取") || s.contains("已过期") ||
            s.contains("红包记录") || s.contains("红包封面");
        if (isPacket && !excluded) out.add(n);
        // 弹窗里的“开”只在能看到红包上下文时才点击
        if (("开".equals(s.trim()) || "拆红包".equals(s.trim())) &&
            hasPacketContext(n)) out.add(n);
        for (int i = 0; i < n.getChildCount(); i++) collect(n.getChild(i), out, depth + 1);
    }

    private boolean hasPacketContext(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo p = n;
        for (int i = 0; i < 6 && p != null; i++, p = p.getParent()) {
            if (p.getContentDescription() != null &&
                p.getContentDescription().toString().contains("红包")) return true;
        }
        return false;
    }

    private boolean clickParent(AccessibilityNodeInfo n) {
        for (int i = 0; n != null && i < 9; i++, n = n.getParent()) {
            if (n.isClickable() && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        }
        return false;
    }

    private void tap(float x, float y) {
        Path path = new Path(); path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke =
            new GestureDescription.StrokeDescription(path, 0, 70);
        dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(), null, null);
    }

    @Override public void onInterrupt() { status("无障碍服务被中断"); }
}
