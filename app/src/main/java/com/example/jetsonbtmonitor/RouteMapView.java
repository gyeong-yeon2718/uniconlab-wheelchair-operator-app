package com.example.jetsonbtmonitor;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
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
    /**
     * Where true north is, as a heading in the map frame.
     *
     * There is no georeference anywhere in this stack to derive it from: the
     * map frame is built from the lidar cloud, its x axis is simply wherever
     * the chair was pointing when the map was made, and neither
     * campus_backdrop.json nor the route files carry a datum, a UTM zone or a
     * latitude. So this is measured, once, by standing the chair somewhere and
     * saying which way it faces.
     *
     * Taken 2026-08-26: the chair read yaw +11.3 deg (quaternion z=0.09827,
     * w=0.99445 off /fast_lio_icp/pose) while facing south, so north is
     * 11.3 + 180. Only a half turn is involved, so this does not depend on
     * which way bearings are counted -- but it is an eyeballed "that is south",
     * so treat it as good to something like +/-15 deg, not to the decimal.
     *
     * Re-measure the same way if the map is ever rebuilt: the x axis moves
     * with it and this number goes stale silently.
     */
    private static final float MAP_YAW_OF_NORTH_DEG = 191.3f;

    private static final float BACKDROP_ORIGIN_X = -47.827f;
    private static final float BACKDROP_ORIGIN_Y = -129.21f;
    private static final float BACKDROP_RES_M = 0.25f;

    /**
     * Landmarks, in the map frame. A point cloud shows shapes but not names, and
     * the dashboard is read by people who may never have been on this campus --
     * "the chair is here" only means something next to a building they can name.
     * Positions came from an operator marking them on the rendered map, so they
     * are placed by someone who knows the place rather than guessed from the
     * geometry.
     */
    private static final class Landmark {
        final float x;
        final float y;
        final String name;

        Landmark(float x, float y, String name) {
            this.x = x;
            this.y = y;
            this.name = name;
        }
    }

    private static final Landmark[] LANDMARKS = {
            new Landmark(-16.0f, -23.5f, "한양대 2번 출구 (애지문)"),
            new Landmark(69.4f, -17.7f, "국제관"),
            new Landmark(61.5f, 29.9f, "역사관"),
            new Landmark(120.8f, -13.0f, "박물관"),
            new Landmark(196.2f, -46.6f, "신소재공학관"),
            new Landmark(246.8f, 36.9f, "공업센터본관")
    };

    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelHalo = new Paint(Paint.ANTI_ALIAS_FLAG);

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

    /**
     * User pan/zoom/rotate, applied on top of the fit.
     *
     * The fit stays exactly as it was -- it is what makes the whole route
     * visible on the dashboard card, and it recomputes as the chair moves. This
     * is a second transform on top, so zooming never fights the fit and letting
     * go of the controls (resetView) always returns to a known frame.
     */
    private boolean interactive;
    private float userScale = 1f;
    private float userTx, userTy;
    private float userRotDeg;
    private final Matrix userMatrix = new Matrix();
    private final float[] scratch = new float[2];
    private OnViewChanged viewListener;

    /** Fingers, tracked directly: a ScaleGestureDetector and a rotation of our
     *  own fight over the same two pointers and produce jitter at the moment
     *  both are moving, which is most of the time. */
    private int pointerA = -1, pointerB = -1;
    private float lastAx, lastAy, lastBx, lastBy;

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

        // Near-black rather than the grey-blue it was: the backdrop runs from
        // near-white ground to dark building faces, and a mid-tone label lost
        // contrast against both ends of that range.
        labelPaint.setColor(Color.parseColor("#10243A"));
        labelPaint.setTextSize(34f);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setFakeBoldText(true);
        // A heavier white casing, because the same label crosses light ground
        // and dark buildings within its own width.
        labelHalo.setColor(Color.WHITE);
        labelHalo.setTextSize(34f);
        labelHalo.setTextAlign(Paint.Align.CENTER);
        labelHalo.setFakeBoldText(true);
        labelHalo.setStyle(Paint.Style.STROKE);
        labelHalo.setStrokeWidth(9f);

        northPaint.setColor(Color.parseColor("#D92D20"));
        northPaint.setTextSize(30f);
        northPaint.setTextAlign(Paint.Align.CENTER);
        northPaint.setStrokeWidth(3f);
        northPaint.setFakeBoldText(true);

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

    /**
     * Metres still to drive, measured along the route from a waypoint index.
     *
     * The route arrives thinned (every {@code stride}-th point), so this walks
     * the drawn polyline and slightly under-measures the curves. On a route
     * whose points are ~0.1 m apart that is well inside the error of dividing
     * by a speed that is itself changing, and the alternative is asking the
     * bridge to ship 1917 points the map does not need.
     *
     * @return metres, or -1 when there is no route or no position on it.
     */
    public float remainingMetres(int fullIndex) {
        if (routeX.length < 2 || fullIndex < 0) {
            return -1f;
        }
        int from = Math.max(0, Math.min(routeX.length - 1, fullIndex / stride));
        double total = 0.0;
        for (int i = from; i < routeX.length - 1; i++) {
            total += Math.hypot(routeX[i + 1] - routeX[i],
                    routeY[i + 1] - routeY[i]);
        }
        return (float) total;
    }

    public int getCountFull() {
        return countFull;
    }

    /** Told when the user moves the view, so a reset button can grey itself. */
    public interface OnViewChanged {
        void onViewChanged(float scale, float rotationDeg, boolean moved);
    }

    public void setOnViewChanged(OnViewChanged listener) {
        this.viewListener = listener;
    }

    /** Only the full-screen copy takes gestures; the card is a picture. */
    public void setInteractive(boolean on) {
        this.interactive = on;
        setClickable(on);
    }

    public void resetView() {
        userScale = 1f;
        userTx = 0f;
        userTy = 0f;
        userRotDeg = 0f;
        notifyView();
        invalidate();
    }

    public boolean isMoved() {
        return userScale != 1f || userTx != 0f || userTy != 0f || userRotDeg != 0f;
    }

    /** Zoom about the middle of the view, for the +/- buttons. */
    public void zoomBy(float factor) {
        applyZoom(factor, getWidth() / 2f, (getHeight() - bottomInsetPx) / 2f);
        invalidate();
    }

    public void rotateBy(float degrees) {
        userRotDeg = (userRotDeg + degrees) % 360f;
        notifyView();
        invalidate();
    }

    private void applyZoom(float factor, float px, float py) {
        // Clamped: below 1 the fit already shows everything and the map just
        // shrinks into a corner, and past 12x the backdrop is single pixels.
        float next = Math.max(1f, Math.min(12f, userScale * factor));
        float actual = next / userScale;
        if (actual == 1f) {
            return;
        }
        // Keep the point under the fingers (or the middle) where it is.
        //
        // buildUserMatrix composes rotate-about-centre, then scale-about-centre,
        // then a screen-space translate, so a screen point q maps as
        // q = S(R(p)) + t. Solving for the t that leaves the focus f fixed
        // under a scale of k gives t' = k*t + (f - centre)*(1 - k). Measuring
        // the focus from the origin instead of from the centre -- the obvious
        // form, and what this did first -- is out by centre*(1-k), which at
        // 3.4x threw the whole map off the screen.
        float cx = getWidth() / 2f;
        float cy = (getHeight() - bottomInsetPx) / 2f;
        userTx = actual * userTx + (px - cx) * (1f - actual);
        userTy = actual * userTy + (py - cy) * (1f - actual);
        userScale = next;
        notifyView();
    }

    private void notifyView() {
        if (viewListener != null) {
            viewListener.onViewChanged(userScale, userRotDeg, isMoved());
        }
    }

    private void buildUserMatrix() {
        userMatrix.reset();
        if (!interactive || !isMoved()) {
            return;
        }
        float cx = getWidth() / 2f;
        float cy = (getHeight() - bottomInsetPx) / 2f;
        userMatrix.postRotate(userRotDeg, cx, cy);
        userMatrix.postScale(userScale, userScale, cx, cy);
        userMatrix.postTranslate(userTx, userTy);
    }

    /** Base screen coords for a map-frame point, then the user transform. */
    private void toScreen(float mx, float my, float minX, float maxY,
                          float offX, float offY, float scale) {
        scratch[0] = offX + (mx - minX) * scale;
        scratch[1] = offY + (maxY - my) * scale;
        userMatrix.mapPoints(scratch);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!interactive) {
            return super.onTouchEvent(event);
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                grabPointers(event);
                return true;
            case MotionEvent.ACTION_MOVE:
                handleMove(event);
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    /** Re-latch onto whichever fingers are down after any up/down. */
    private void grabPointers(MotionEvent event) {
        int leaving = (event.getActionMasked() == MotionEvent.ACTION_POINTER_UP
                || event.getActionMasked() == MotionEvent.ACTION_UP)
                ? event.getActionIndex() : -1;
        pointerA = pointerB = -1;
        for (int i = 0; i < event.getPointerCount(); i++) {
            if (i == leaving) {
                continue;
            }
            if (pointerA < 0) {
                pointerA = event.getPointerId(i);
                lastAx = event.getX(i);
                lastAy = event.getY(i);
            } else if (pointerB < 0) {
                pointerB = event.getPointerId(i);
                lastBx = event.getX(i);
                lastBy = event.getY(i);
                break;
            }
        }
    }

    private void handleMove(MotionEvent event) {
        int ia = pointerA < 0 ? -1 : event.findPointerIndex(pointerA);
        int ib = pointerB < 0 ? -1 : event.findPointerIndex(pointerB);
        if (ia < 0) {
            return;
        }
        float ax = event.getX(ia), ay = event.getY(ia);
        if (ib < 0) {
            userTx += ax - lastAx;
            userTy += ay - lastAy;
            lastAx = ax;
            lastAy = ay;
            notifyView();
            invalidate();
            return;
        }
        float bx = event.getX(ib), by = event.getY(ib);

        float oldDist = (float) Math.hypot(lastBx - lastAx, lastBy - lastAy);
        float newDist = (float) Math.hypot(bx - ax, by - ay);
        float focusX = (ax + bx) / 2f;
        float focusY = (ay + by) / 2f;

        // Pan by however far the midpoint moved, before zoom or rotation, so
        // two fingers dragging together behave like one.
        userTx += focusX - (lastAx + lastBx) / 2f;
        userTy += focusY - (lastAy + lastBy) / 2f;

        if (oldDist > 12f && newDist > 12f) {
            applyZoom(newDist / oldDist, focusX, focusY);
            double before = Math.atan2(lastBy - lastAy, lastBx - lastAx);
            double after = Math.atan2(by - ay, bx - ax);
            float delta = (float) Math.toDegrees(after - before);
            if (delta > 180f) {
                delta -= 360f;
            } else if (delta < -180f) {
                delta += 360f;
            }
            userRotDeg += delta;
        }

        lastAx = ax;
        lastAy = ay;
        lastBx = bx;
        lastBy = by;
        notifyView();
        invalidate();
    }

    /**
     * Which way is north. The route is drawn in the map frame, whose x axis is
     * where the chair was pointing when the map was built -- so "up" on this
     * view is not north and there was nothing on screen saying so.
     */
    /**
     * Which way is north, drawn as a needle that actually points at it.
     *
     * The old one was an up-arrow labelled N, and up on this view is not north
     * -- its own comment said so. It pointed at the top of the screen whatever
     * the map was doing, which is a decoration, not a compass.
     */
    private void drawNorth(Canvas canvas) {
        // Big on the map you opened to read; modest on the dashboard card,
        // where the view is a few hundred pixels tall and a full-size dial
        // lands on top of the landmark names.
        float radius = interactive
                ? Math.max(46f, Math.min(70f,
                        Math.min(getWidth(), getHeight()) * 0.11f))
                : 30f;
        // Bottom right, not top right. The fit puts the far end of the route
        // in the top corners, so a dial there lands on 공업센터본관's label on
        // the card. The speed readout takes the bottom LEFT, and bottomInsetPx
        // is already reserved for it, so this corner is the one free of both.
        float cx = getWidth() - radius - 26f;
        float cy = getHeight() - bottomInsetPx - radius - 20f;
        northPaint.setTextSize(radius * 0.75f);
        northPaint.setStrokeWidth(radius * 0.09f);

        // A disc behind it, or the needle disappears into the point cloud.
        northPaint.setStyle(Paint.Style.FILL);
        northPaint.setColor(Color.parseColor("#F2FFFFFF"));
        canvas.drawCircle(cx, cy, radius, northPaint);
        northPaint.setStyle(Paint.Style.STROKE);
        northPaint.setColor(Color.parseColor("#E4D9DA"));
        northPaint.setStrokeWidth(2f);
        canvas.drawCircle(cx, cy, radius, northPaint);

        // Same convention as the heading arrow: map yaw drawn as
        // (cos, -sin), with the view's own rotation taken off first.
        double rad = Math.toRadians(MAP_YAW_OF_NORTH_DEG - userRotDeg);
        float dx = (float) Math.cos(rad);
        float dy = -(float) Math.sin(rad);
        float tipX = cx + dx * (radius - 6f);
        float tipY = cy + dy * (radius - 6f);
        float tailX = cx - dx * (radius * 0.62f);
        float tailY = cy - dy * (radius * 0.62f);

        // Grey tail, red head: which end is north has to be readable without
        // finding the letter first.
        northPaint.setColor(Color.parseColor("#B9C6D4"));
        northPaint.setStrokeWidth(radius * 0.16f);
        canvas.drawLine(cx, cy, tailX, tailY, northPaint);

        northPaint.setStyle(Paint.Style.FILL);
        northPaint.setColor(Color.parseColor("#D92D20"));
        float halfWidth = radius * 0.27f;
        arrow.reset();
        arrow.moveTo(tipX, tipY);
        arrow.lineTo(cx - dy * halfWidth, cy + dx * halfWidth);
        arrow.lineTo(cx + dy * halfWidth, cy - dx * halfWidth);
        arrow.close();
        canvas.drawPath(arrow, northPaint);

        // The letter rides just outside the tip, upright.
        canvas.drawText("N", cx + dx * (radius + radius * 0.42f),
                cy + dy * (radius + radius * 0.42f) + radius * 0.26f, northPaint);
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
        // Landmarks are context, so they belong inside the frame -- fitting to
        // the route alone pushed 애지문 and 공업센터본관, the two ends of the
        // journey, off the edge.
        for (Landmark mark : LANDMARKS) {
            minX = Math.min(minX, mark.x);
            maxX = Math.max(maxX, mark.x);
            minY = Math.min(minY, mark.y);
            maxY = Math.max(maxY, mark.y);
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

        buildUserMatrix();
        // Geometry goes under the user transform; text and the chair marker do
        // not, further down. Stroke widths are divided by the zoom so a route
        // line stays a route line at 8x instead of becoming a blue field.
        canvas.save();
        canvas.concat(userMatrix);
        float widthDiv = Math.max(0.0001f, userScale);
        float routeW = routePaint.getStrokeWidth();
        float doneW = donePaint.getStrokeWidth();
        float bandW = bandEdge.getStrokeWidth();
        routePaint.setStrokeWidth(routeW / widthDiv);
        donePaint.setStrokeWidth(doneW / widthDiv);
        bandEdge.setStrokeWidth(bandW / widthDiv);

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
                offY + (maxY - routeY[0]) * scale, 7f / widthDiv, dotPaint);
        int last = routeX.length - 1;
        canvas.drawCircle(offX + (routeX[last] - minX) * scale,
                offY + (maxY - routeY[last]) * scale, 7f / widthDiv, dotPaint);

        routePaint.setStrokeWidth(routeW);
        donePaint.setStrokeWidth(doneW);
        bandEdge.setStrokeWidth(bandW);
        canvas.restore();

        // Names stay upright and stay the size they were. A label that rotates
        // with the map ends up upside down, and one that scales with it either
        // vanishes or swallows the map.
        for (Landmark mark : LANDMARKS) {
            toScreen(mark.x, mark.y, minX, maxY, offX, offY, scale);
            float lx = scratch[0];
            float ly = scratch[1];
            if (ly < 0 || ly > getHeight() || lx < -getWidth() || lx > 2 * getWidth()) {
                continue;               // off screen at this zoom
            }
            // The anchor is inside the view but the text is centred on it, so a
            // landmark near either edge lost half its name. Pull the text back
            // in rather than dropping it -- the two that sit at the edges are
            // the start and the end of the journey.
            float half = labelPaint.measureText(mark.name) / 2f + 6f;
            float tx = Math.max(half, Math.min(lx, getWidth() - half));
            canvas.drawText(mark.name, tx, ly, labelHalo);
            canvas.drawText(mark.name, tx, ly, labelPaint);
        }

        // Before the pose check, not after it. drawNorth used to sit at the
        // very end of this method, past an early return, so the one time the
        // view most needs to say which way it is pointing -- no fix yet, or a
        // rotated map -- was the one time it did not.
        drawNorth(canvas);

        if (!hasPose) {
            return;
        }
        toScreen(poseX, poseY, minX, maxY, offX, offY, scale);
        float cx = scratch[0];
        float cy = scratch[1];
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
        // The marker is drawn in screen space so it keeps its size, which
        // means the map's rotation has to be added to the heading by hand.
        double rad = Math.toRadians(poseYawDeg - userRotDeg);
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
    }
}
