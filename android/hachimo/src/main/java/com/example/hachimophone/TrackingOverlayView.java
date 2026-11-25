package com.example.hachimophone;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import java.util.Collections;
import java.util.List;

/**
 * 相机预览上的跟踪框：
 *   当前跟踪主体（人脸或人体）画蓝色框，其他检测到的人脸画白色框。
 * 坐标是摆正后画面的 0~1，按 PreviewView 的 FILL_CENTER 规则映射到这个小预览窗。
 */
final class TrackingOverlayView extends View {

    private final Paint primaryPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint otherPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private List<VisionTracker.Box> boxes = Collections.emptyList();
    private float frameAspect = 4f / 3f;
    private boolean flipped;

    TrackingOverlayView(Context context) {
        super(context);
        primaryPaint.setStyle(Paint.Style.STROKE);
        primaryPaint.setStrokeWidth(dp(2f));
        primaryPaint.setColor(0xFF2196F3);

        otherPaint.setStyle(Paint.Style.STROKE);
        otherPaint.setStrokeWidth(dp(1.5f));
        otherPaint.setColor(0xFFFFFFFF);
    }

    void setDetections(List<VisionTracker.Box> list, int frameW, int frameH, boolean flipped) {
        boxes = list == null ? Collections.emptyList() : list;
        if (frameW > 0 && frameH > 0) {
            frameAspect = frameW / (float) frameH;
        }
        this.flipped = flipped;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (boxes.isEmpty()) return;
        float viewW = getWidth();
        float viewH = getHeight();
        if (viewW <= 0 || viewH <= 0) return;

        float viewAspect = viewW / viewH;
        float contentW;
        float contentH;
        float offsetX;
        float offsetY;
        if (frameAspect > viewAspect) {
            // 画面更宽：按高度填满，左右裁切
            contentH = viewH;
            contentW = viewH * frameAspect;
            offsetX = (viewW - contentW) / 2f;
            offsetY = 0;
        } else {
            // 画面更高：按宽度填满，上下裁切
            contentW = viewW;
            contentH = viewW / frameAspect;
            offsetX = 0;
            offsetY = (viewH - contentH) / 2f;
        }

        for (VisionTracker.Box b : boxes) {
            float l = b.l;
            float t = b.t;
            float r = b.r;
            float bt = b.b;
            if (flipped) {
                // 整页转了 180°，检测坐标里也含这 180°，画之前先抵消
                float nl = 1f - r;
                float nr = 1f - l;
                float nt = 1f - bt;
                float nb = 1f - t;
                l = nl;
                r = nr;
                t = nt;
                bt = nb;
            }
            rect.set(
                    offsetX + l * contentW,
                    offsetY + t * contentH,
                    offsetX + r * contentW,
                    offsetY + bt * contentH);
            canvas.drawRect(rect, b.primary ? primaryPaint : otherPaint);
        }
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
