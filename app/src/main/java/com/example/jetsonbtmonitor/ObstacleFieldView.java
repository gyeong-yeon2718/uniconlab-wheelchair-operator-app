package com.example.jetsonbtmonitor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * What the lidar is looking at, from above, with the chair in the middle.
 *
 * The route map answers "where on the campus is the chair"; this answers the
 * other question an operator standing next to it has -- "what does it think is
 * in front of it, and is that thing moving". They cannot be the same picture:
 * the route map is fitted to 250 m of campus, on which a person 3 m away is
 * less than a pixel.
 *
 * <p>Everything here is in the frame the bridge sends objects in: the
 * chair-aligned LIDAR frame, x forward and y left, which is NOT the map frame
 * {@code pose_x}/{@code pose_y} use. Nothing on this view is transformed by the
 * pose, so a localization that has drifted or died changes nothing about what
 * is drawn -- this is the sensor's own account, and it stays readable exactly
 * when the map view cannot be trusted.
 *
 * <p>Each object's {@code trail} is where the tracker has actually seen it over
 * the last three seconds, expressed about where the chair is now. A parked
 * object therefore has no trail at all, and that is the point: "moving" is a
 * claim the perception node makes, and the path is the evidence for it.
 *
 * <p>No corridor is drawn. The chair's stopping corridor is a constant that
 * lives in {@code safety_gate.py}, and a corridor drawn from a copy of it here
 * would go on reassuring the operator after the real one changed. Which objects
 * are in the way is instead read from {@code band_relation}, which the robot
 * computes against the corridor it is actually using.
 */
public class ObstacleFieldView extends View {

    /**
     * The class colours, from {@code colors.xml}, which carries them in one
     * place because the legend printed beside this view has to agree with it.
     * They are the colours {@code obstacle_clusters.py} gives its RViz
     * markers, so the phone and the screen in the lab do not disagree about
     * which box is the person.
     */
    private final int colorPerson;
    private final int colorVehicle;
    private final int colorObstacle;
    private final int colorOutside;
    private final int colorStale;
    private static final int COLOR_CHAIR = Color.parseColor("#12805C");

    /**
     * The chair's own footprint, from {@code safety_gate.py}'s
     * FOOTPRINT_FRONT_M / FOOTPRINT_REAR_M / FOOTPRINT_HALF_WIDTH_M, about the
     * lidar origin. Drawn so that distances have something the size of a
     * wheelchair to be read against -- it is a scale marker, not a claim about
     * clearance, which is the gate's business and not this view's.
     */
    private static final float CHAIR_FRONT_M = 0.50f;
    private static final float CHAIR_REAR_M = 0.50f;
    private static final float CHAIR_HALF_WIDTH_M = 0.30f;

    /**
     * How far out the view reaches, in metres ahead. Stepped rather than
     * continuous: a range that slides to fit whatever happens to be in view
     * redraws the rings under the reader every frame, and then nothing can be
     * judged by eye from one second to the next.
     */
    private static final float[] RANGE_STEPS = {4f, 6f, 10f, 15f, 20f, 30f};
    /** Lateral half-extent guaranteed visible, as a fraction of the range. */
    private static final float SIDE_FRACTION = 0.62f;
    /** How much of the ground behind the chair stays in frame. */
    private static final float BEHIND_FRACTION = 0.22f;

    private static final class Obj {
        String cls;
        String band;
        float x, y;
        float lengthM, widthM;      // extents along lidar x and y
        String motion;
        float speedMps;
        boolean hasSpeed;
        float[] trail;              // x,y pairs, oldest first; null when none
        float distanceM;
    }

    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chairPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint boxFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint boxEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trailPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelHalo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint emptyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path glyph = new Path();
    private final RectF box = new RectF();

    private final List<Obj> objects = new ArrayList<>();
    private boolean haveObjects;
    private String status;
    /**
     * Which origin x and y are measured from, in the producer's own words.
     *
     * Not decoration. Two nodes publish this pavement and they do not agree:
     * obstacle_clusters.py answers "lidar", hybrid_object_fusion.py answers
     * "chair_centre", and those sit about half a metre apart. Both are
     * chair-aligned and x-forward, so the picture is right either way -- but
     * the chair drawn at the origin means a different thing in each, and a
     * view that silently picks one is a view that cannot be checked.
     */
    private String frame;
    /**
     * How many clusters the bridge left out: ground clutter below the display
     * floor, plus anything past the count cap. Printed, because a trimmed
     * view that does not say it is trimmed reads as an empty pavement -- and
     * the operator would be right to believe it.
     */
    private int hidden;
    private boolean stale = true;
    private String emptyOverride;

    /** Negative means "fit to what is out there"; otherwise a RANGE_STEPS index. */
    private int rangeIndex = -1;
    private float drawnRangeM = RANGE_STEPS[2];

    private final float density;

    public ObstacleFieldView(Context context, AttributeSet attrs) {
        super(context, attrs);
        // dp rather than the raw pixel sizes the route map uses: this view puts
        // labels beside 0.6 m boxes, and at that size the difference between a
        // 2x and a 3.5x screen is the difference between a number and a smudge.
        density = getResources().getDisplayMetrics().density;
        colorPerson = ContextCompat.getColor(context, R.color.object_person);
        colorVehicle = ContextCompat.getColor(context, R.color.object_vehicle);
        colorObstacle = ContextCompat.getColor(context, R.color.object_obstacle);
        colorOutside = ContextCompat.getColor(context, R.color.object_outside);
        colorStale = ContextCompat.getColor(context, R.color.object_stale);

        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(dp(1f));
        ringPaint.setColor(Color.parseColor("#D6DEE8"));

        ringTextPaint.setColor(Color.parseColor("#98A2B3"));
        ringTextPaint.setTextSize(dp(10f));
        ringTextPaint.setTextAlign(Paint.Align.CENTER);

        chairPaint.setColor(COLOR_CHAIR);

        boxFill.setStyle(Paint.Style.FILL);
        boxEdge.setStyle(Paint.Style.STROKE);
        boxEdge.setStrokeWidth(dp(1.5f));

        trailPaint.setStyle(Paint.Style.STROKE);
        trailPaint.setStrokeWidth(dp(2.5f));
        trailPaint.setStrokeCap(Paint.Cap.ROUND);
        trailPaint.setStrokeJoin(Paint.Join.ROUND);

        labelPaint.setColor(Color.parseColor("#10243A"));
        labelPaint.setTextSize(dp(11f));
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setFakeBoldText(true);
        labelHalo.setColor(Color.WHITE);
        labelHalo.setTextSize(dp(11f));
        labelHalo.setTextAlign(Paint.Align.CENTER);
        labelHalo.setFakeBoldText(true);
        labelHalo.setStyle(Paint.Style.STROKE);
        labelHalo.setStrokeWidth(dp(3f));

        emptyPaint.setColor(Color.parseColor("#98A2B3"));
        emptyPaint.setTextSize(dp(13f));
        emptyPaint.setTextAlign(Paint.Align.CENTER);
    }

    private float dp(float value) {
        return value * density;
    }

    /**
     * One telemetry frame's worth of perception.
     *
     * @param frameObjects the frame's {@code objects} array, or null when the
     *                     bridge marked it unavailable -- which is not the same
     *                     as an empty pavement and must not be drawn as one.
     * @param objectsStatus the frame's {@code objects_status}: "OK", one of the
     *                     producer's own refusals (NO_CLOUD, NO_MAP_POSE), or
     *                     null when nothing has been heard at all.
     * @param objectsFrame the frame's {@code objects_frame}: the producer's
     *                     own name for the origin x/y are about, or null when
     *                     it did not say -- which is not the same as "lidar".
     * @param linkStale whether the reading is too old to believe.
     */
    public void update(JSONArray frameObjects, String objectsStatus,
                       String objectsFrame, int objectsHidden, boolean linkStale) {
        status = objectsStatus;
        frame = objectsFrame;
        hidden = objectsHidden;
        stale = linkStale;
        haveObjects = frameObjects != null;
        // A frame has landed, so "waiting for the first one" has stopped being
        // true. Left in place it would go on claiming that over an empty
        // pavement, which is the one reading here that must not be guessed at.
        emptyOverride = null;
        objects.clear();
        for (int i = 0; frameObjects != null && i < frameObjects.length(); i++) {
            JSONObject item = frameObjects.optJSONObject(i);
            if (item == null || item.isNull("x") || item.isNull("y")) {
                continue;           // no position is not a position of zero
            }
            Obj obj = new Obj();
            obj.cls = item.optString("class", "obstacle");
            obj.band = item.optString("band_relation", "");
            obj.x = (float) item.optDouble("x");
            obj.y = (float) item.optDouble("y");
            obj.distanceM = (float) Math.hypot(obj.x, obj.y);
            JSONArray size = item.optJSONArray("size");
            obj.lengthM = size == null ? 0.3f : (float) size.optDouble(0, 0.3);
            obj.widthM = size == null ? 0.3f : (float) size.optDouble(1, 0.3);
            obj.motion = item.optString("motion", "unknown");
            obj.hasSpeed = !item.isNull("speed_mps");
            obj.speedMps = (float) item.optDouble("speed_mps", 0.0);
            obj.trail = readTrail(item.optJSONArray("trail"));
            objects.add(obj);
        }
        // Far first, so a near object is never hidden under one behind it.
        Collections.sort(objects, new Comparator<Obj>() {
            @Override
            public int compare(Obj a, Obj b) {
                return Float.compare(b.distanceM, a.distanceM);
            }
        });
        invalidate();
    }

    /** @return x,y pairs oldest first, or null when there is no usable path. */
    private static float[] readTrail(JSONArray trail) {
        if (trail == null || trail.length() < 2) {
            return null;
        }
        float[] points = new float[trail.length() * 2];
        for (int i = 0; i < trail.length(); i++) {
            JSONArray point = trail.optJSONArray(i);
            if (point == null || point.length() < 2) {
                return null;        // a path with a hole in it is not a path
            }
            points[i * 2] = (float) point.optDouble(0);
            points[i * 2 + 1] = (float) point.optDouble(1);
        }
        return points;
    }

    /** Shown instead of the field until the first frame arrives. */
    public void setEmptyOverride(String message) {
        emptyOverride = message;
        invalidate();
    }

    /**
     * The link went quiet. Grey what is drawn rather than let it stand.
     *
     * A pavement three seconds old is drawn exactly like a pavement now, and
     * of the two only one is a reason to keep driving.
     */
    public void markStale() {
        if (!stale) {
            stale = true;
            invalidate();
        }
    }

    /** @return true if the range changed; false at either end of the steps. */
    public boolean stepRange(int by) {
        int from = rangeIndex < 0 ? nearestStep(drawnRangeM) : rangeIndex;
        int next = Math.max(0, Math.min(RANGE_STEPS.length - 1, from + by));
        if (next == rangeIndex) {
            return false;
        }
        rangeIndex = next;
        invalidate();
        return true;
    }

    /** Back to fitting whatever the lidar can see. */
    public void resetRange() {
        rangeIndex = -1;
        invalidate();
    }

    public boolean isRangeLocked() {
        return rangeIndex >= 0;
    }

    private static int nearestStep(float metres) {
        for (int i = 0; i < RANGE_STEPS.length; i++) {
            if (RANGE_STEPS[i] >= metres) {
                return i;
            }
        }
        return RANGE_STEPS.length - 1;
    }

    /**
     * The smallest step that holds everything worth seeing.
     *
     * Trails count as well as boxes. A path that runs off the edge of the view
     * is how "someone walked in from over there" turns into "someone appeared".
     */
    private float autoRange() {
        float needed = RANGE_STEPS[0];
        for (Obj obj : objects) {
            needed = Math.max(needed, Math.max(Math.abs(obj.x),
                    Math.abs(obj.y) / SIDE_FRACTION));
            for (int i = 0; obj.trail != null && i < obj.trail.length; i += 2) {
                needed = Math.max(needed, Math.max(Math.abs(obj.trail[i]),
                        Math.abs(obj.trail[i + 1]) / SIDE_FRACTION));
            }
        }
        return RANGE_STEPS[nearestStep(needed * 1.08f)];
    }

    private static float ringStep(float rangeM) {
        if (rangeM <= 6f) {
            return 1f;
        }
        if (rangeM <= 10f) {
            return 2f;
        }
        if (rangeM <= 20f) {
            return 5f;
        }
        return 10f;
    }

    private int colorFor(Obj obj) {
        if (stale) {
            return colorStale;
        }
        if ("outside".equals(obj.band) || "outside_band".equals(obj.cls)) {
            return colorOutside;
        }
        if ("person".equals(obj.cls)) {
            return colorPerson;
        }
        if ("vehicle".equals(obj.cls)) {
            return colorVehicle;
        }
        return colorObstacle;
    }

    /**
     * The one line to print when there is no field to draw.
     *
     * "없음" is the wrong answer to four different questions -- nothing
     * connected, nothing forwarded, no cloud, empty pavement -- and only the
     * last of them means it is safe to look away.
     */
    private String emptyMessage() {
        if (emptyOverride != null) {
            return emptyOverride;
        }
        if (status == null) {
            return "장애물 인식 데이터 없음";
        }
        if (!"OK".equals(status)) {
            return "인식 중단: " + status;
        }
        if (!haveObjects) {
            return "물체 좌표 없음 (브릿지가 보내지 않음)";
        }
        return "감지된 물체 없음";
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float pad = dp(10f);
        float availW = getWidth() - 2 * pad;
        float availH = getHeight() - 2 * pad;
        if (availW <= 0 || availH <= 0) {
            return;
        }

        drawnRangeM = rangeIndex >= 0 ? RANGE_STEPS[rangeIndex] : autoRange();
        // One scale for both axes; a stretched view would misreport how close
        // something beside the chair is.
        float scale = Math.min(availH / (drawnRangeM * (1f + BEHIND_FRACTION)),
                availW / (drawnRangeM * SIDE_FRACTION * 2f));
        float cx = pad + availW / 2f;
        float cy = pad + availH - drawnRangeM * BEHIND_FRACTION * scale;

        drawRings(canvas, cx, cy, scale);
        drawChair(canvas, cx, cy, scale);

        // Paths under boxes: the box is the object, the path is where it has
        // been, and the object should never be hidden by its own history.
        for (int i = 0; i < objects.size(); i++) {
            drawTrail(canvas, objects.get(i), cx, cy, scale);
        }
        for (int i = 0; i < objects.size(); i++) {
            drawObject(canvas, objects.get(i), cx, cy, scale);
        }
        drawLabels(canvas, cx, cy, scale);

        if (objects.isEmpty()) {
            canvas.drawText(emptyMessage(), getWidth() / 2f,
                    pad + availH / 2f, emptyPaint);
        }
        drawHeader(canvas, pad);
    }

    private void drawRings(Canvas canvas, float cx, float cy, float scale) {
        float step = ringStep(drawnRangeM);
        for (float r = step; r <= drawnRangeM + 0.01f; r += step) {
            canvas.drawCircle(cx, cy, r * scale, ringPaint);
            // On the forward axis, where the number is what the ring is for.
            canvas.drawText(String.format(Locale.US, "%.0fm", r),
                    cx + dp(14f), cy - r * scale + dp(4f), ringTextPaint);
        }
        // Straight ahead, so a box can be read as left or right of the chair
        // without measuring it against the edges of the view.
        ringPaint.setColor(Color.parseColor("#E4EAF2"));
        canvas.drawLine(cx, cy, cx, cy - drawnRangeM * scale, ringPaint);
        ringPaint.setColor(Color.parseColor("#D6DEE8"));
    }

    private void drawChair(Canvas canvas, float cx, float cy, float scale) {
        box.set(cx - CHAIR_HALF_WIDTH_M * scale,
                cy - CHAIR_FRONT_M * scale,
                cx + CHAIR_HALF_WIDTH_M * scale,
                cy + CHAIR_REAR_M * scale);
        chairPaint.setColor(stale ? colorStale : COLOR_CHAIR);
        chairPaint.setStyle(Paint.Style.FILL);
        chairPaint.setAlpha(48);
        canvas.drawRoundRect(box, dp(3f), dp(3f), chairPaint);
        chairPaint.setAlpha(255);
        chairPaint.setStyle(Paint.Style.STROKE);
        chairPaint.setStrokeWidth(dp(1.5f));
        canvas.drawRoundRect(box, dp(3f), dp(3f), chairPaint);

        // Which way is forward. Everything here is drawn relative to it, and a
        // top-down picture with no heading in it is one that can be read
        // backwards -- the object behind the chair becomes the one ahead.
        chairPaint.setStyle(Paint.Style.FILL);
        float tip = cy - CHAIR_FRONT_M * scale - dp(7f);
        glyph.reset();
        glyph.moveTo(cx, tip);
        glyph.lineTo(cx - dp(5f), tip + dp(8f));
        glyph.lineTo(cx + dp(5f), tip + dp(8f));
        glyph.close();
        canvas.drawPath(glyph, chairPaint);
    }

    private void drawTrail(Canvas canvas, Obj obj, float cx, float cy, float scale) {
        if (obj.trail == null || obj.trail.length < 4) {
            return;
        }
        glyph.reset();
        for (int i = 0; i < obj.trail.length; i += 2) {
            float px = cx - obj.trail[i + 1] * scale;
            float py = cy - obj.trail[i] * scale;
            if (i == 0) {
                glyph.moveTo(px, py);
            } else {
                glyph.lineTo(px, py);
            }
        }
        trailPaint.setColor(colorFor(obj));
        trailPaint.setStyle(Paint.Style.STROKE);
        trailPaint.setAlpha(110);
        canvas.drawPath(glyph, trailPaint);
        // The oldest end, marked. A line does not say which way along itself
        // the object was travelling, and that is most of what a path is for.
        trailPaint.setStyle(Paint.Style.FILL);
        trailPaint.setAlpha(70);
        canvas.drawCircle(cx - obj.trail[1] * scale, cy - obj.trail[0] * scale,
                dp(2.5f), trailPaint);
        trailPaint.setAlpha(255);
    }

    private void drawObject(Canvas canvas, Obj obj, float cx, float cy, float scale) {
        boolean outside = "outside".equals(obj.band) || "outside_band".equals(obj.cls);
        int color = colorFor(obj);
        // A minimum size in pixels, not in metres: a 0.3 m bollard at 20 m
        // range is a third of a pixel, and an object that cannot be seen is
        // the same as one that was never sent.
        float halfW = Math.max(obj.widthM * scale / 2f, dp(3.5f));
        float halfL = Math.max(obj.lengthM * scale / 2f, dp(3.5f));
        float px = cx - obj.y * scale;
        float py = cy - obj.x * scale;
        box.set(px - halfW, py - halfL, px + halfW, py + halfL);

        // Outside the corridor: an outline, and nothing else. Most of what
        // the lidar returns beside a pavement is the pavement -- kerbs, walls,
        // hedges, parked cars -- and filled boxes for all of it bury the one
        // object that is actually in the way. They still have to be drawn:
        // a person standing just off the corridor is exactly who steps into
        // it. So they are kept as scenery, not as findings.
        if (!outside) {
            boxFill.setColor(color);
            boxFill.setAlpha(95);
            canvas.drawRoundRect(box, dp(2f), dp(2f), boxFill);
        }
        boxEdge.setColor(color);
        boxEdge.setAlpha(outside ? 140 : 255);
        // In the chair's way: heavier. It is the only distinction on this view
        // that decides anything.
        boxEdge.setStrokeWidth(dp(outside ? 1f : 2f));
        canvas.drawRoundRect(box, dp(2f), dp(2f), boxEdge);
        boxEdge.setAlpha(255);
    }

    /**
     * Labels for the few objects a label helps with.
     *
     * Naming all ten covers the view in text at the exact moment it is busy
     * enough to be worth looking at. What earns one is being in the way or
     * being on the move; the rest are shapes, and their colour already says
     * what they are. Nearest first, because that is the order they matter in.
     */
    private void drawLabels(Canvas canvas, float cx, float cy, float scale) {
        int drawn = 0;
        for (int i = objects.size() - 1; i >= 0 && drawn < 4; i--) {
            Obj obj = objects.get(i);
            boolean inBand = "inside".equals(obj.band) || "overlap".equals(obj.band);
            boolean moving = "moving".equals(obj.motion);
            // Scenery outside the corridor gets no label even when the tracker
            // calls it moving: a hedge whose centroid slides as more of it
            // comes into view is the commonest "moving" thing on a pavement,
            // and naming it is what turns the view back into clutter.
            if (!inBand) {
                continue;
            }
            if (!moving && !"static".equals(obj.motion)) {
                continue;
            }
            StringBuilder text = new StringBuilder(
                    String.format(Locale.US, "%.1fm", obj.distanceM));
            if (moving && obj.hasSpeed) {
                text.append(String.format(Locale.US, " · %.1fm/s", obj.speedMps));
            } else if ("static".equals(obj.motion)) {
                text.append(" · 정지");
            }
            float px = cx - obj.y * scale;
            float py = cy - obj.x * scale
                    - Math.max(obj.lengthM * scale / 2f, dp(3.5f)) - dp(5f);
            float half = labelPaint.measureText(text.toString()) / 2f + dp(2f);
            px = Math.max(half, Math.min(px, getWidth() - half));
            py = Math.max(dp(24f), py);
            canvas.drawText(text.toString(), px, py, labelHalo);
            canvas.drawText(text.toString(), px, py, labelPaint);
            drawn++;
        }
    }

    /** Range and count, so the picture cannot be read at the wrong scale. */
    private void drawHeader(Canvas canvas, float pad) {
        StringBuilder text = new StringBuilder(
                String.format(Locale.US, "반경 %.0fm", drawnRangeM));
        if (isRangeLocked()) {
            text.append(" 고정");
        }
        if (!objects.isEmpty()) {
            text.append(String.format(Locale.US, "  ·  %d개", objects.size()));
        }
        if (hidden > 0) {
            text.append(String.format(Locale.US, " (+%d 숨김)", hidden));
        }
        // Whose origin the rings are measured from. One word, from the robot,
        // because the alternative is a picture that cannot say what it is of.
        text.append("  ·  ").append(frame == null || frame.isEmpty()
                ? "기준 미상" : frame);
        if (stale) {
            text.append("  ·  갱신 끊김");
        }
        ringTextPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(text.toString(), pad, pad + dp(10f), ringTextPaint);
        ringTextPaint.setTextAlign(Paint.Align.CENTER);
    }
}
