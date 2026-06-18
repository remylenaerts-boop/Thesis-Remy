package com.example.thesisremy.model;

/*
    A polled sensor endpoint. The server polls "url" every "pollMs" milliseconds
    and broadcasts each new JSON payload to the glasses as a NAMED SSE event
    matching this source's id (e.g. event name "welder1").

    The source with id "welder1" is also broadcast as the legacy UNNAMED
    "welddata" event so the current Android app keeps working until it is
    updated to the new layout-driven flow.

    Fields are public for direct Jackson serialization. LayoutStore reads and
    writes a List<Source> to config/layouts.json on startup and on every change.
*/
public class Source {

    public String  id;        // unique identifier, used as the SSE event name
    public String  name;      // friendly name shown in the dashboard
    public String  url;       // full HTTP URL to poll
    public int     pollMs;    // poll interval in milliseconds (default 1000)
    public boolean enabled;   // when false, this source is not polled

    public Source() {
        // no-arg constructor required by Jackson
    }

    public Source(String id, String name, String url, int pollMs, boolean enabled) {
        this.id      = id;
        this.name    = name;
        this.url     = url;
        this.pollMs  = pollMs;
        this.enabled = enabled;
    }
}
