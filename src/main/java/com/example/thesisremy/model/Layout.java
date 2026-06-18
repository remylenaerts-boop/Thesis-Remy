package com.example.thesisremy.model;

import java.util.ArrayList;
import java.util.List;

/*
    A named preset: an ordered list of Widget instances that together describe
    what the glasses should display. The dashboard editor produces these,
    LayoutStore persists them, and the "push" endpoint broadcasts the active
    layout to the glasses as a named SSE event "layout".

    Fields are public for direct Jackson serialization.
*/
public class Layout {

    public String       name;       // unique preset name (e.g. "default", "TIG-porosity")
    public int          displayW;   // target glasses width in pixels (BT-300 = 921)
    public int          displayH;   // target glasses height in pixels (BT-300 = 518)
    public List<Widget> widgets;    // never null; empty list means "nothing on screen"

    public Layout() {
        // no-arg constructor required by Jackson
        this.widgets  = new ArrayList<>();
        this.displayW = 921;
        this.displayH = 518;
    }

    public Layout(String name) {
        this.name     = name;
        this.widgets  = new ArrayList<>();
        this.displayW = 921;
        this.displayH = 518;
    }
}
