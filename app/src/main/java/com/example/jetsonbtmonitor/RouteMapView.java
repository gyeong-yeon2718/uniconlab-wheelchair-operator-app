package com.example.jetsonbtmonitor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import org.json.JSONArray;

/**
 * Top-down route view: the planned waypoints, how far along the chair is, and
 * where it is pointing.
 *
 * The route and {@code /fast_lio_icp/pose} share the {@code map} frame -- the
 * route was captured from that very topic -- so both are drawn on the same axes
 * with no transform. ROS is x-forward / y-left, screen is x-right / y-down, so
 * the mapping flips y.
 *
 * Everything is fail-visible: with no route it says so rather than drawing an
 * empty box, and a stale pose greys the chair marker instead of leaving it
 * parked somewhere convincing but wrong.
 */
public class RouteMapView extends View {

    private static final float PAD_PX = 24f;

    /**
     * Height kept clear along the bottom edge for the speed readout that sits
     * over the map. Reserved before fitting rather than drawn over afterwards --
     * the whole point of putting the speed here was to save space, and a readout
     * that hides the corner the chair is currently in saves nothing.
     */
    private float bottomInsetPx;

    private float[] routeX = new float[0];
    private float[] routeY = new float[0];
    private int progressIndex = -1;
    /** Points are sent thinned; wp_index counts the FULL route, so divide. */
    private int stride = 1;
    private int countFull;

    private boolean hasPose;
    private float poseX, poseY, poseYawDeg;
    private boolean poseStale = true;

    private String emptyMessage = "경로 없음";

    private final Paint routePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint donePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chairPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    public RouteMapView(Context context, AttributeSet attrs) {
        super(context, attrs);
        routePaint.setStyle(Paint.Style.STROKE);
        routePaint.setStrokeWidth(6f);
        routePaint.setColor(Color.parseColor("#C7D6E5"));
        routePaint.setStrokeCap(Paint.Cap.ROUND);
        routePaint.setStrokeJoin(Paint.Join.ROUND);

        donePaint.setStyle(Paint.Style.STROKE);
        donePaint.setStrokeWidth(6f);
        donePaint.setColor(Color.parseColor("#0175C2"));
        donePaint.setStrokeCap(Paint.Cap.ROUND);
        donePaint.setStrokeJoin(Paint.Join.ROUND);

        dotPaint.setColor(Color.parseColor("#98A2B3"));
        chairPaint.setColor(Color.parseColor("#12805C"));
        textPaint.setColor(Color.parseColor("#98A2B3"));
        textPaint.setTextSize(30f);
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    /** Route arrives once per connection as a {@code type:"route"} frame. */
    public void setRoute(JSONArray points, int stride, int countFull) {
        this.stride = Math.max(1, stride);
        this.countFull = countFull;
        setRoute(points);
    }

    public void setRoute(JSONArray points) {
        int n = points == null ? 0 : points.length();
        float[] xs = new float[n];
        float[] ys = new float[n];
        for (int i = 0; i < n; i++) {
            JSONArray p = points.optJSONArray(i);
            if (p == null || p.length() < 2) {
                continue;
            }
            xs[i] = (float) p.optDouble(0, 0);
            ys[i] = (float) p.optDouble(1, 0);
        }
        routeX = xs;
        routeY = ys;
        emptyMessage = n == 0 ? "경로 비어 있음" : null;
        invalidate();
    }

    public void setPose(Float x, Float y, Float yawDeg, boolean stale) {
        hasPose = x != null && y != null;
        if (hasPose) {
            poseX = x;
            poseY = y;
            poseYawDeg = yawDeg == null ? 0f : yawDeg;
        }
        poseStale = stale;
        invalidate();
    }

    /** @param fullIndex waypoint index against the unthinned route. */
    public void setProgress(int fullIndex) {
        progressIndex = fullIndex < 0 ? -1 : fullIndex / stride;
        invalidate();
    }

    /** Whether a drawable route has actually arrived. */
    public boolean hasRoute() {
        return routeX.length >= 2;
    }

    public void setBottomInset(float px) {
        bottomInsetPx = Math.max(0f, px);
        invalidate();
    }

    public int getStride() {
        return stride;
    }

    public int getCountFull() {
        return countFull;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (routeX.length < 2) {
            canvas.drawText(emptyMessage == null ? "경로 없음" : emptyMessage,
                    getWidth() / 2f, getHeight() / 2f, textPaint);
            return;
        }

        // Fit route and pose together so the chair never leaves the frame.
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int i = 0; i < routeX.length; i++) {
            minX = Math.min(minX, routeX[i]);
            maxX = Math.max(maxX, routeX[i]);
            minY = Math.min(minY, routeY[i]);
            maxY = Math.max(maxY, routeY[i]);
        }
        if (hasPose) {
            minX = Math.min(minX, poseX);
            maxX = Math.max(maxX, poseX);
            minY = Math.min(minY, poseY);
            maxY = Math.max(maxY, poseY);
        }

        float spanX = Math.max(maxX - minX, 0.5f);
        float spanY = Math.max(maxY - minY, 0.5f);
        float usableW = getWidth() - 2 * PAD_PX;
        float usableH = getHeight() - 2 * PAD_PX - bottomInsetPx;
        // One scale for both axes: an independently stretched map would misreport
        // how close the chair is to the route.
        float scale = Math.min(usableW / spanX, usableH / spanY);
        float offX = PAD_PX + (usableW - spanX * scale) / 2f;
        float offY = PAD_PX + (usableH - spanY * scale) / 2f;

        // ROS x-forward/y-left -> screen x-right/y-down.
        // sx/sy are computed inline below from these.
        int done = Math.max(0, Math.min(progressIndex, routeX.length - 1));

        path.reset();
        for (int i = 0; i <= done; i++) {
            float sx = offX + (routeX[i] - minX) * scale;
            float sy = offY + (maxY - routeY[i]) * scale;
            if (i == 0) {
                path.moveTo(sx, sy);
            } else {
                path.lineTo(sx, sy);
            }
        }
        if (progressIndex >= 0) {
            canvas.drawPath(path, donePaint);
        }

        path.reset();
        for (int i = Math.max(done, 0); i < routeX.length; i++) {
            float sx = offX + (routeX[i] - minX) * scale;
            float sy = offY + (maxY - routeY[i]) * scale;
            if (i == Math.max(done, 0)) {
                path.moveTo(sx, sy);
            } else {
                path.lineTo(sx, sy);
            }
        }
        canvas.drawPath(path, routePaint);

        // Endpoints, so the direction of travel is readable at a glance.
        canvas.drawCircle(offX + (routeX[0] - minX) * scale,
                offY + (maxY - routeY[0]) * scale, 7f, dotPaint);
        int last = routeX.length - 1;
        canvas.drawCircle(offX + (routeX[last] - minX) * scale,
                offY + (maxY - routeY[last]) * scale, 7f, dotPaint);

        if (!hasPose) {
            return;
        }
        float cx = offX + (poseX - minX) * scale;
        float cy = offY + (maxY - poseY) * scale;
        chairPaint.setColor(poseStale ? Color.parseColor("#98A2B3")
                                      : Color.parseColor("#12805C"));
        canvas.drawCircle(cx, cy, 13f, chairPaint);

        // Heading tick. Screen y is inverted, hence the minus on sin.
        double rad = Math.toRadians(poseYawDeg);
        chairPaint.setStrokeWidth(5f);
        canvas.drawLine(cx, cy,
                cx + (float) (Math.cos(rad) * 26.0),
                cy - (float) (Math.sin(rad) * 26.0), chairPaint);
    }
}
