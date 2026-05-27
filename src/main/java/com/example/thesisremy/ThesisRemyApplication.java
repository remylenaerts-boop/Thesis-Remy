package com.example.thesisremy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/*
    Entry point of the Spring Boot application.

    @SpringBootApplication tells Spring to scan all classes in this package and
    set up the entire application context automatically: no manual configuration needed.

    @EnableScheduling activates support for @Scheduled methods. Without this annotation,
    the DataGetter polling loop would simply never run.
*/
@SpringBootApplication
@EnableScheduling
public class ThesisRemyApplication {

    public static void main(String[] args) {
        SpringApplication.run(ThesisRemyApplication.class, args);
    }
}
/*
{
  "timestamp_pc": "2026-03-15T16:54:15",
  "inference_latest": [
    {
      "pc_time": "1773590047.3070812",
      "chunk_time": "1773589655.3558195",
      "robot_time": "0.0",
      "prob_0": 3.033396148266476e-06, //
      "prob_1": 0.9999969666038517, //          -->    HEATBAR DISPLAY - ADD 10 POINTS AT THE SAME TIME
      "pred_class": 1, //                       -->    MAKE GRAPH RED WHEN 1 AND GREEN FOR 0

      "x_mm": 0.0,
      "y_mm": 0.0,
      "z_mm": 0.0
    },
    ... 9 more inference_latest
  ],
  "analog_rms_per_channel": [
    8.657294123062512,//ai0: AE sensor1, dB
    8.851206300118678,// ai1: AE sensor2, dB
    8.988331226671345,// ai2: Microphone, dB
    9.075648139761755,// ai3: Current, A        -->    VALUE DISPLAY
    9.090609525674049,// ai4: Voltage, V        -->    VALUE DISPLAY
    9.055031398937517 // ai5: Gas, l/min,       -->    VALUE DISPLAY
  ],
  "robot_xyz_mm": {
    "x": 0.0,
    "y": 0.0,
    "z": 0.0
  }
}
 */
