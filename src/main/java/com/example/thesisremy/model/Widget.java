package com.example.thesisremy.model;

/*
    One visible element on the glasses overlay: a gauge, a heat bar, a number,
    or a line graph. Bound to a Source by id, with a jsonPath that picks the
    value (or array of values) out of that source's last JSON payload.

    Value resolution on the Android side:
        raw       = evaluate(jsonPath, sourceJson)
        displayed = raw * scale + offset

    For widget types that consume an array (heat bar, line graph) the jsonPath
    must resolve to a JSON array; "scale" and "offset" are applied to every
    element. For scalar widgets (gauge, number) the path resolves to a single
    number.

    Position and size are in pixels on the glasses display
    (BT-300 is 921 x 518).

    Fields are public for direct Jackson serialization.
*/
public class Widget {

    public String  id;          // unique identifier within the layout
    public String  type;        // "gauge", "heatbar", "number", "linegraph"
    public String  label;       // caption shown on the widget (e.g. "CURRENT")
    public String  unit;        // unit text (e.g. "A", "V", "l/min"); may be empty

    public String  source;      // Source.id this widget is bound to
    public String  jsonPath;    // dotted path like "analog_rms_per_channel[3]"

    public double  scale;       // raw value is multiplied by this (default 1.0)
    public double  offset;      // ... then this is added (default 0.0)

    public double  min;         // gauge / graph minimum
    public double  max;         // gauge / graph maximum

    public String  color;       // optional 0xAARRGGBB hex string; null = type default

    public int     x;           // left edge in pixels on the glasses display
    public int     y;           // top edge
    public int     w;           // width
    public int     h;           // height

    // Heat bar only: how many seconds of history to display. Ignored by other types.
    public int     heatbarDuration;

    public Widget() {
        // no-arg constructor required by Jackson
        this.scale = 1.0;
        this.offset = 0.0;
        this.heatbarDuration = 30;
    }
}
