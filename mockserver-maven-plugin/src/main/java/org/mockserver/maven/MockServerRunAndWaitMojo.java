package org.mockserver.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Run the MockServer and wait for a specified timeout (or indefinitely)
 *
 * @author jamesdbloom
 */
@Mojo(name = "run", requiresProject = false, threadSafe = false)
public class MockServerRunAndWaitMojo extends MockServerAbstractMojo {

    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("Skipping plugin execution");
        } else {
            getLog().info("Starting MockServer on port " + port);
            try {
                if (timeout > 0) {
                    getEmbeddedJettyHolder().start(port, securePort, logLevel).join(TimeUnit.SECONDS.toMillis(timeout));
                } else {
                    getEmbeddedJettyHolder().start(port, securePort, logLevel).join();
                }
            } catch (Exception e) {
                getLog().error("Exception while running MockServer", e);
            }
        }

    }
}
