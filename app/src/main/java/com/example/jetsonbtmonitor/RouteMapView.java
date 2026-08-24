package com.example.jetsonbtmonitor;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
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
     * Campus backdrop, rendered from the very point cloud the localizer matches
     * against (tools/render_map_backdrop.py). Because it comes from that cloud
     * it is already in the map frame -- these three numbers place it, and no
     * control points or eyeballed fit are involved. Keep them in step with
     * campus_backdrop.json if the image is ever re-rendered.
     */
    private static final float BACKDROP_ORIGIN_X = -47.827f;
    private static final float BACKDROP_ORIGIN_Y = -129.21f;
    private static final float BACKDROP_RES_M = 0.25f;

    private Bitmap backdrop;
    private final RectF backdropRect = new RectF();

    /**
     * Height kept clear along the bottom edge for the speed readout that sits
     * over the map. Reserved before fitting rather than drawn over afterwards --
     * the whole point of putting the speed here was to save space, and a readout
     * that hides the corner the chair is currently in saves nothing.
     */
    private float bottomInsetPx;

    private float[] routeX = new float[0];
    private float[] routeY = new float[0];
    /** Drivable corridor edges, same map frame as the route. */
    private float[] bandLX = new float[0];
    private float[] bandLY = new float[0];
    private float[] bandRX = new float[0];
    private float[] bandRY = new float[0];
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
    private final Path band = new Path();
    private final Path arrow = new Path();
    private final Paint bandFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bandEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint haloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint northPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

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
        // The corridor the chair is allowed to use. Filled rather than
        // outlined because the useful reading is how much room is left here,
        // and on a phone-sized view a filled ribbon answers that at a glance.
        bandFill.setStyle(Paint.Style.FILL);
        bandFill.setColor(Color.parseColor("#B7E2F5"));
        bandEdge.setStyle(Paint.Style.STROKE);
        bandEdge.setStrokeWidth(2f);
        bandEdge.setColor(Color.parseColor("#5FB6DA"));

        // A white outline under the chair marker and its arrow, so neither
        // disappears where it sits on top of the route.
        haloPaint.setStyle(Paint.Style.STROKE);
        haloPaint.setStrokeWidth(5f);
        haloPaint.setColor(Color.WHITE);
        haloPaint.setStrokeJoin(Paint.Join.ROUND);

        northPaint.setColor(Color.parseColor("#98A2B3"));
        northPaint.setTextSize(24f);
        northPaint.setTextAlign(Paint.Align.CENTER);
        northPaint.setStrokeWidth(3f);

        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inScaled = false;       // nodpi: the pixels are metres, not dp
            backdrop = BitmapFactory.decodeResource(
                    getResources(), R.drawable.campus_backdrop, options);
        } catch (OutOfMemoryError ignored) {
            backdrop = null;                // the route alone still draws
        }

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

    /** Corridor edges arrive in the same route frame as the centreline. */
    public void setBand(JSONArray left, JSONArray right) {
        int n = Math.min(left == null ? 0 : left.length(),
                         right == null ? 0 : right.length());
        float[] lx = new float[n], ly = new float[n];
        float[] rx = new float[n], ry = new float[n];
        for (int i = 0; i < n; i++) {
            JSONArray l = left.optJSONArray(i);
            JSONArray r = right.optJSONArray(i);
            if (l == null || r == null || l.length() < 2 || r.length() < 2) {
                continue;
            }
            lx[i] = (float) l.optDouble(0, 0);
            ly[i] = (float) l.optDouble(1, 0);
            rx[i] = (float) r.optDouble(0, 0);
            ry[i] = (float) r.optDouble(1, 0);
        }
        bandLX = lx;
        bandLY = ly;
        bandRX = rx;
        bandRY = ry;
        invalidate();
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

    /**
     * Which way is north. The route is drawn in the map frame, whose x axis is
     * where the chair was pointing when the map was built -- so "up" on this
     * view is not north and there was nothing on screen saying so.
     */
    private void drawNorth(Canvas canvas) {
        float x = getWidth() - 30f;
        float y = 34f;
        northPaint.setStyle(Paint.Style.STROKE);
        canvas.drawLine(x, y + 16f, x, y - 10f, northPaint);
        canvas.drawLine(x, y - 10f, x - 6f, y - 2f, northPaint);
        canvas.drawLine(x, y - 10f, x + 6f, y - 2f, northPaint);
        northPaint.setStyle(Paint.Style.FILL);
        canvas.drawText("N", x, y + 36f, northPaint);
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
        for (int i = 0; i < bandLX.length; i++) {
            minX = Math.min(minX, Math.min(bandLX[i], bandRX[i]));
            maxX = Math.max(maxX, Math.max(bandLX[i], bandRX[i]));
            minY = Math.min(minY, Math.min(bandLY[i], bandRY[i]));
            maxY = Math.max(maxY, Math.max(bandLY[i], bandRY[i]));
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

        // Campus first of all: buildings, tree lines and road edges, so someone
        // who has never been to this campus can tell what they are looking at.
        if (backdrop != null) {
            float left = offX + (BACKDROP_ORIGIN_X - minX) * scale;
            float bottom = offY + (maxY - BACKDROP_ORIGIN_Y) * scale;
            float widthM = backdrop.getWidth() * BACKDROP_RES_M;
            float heightM = backdrop.getHeight() * BACKDROP_RES_M;
            backdropRect.set(left,
                             bottom - heightM * scale,
                             left + widthM * scale,
                             bottom);
            canvas.drawBitmap(backdrop, null, backdropRect, null);
        }

        // Corridor first, so everything else sits on top of it.
        if (bandLX.length >= 2) {
            band.reset();
            for (int i = 0; i < bandLX.length; i++) {
                float sx = offX + (bandLX[i] - minX) * scale;
                float sy = offY + (maxY - bandLY[i]) * scale;
                if (i == 0) {
                    band.moveTo(sx, sy);
                } else {
                    band.lineTo(sx, sy);
                }
            }
            for (int i = bandRX.length - 1; i >= 0; i--) {
                band.lineTo(offX + (bandRX[i] - minX) * scale,
                            offY + (maxY - bandRY[i]) * scale);
            }
            band.close();
            canvas.drawPath(band, bandFill);
            canvas.drawPath(band, bandEdge);
        }

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
        chairPaint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx, cy, 17f, haloPaint);
        canvas.drawCircle(cx, cy, 17f, chairPaint);

        // Heading. A line was too easy to lose against the route at a glance,
        // and which end of it was the front took a moment to work out. A filled
        // arrowhead reads direction immediately, and the halo keeps it visible
        // where it crosses the drawn line.
        // Smaller than before and sitting just clear of the dot, but with a
        // sharper point: what makes a heading readable is the angle at the tip,
        // not the length of the thing.
        double rad = Math.toRadians(poseYawDeg);
        float baseX = cx + (float) (Math.cos(rad) * 15.0);
        float baseY = cy - (float) (Math.sin(rad) * 15.0);
        float tipX = cx + (float) (Math.cos(rad) * 36.0);
        float tipY = cy - (float) (Math.sin(rad) * 36.0);
        double left = rad + Math.PI / 2;
        double right = rad - Math.PI / 2;
        float halfWidth = 10f;
        arrow.reset();
        arrow.moveTo(tipX, tipY);
        arrow.lineTo(baseX + (float) (Math.cos(left) * halfWidth),
                     baseY - (float) (Math.sin(left) * halfWidth));
        arrow.lineTo(baseX + (float) (Math.cos(right) * halfWidth),
                     baseY - (float) (Math.sin(right) * halfWidth));
        arrow.close();
        canvas.drawPath(arrow, haloPaint);
        canvas.drawPath(arrow, chairPaint);

        drawNorth(canvas);
    }
}
