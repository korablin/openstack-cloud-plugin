package jenkins.plugins.openstack.compute;

import hudson.node_monitors.DiskSpaceMonitorDescriptor;
import hudson.remoting.VirtualChannel;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.OfflineCause;
import hudson.slaves.SlaveComputer;
import hudson.slaves.OfflineCause.SimpleOfflineCause;

import java.io.IOException;
import java.util.logging.Logger;

import jenkins.model.Jenkins;

import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.cloudstats.TrackedItem;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.HttpRedirect;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.HttpResponses;
import org.kohsuke.stapler.StaplerResponse;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.servlet.http.HttpServletResponse;

/**
 * OpenStack version of Jenkins {@link SlaveComputer} - responsible for terminating an instance.
 */
public class JCloudsComputer extends AbstractCloudComputer<JCloudsSlave> implements TrackedItem {

    private static final Logger LOGGER = Logger.getLogger(JCloudsComputer.class.getName());
    private final ProvisioningActivity.Id provisioningId;

    public JCloudsComputer(JCloudsSlave slave) {
        super(slave);
        this.provisioningId = slave.getId();
    }

    @Override
    public @CheckForNull JCloudsSlave getNode() {
        return super.getNode();
    }

    @Override
    public @Nonnull ProvisioningActivity.Id getId() {
        return provisioningId;
    }

    /**
     * Flag the slave to be collected asynchronously.
     */
    public void setPendingDelete(boolean newVal) {
        boolean is = isPendingDelete();
        if (is == newVal) return;

        LOGGER.info("Setting " + getName() + " pending delete status to " + newVal);
        setTemporarilyOffline(newVal, newVal ? PENDING_TERMINATION : null);
    }

    /**
     * Is slave pending termination.
     */
    public boolean isPendingDelete() {
        // No need  to synchronize reading as offlineCause is volatile
        return offlineCause instanceof PendingTermination;
    }

    /**
     * Get computer {@link OfflineCause} provided it is severe enough the computer should be discarded.
     *
     * @return value if should be discarded, null if online or offline with non-fatal cause.
     */
    /*package*/ @CheckForNull OfflineCause getFatalOfflineCause() {
        OfflineCause oc = getOfflineCause();
        return oc instanceof DiskSpaceMonitorDescriptor.DiskSpace || oc instanceof OfflineCause.ChannelTermination
                ? oc
                : null
        ;
    }

    // Hide /configure view inherited from Computer
    @Restricted(DoNotUse.class)
    public void doConfigure(StaplerResponse rsp) throws IOException {
        rsp.sendError(HttpServletResponse.SC_NOT_FOUND);
    }

    @Override @Restricted(NoExternalUse.class)
    public HttpResponse doDoDelete() throws IOException {
        setPendingDelete(true);
        try {
            return super.doDoDelete();
        } catch (IOException|RuntimeException ex) {
            setPendingDelete(false);
            return HttpResponses.error(500, ex);
        }
    }

    @Restricted(NoExternalUse.class)
    public HttpRedirect doScheduleTermination() {
        checkPermission(DISCONNECT);
        setPendingDelete(true);
        return new HttpRedirect(".");
    }

    /**
     * Delete the slave, terminate the instance.
     */
    public void deleteSlave() throws IOException, InterruptedException {
        LOGGER.info("Deleting slave " + getName());
        JCloudsSlave slave = getNode();

        // Slave already deleted
        if (slave == null) {
            LOGGER.info("Skipping, computer is gone already: " + getName());
            return;
        }

        VirtualChannel channel = slave.getChannel();
        if (channel != null) {
            channel.close();
        }
        slave.terminate();
        Jenkins.getActiveInstance().removeNode(slave);
        LOGGER.info("Deleted slave " + getName());
    }

    // Singleton
    private static final PendingTermination PENDING_TERMINATION = new PendingTermination();

    private static final class PendingTermination extends SimpleOfflineCause {

        protected PendingTermination() {
            super(Messages._DeletedCause());
        }
    }
}
