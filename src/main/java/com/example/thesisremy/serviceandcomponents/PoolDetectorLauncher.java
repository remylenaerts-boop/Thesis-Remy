package com.example.thesisremy.serviceandcomponents;

import jakarta.annotation.PreDestroy;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;

/*
    Starts pool_detector.py as a subprocess when the Java server starts up,
    and kills it cleanly when the server shuts down.

    This means you only need to launch the Java server — the Python detector
    starts automatically alongside it.

    The script is expected to live in the working directory (project root).
    Its stdout/stderr are inherited by the Java process so its log lines appear
    in the same console, prefixed naturally by the detector itself ("[Detector] ...").

    Python command resolution:
      - Tries "python" first  (standard on Windows)
      - Falls back to "python3" (standard on macOS / Linux)
      - Logs an error and skips if neither is found on PATH
*/
@Configuration
public class PoolDetectorLauncher {

    private Process detectorProcess;

    @Bean
    public ApplicationRunner launchDetector() {
        return args -> {
            File workingDir = new File(System.getProperty("user.dir"));
            File script     = new File(workingDir, "pool_detector.py");

            if (!script.exists()) {
                System.err.println("[Launcher] pool_detector.py not found at " + script.getAbsolutePath() + " — detector will not start.");
                return;
            }

            String pythonCmd = resolvePython();
            if (pythonCmd == null) {
                System.err.println("[Launcher] Neither 'python' nor 'python3' found on PATH — detector will not start.");
                System.err.println("[Launcher] Install Python 3 and run: pip install -r requirements.txt");
                return;
            }

            ProcessBuilder pb = new ProcessBuilder(pythonCmd, "pool_detector.py");
            pb.directory(workingDir);
            pb.inheritIO();   // detector output appears in the same console as Java

            detectorProcess = pb.start();
            System.out.println("[Launcher] pool_detector.py started via '" + pythonCmd + "' (pid " + detectorProcess.pid() + ")");
        };
    }

    /*
        Tries "python" and "python3" in order and returns whichever one exists on PATH.
        Returns null if neither is available.
    */
    private String resolvePython() {
        for (String cmd : new String[]{"python", "python3"}) {
            try {
                Process p = new ProcessBuilder(cmd, "--version")
                        .redirectErrorStream(true)
                        .start();
                if (p.waitFor() == 0) return cmd;
            } catch (Exception ignored) {
                // command not found — try the next one
            }
        }
        return null;
    }

    @PreDestroy
    public void stopDetector() {
        if (detectorProcess != null && detectorProcess.isAlive()) {
            detectorProcess.destroy();
            System.out.println("[Launcher] pool_detector.py stopped.");
        }
    }
}
