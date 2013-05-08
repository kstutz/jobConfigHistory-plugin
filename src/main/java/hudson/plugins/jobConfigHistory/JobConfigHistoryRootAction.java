package hudson.plugins.jobConfigHistory;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

import javax.servlet.ServletException;
import javax.xml.transform.stream.StreamSource;

import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import hudson.Extension;
import hudson.FilePath;
import hudson.XmlFile;
import hudson.model.AbstractProject;
import hudson.model.Hudson;
import hudson.model.Item;
import hudson.model.RootAction;
import hudson.plugins.jobConfigHistory.JobConfigHistoryBaseAction.SideBySideView.Line;
import hudson.security.AccessControlled;
import hudson.security.Permission;
import hudson.util.MultipartFormDataParser;

/**
 *
 * @author Stefan Brausch, mfriedenhagen
 */

@Extension
public class JobConfigHistoryRootAction extends JobConfigHistoryBaseAction implements RootAction {

    /** Our logger. */
    private static final Logger LOG = Logger.getLogger(JobConfigHistoryRootAction.class.getName());
    
    /**
     * Constructor necessary for testing.
     */
    public JobConfigHistoryRootAction() {
        super();
    }

    /**
     * {@inheritDoc}
     *
     * This actions always starts from the context directly, so prefix {@link JobConfigHistoryConsts#URLNAME} with a
     * slash.
     */
    @Override
    public final String getUrlName() {
        return "/" + JobConfigHistoryConsts.URLNAME;
    }

    /**
     * {@inheritDoc}
     * 
     * Make method final, as we always want the same icon file. Returns
     * {@code null} to hide the icon if the user is not allowed to configure
     * jobs.
     */
    public final String getIconFileName() {
        if (hasConfigurePermission() || hasJobConfigurePermission()) {
            return JobConfigHistoryConsts.ICONFILENAME;
        } else {
            return null;
        }
    }

    /**
     * Returns the configuration history entries 
     * for either {@link AbstractItem}s or system changes or deleted jobs 
     * or all of the above.
     *
     * @return list of configuration histories (as ConfigInfo)
     * @throws IOException
     *             if one of the history entries might not be read.
     */
    public final List<ConfigInfo> getConfigs() throws IOException {
        final String filter = getRequestParameter("filter");
        List<ConfigInfo> configs = null;

        if (filter == null || "system".equals(filter)) {
            configs = getSystemConfigs();
        } else if ("all".equals(filter)) {
            configs = getJobConfigs("jobs");
            configs.addAll(getJobConfigs("deleted"));
            configs.addAll(getSystemConfigs());
        } else {
            configs = getJobConfigs(filter);
        }
        
        Collections.sort(configs, ConfigInfoComparator.INSTANCE);
        return configs;
    }

    /**
     * Returns the configuration history entries for all system files
     * in this Hudson instance.
     * 
     * @return List of config infos.
     * @throws IOException
     *             if one of the history entries might not be read.
     */
    protected List<ConfigInfo> getSystemConfigs() throws IOException {
        final ArrayList<ConfigInfo> configs = new ArrayList<ConfigInfo>();
        final File historyRootDir = getPlugin().getConfiguredHistoryRootDir();
        
        if (!hasConfigurePermission()) {
            return configs;
        }

        if (!historyRootDir.isDirectory()) {
            LOG.fine(historyRootDir + " is not a directory, assuming that no history exists yet.");
        } else {
            final File[] itemDirs = historyRootDir.listFiles();
            for (final File itemDir : itemDirs) {
                //skip the "jobs" directory since we're looking for system changes
                if (itemDir.getName().equals(JobConfigHistoryConsts.JOBS_HISTORY_DIR)) {
                    continue;
                }
                for (final File historyDir : itemDir.listFiles(JobConfigHistory.HISTORY_FILTER)) {
                    final XmlFile historyXml = new XmlFile(new File(historyDir, JobConfigHistoryConsts.HISTORY_FILE));
                    final HistoryDescr histDescr = (HistoryDescr) historyXml.read();
                    final ConfigInfo config = ConfigInfo.create(itemDir.getName(), historyDir, histDescr, false);
                    configs.add(config);
                }
            }
        }
        return configs; 
    }

    /**
     * Returns the configuration history entries for all jobs 
     * or deleted jobs in this Hudson instance.
     * 
     * @param type Whether we want to see all jobs or just the deleted jobs.
     * @return List of config infos.
     * @throws IOException
     *             if one of the history entries might not be read.
     */
    protected List<ConfigInfo> getJobConfigs(String type) throws IOException {
        final ArrayList<ConfigInfo> configs = new ArrayList<ConfigInfo>();
        final File historyRootDir = getPlugin().getJobHistoryRootDir();
        
        if (!hasJobConfigurePermission()) {
            return configs;
        }

        if (!historyRootDir.isDirectory()) {
            LOG.fine(historyRootDir + " is not a directory, assuming that no history exists yet.");
        } else {
            final File[] itemDirs;
            if ("deleted".equals(type)) {
                itemDirs = historyRootDir.listFiles(JobConfigHistory.DELETED_FILTER);
            } else {
                itemDirs = historyRootDir.listFiles();
            }
            for (final File itemDir : itemDirs) {
                for (final File historyDir : itemDir.listFiles(JobConfigHistory.HISTORY_FILTER)) {
                    final XmlFile historyXml = new XmlFile(new File(historyDir, JobConfigHistoryConsts.HISTORY_FILE));
                    final HistoryDescr histDescr = (HistoryDescr) historyXml.read();
                    final ConfigInfo config;
                    if ("jobs".equals(type) && !itemDir.getName().contains(JobConfigHistoryConsts.DELETED_MARKER)) {
                        config = ConfigInfo.create(itemDir.getName(), historyDir, histDescr, true);
                    } else {
                        config = ConfigInfo.create(itemDir.getName(), historyDir, histDescr, false);
                    }
                    if (!("deleted".equals(type) && !"Deleted".equals(config.getOperation()))) {
                        configs.add(config);
                    }
                }
            }
        }
        return configs; 
    }

    
    /**
     * Returns the configuration history entries for one group of system files
     * or deleted jobs.
     * 
     * @param name The name of the job or system file
     * @return Configs list for one group of system configuration files or a deleted job.
     * @throws IOException
     *             if one of the history entries might not be read.
     */
    public final List<ConfigInfo> getSingleConfigs(String name) throws IOException {
        final ArrayList<ConfigInfo> configs = new ArrayList<ConfigInfo>();
        final File historyRootDir;
        if (name.contains(JobConfigHistoryConsts.DELETED_MARKER)) {
            historyRootDir = getPlugin().getJobHistoryRootDir();
        } else {
            historyRootDir = getPlugin().getConfiguredHistoryRootDir();
        }

        for (final File itemDir : historyRootDir.listFiles()) {
            if (itemDir.getName().equals(name)) {
                for (final File historyDir : itemDir.listFiles(JobConfigHistory.HISTORY_FILTER)) {
                    final XmlFile historyXml = new XmlFile(new File(historyDir, JobConfigHistoryConsts.HISTORY_FILE));
                    final HistoryDescr histDescr = (HistoryDescr) historyXml.read();
                    final ConfigInfo config = ConfigInfo.create(itemDir.getName(), historyDir, histDescr, false);
                    configs.add(config);
                }
            }
        }
        Collections.sort(configs, ConfigInfoComparator.INSTANCE);
        return configs;
    }

    /**
     * Returns {@link JobConfigHistoryBaseAction#getConfigXml(String)} as
     * String.
     * 
     * @return content of the {@code config.xml} found in directory given by the
     *         request parameter {@code file}.
     * @throws IOException
     *             if the config file could not be read or converted to an xml
     *             string.
     */
    public final String getFile() throws IOException {
        final String name = getRequestParameter("name");
        if ((name.contains(JobConfigHistoryConsts.DELETED_MARKER) && hasJobConfigurePermission())
                || hasConfigurePermission()) {
            final String timestamp = getRequestParameter("timestamp");
            final XmlFile xmlFile = getOldConfigXml(name, timestamp);
            return xmlFile.asString();
        } else {
            return "No permission to view config files";
        }
    }

    /**
     * Creates links to the correct configOutput.jellys for job history vs. system history
     * and for xml vs. plain text.
     * 
     * @param config ConfigInfo.
     * @param type Output type ('xml' or 'plain').
     * @return The link as String.
     */
    public final String createLinkToJobFiles(ConfigInfo config, String type) {
        String link = null;
        final String name = config.getJob();
        final String timestamp = config.getDate();
    
        if (name.contains(JobConfigHistoryConsts.DELETED_MARKER)) {
            //if last config.xml for deleted job doesn't exist (due to deletion while being disabled)
            //then return link to the version before
            if (getOldConfigXml(name, timestamp) == null) {
                try {
                    final ConfigInfo nextConfig = getSingleConfigs(name).get(1);
                    link = getHudson().getRootUrl() + "job/" + config.getJob().replace("/", "/job/") + getUrlName()
+ "/configOutput?type=" + type + "&name=" + nextConfig.getJob() + "&timestamp=" + nextConfig.getDate();
                } catch (IOException ex) {
                    LOG.finest("Unable to get config for " + name);
                }
            } else {
                link = "configOutput?type=" + type + "&name=" + name + "&timestamp=" + timestamp;
            }
        } else if (config.getIsJob()) {
            link = getHudson().getRootUrl() + "job/" + name + getUrlName() 
                    + "/configOutput?type=" + type + "&timestamp=" + timestamp;
        } else {
            link = "configOutput?type=" + type + "&name=" + name + "&timestamp=" + timestamp;
        }
        return link;
    }
    
    /**
     * {@inheritDoc}
     *
     * Returns the hudson instance.
     */
    @Override
    protected AccessControlled getAccessControlledObject() {
        return getHudson();
    }
    
    @Override
    protected void checkConfigurePermission() {
        getAccessControlledObject().checkPermission(Permission.CONFIGURE);
    }

    @Override
    public boolean hasConfigurePermission() {
        return getAccessControlledObject().hasPermission(Permission.CONFIGURE);
    }
    
    /**
     * Returns whether the current user may configure jobs.
     * 
     * @return true if the current user may configure jobs.
     */
    public boolean hasJobConfigurePermission() {
        return getAccessControlledObject().hasPermission(Item.CONFIGURE);
    }
    
    /**
     * Parses the incoming {@code POST} request and redirects as
     * {@code GET showDiffFiles}.
     * 
     * @param req
     *            incoming request
     * @param rsp
     *            outgoing response
     * @throws ServletException
     *             when parsing the request as {@link MultipartFormDataParser}
     *             does not succeed.
     * @throws IOException
     *             when the redirection does not succeed.
     */
    public final void doDiffFiles(StaplerRequest req, StaplerResponse rsp)
        throws ServletException, IOException {
        final MultipartFormDataParser parser = new MultipartFormDataParser(req);
        rsp.sendRedirect("showDiffFiles?name=" + parser.get("name") + "&timestamp1=" + parser.get("timestamp1")
                + "&timestamp2=" + parser.get("timestamp2"));
    }
    
    /**
     * Returns the diff between two config files as a list of single lines.
     * Takes the two timestamps and the name of the system property 
     * or the deleted job from the url parameters.
     * 
     * @return Differences between two config versions as list of lines.
     * @throws IOException If diff doesn't work or xml files can't be read.
     */
    public final List<Line> getLines() throws IOException {
        final String name = getRequestParameter("name");
        if ((name.contains(JobConfigHistoryConsts.DELETED_MARKER) && hasJobConfigurePermission())
                || hasConfigurePermission()) {
            final String timestamp1 = getRequestParameter("timestamp1");
            final String timestamp2 = getRequestParameter("timestamp2");

            final XmlFile configXml1 = getOldConfigXml(name, timestamp1);
            final String[] configXml1Lines = configXml1.asString().split("\\n");
            final XmlFile configXml2 = getOldConfigXml(name, timestamp2);
            final String[] configXml2Lines = configXml2.asString().split("\\n");
            
            final String diffAsString = getDiffAsString(configXml1.getFile(), configXml2.getFile(),
                    configXml1Lines, configXml2Lines);
            
            final List<String> diffLines = Arrays.asList(diffAsString.split("\n"));
            return getDiffLines(diffLines);
        } else {
            return Collections.emptyList();
        }
    }
    
    /**
     * Gets the version of the config.xml that was saved at a certain time.
     * 
     * @param name The name of the system property or deleted job.
     * @param timestamp The timestamp as String.
     * @return The config file as XmlFile.
     */
    protected XmlFile getOldConfigXml(String name, String timestamp) {
        final JobConfigHistory plugin = getPlugin();
        final String rootDir;
        File configFile = null;
        String path = null;

        if (checkParameters(name, timestamp)) {
            if (name.contains(JobConfigHistoryConsts.DELETED_MARKER)) {
                rootDir = plugin.getJobHistoryRootDir().getPath();
            } else {
                rootDir = plugin.getConfiguredHistoryRootDir().getPath();
                checkConfigurePermission();
            }
            path = rootDir + "/" + name + "/" + timestamp;
            configFile = plugin.getConfigFile(new File(path));
        }

        if (configFile == null) {
            LOG.finest("Unable to get history from: " + path);
            return null;
//            throw new IllegalArgumentException("Unable to get history from: " + path);
        } else {
            return new XmlFile(configFile);
        }
    }
    
    /**
     * Checks the url parameters 'name' and 'timestamp' and returns true if they are neither null 
     * nor suspicious.
     * @param name Name of deleted job or system property.
     * @param timestamp Timestamp of config change.
     * @return True if parameters are okay.
     */
    private boolean checkParameters(String name, String timestamp) {
        if (name == null || "null".equals(name) || !checkTimestamp(timestamp)) {
            return false;
        }
        if (name.contains("..")) {
            throw new IllegalArgumentException("Invalid directory name because of '..': " + name);
        }
        return true;
    }
    
    /**
     * Action when 'restore' button is pressed: Restore deleted project.
     * 
     * @param req Incoming StaplerRequest
     * @param rsp Outgoing StaplerResponse
     * @throws IOException If something goes wrong
     */
    public final void doRestore(StaplerRequest req, StaplerResponse rsp) throws IOException {
        getAccessControlledObject().checkPermission(AbstractProject.CONFIGURE);

        final String deletedName = req.getParameter("name");
        String newName = deletedName.split("_deleted_") [0];
        
        LOG.finest("deletedName: " + deletedName);
        LOG.finest("newName: " + newName);
        
        final List<ConfigInfo> configInfos = getSingleConfigs(deletedName);
        ConfigInfo lastChange = Collections.min(configInfos, ConfigInfoComparator.INSTANCE);
        String timestamp = lastChange.getDate();
        XmlFile configXml = getOldConfigXml(deletedName, timestamp);
        
        if (configXml == null) {
            lastChange = configInfos.get(1);
            timestamp = lastChange.getDate();
            configXml = getOldConfigXml(deletedName, timestamp);
        }
        
        final InputStream is = new ByteArrayInputStream(configXml.asString().getBytes("UTF-8"));
        
        //neues Projekt erzeugen
        final AbstractProject project;
        if (getHudson().getItem(newName) != null) {
            int i = 1;
            do {
                newName = newName + "_" + i;
            } while (getHudson().getItem(newName) != null);
        }

        project = (AbstractProject) getHudson().createProjectFromXML(newName, is);

        final FilePath oldFilePath = new FilePath(new File(getPlugin().getJobHistoryRootDir(), deletedName));
        final FilePath newFilePath = new FilePath(new File(getPlugin().getJobHistoryRootDir(), newName));
        try {
            oldFilePath.moveAllChildrenTo(newFilePath);
            oldFilePath.delete();
        } catch (InterruptedException ex) {
            LOG.info("Unable to move old history data " + oldFilePath + " to new directory " + newFilePath);
            LOG.info(ex.getMessage());
        } catch (IOException ex) {
            LOG.info("Unable to move old history data " + oldFilePath + " to new directory " + newFilePath);            
            LOG.info(ex.getMessage());
        }

        //change historydescr from created to restored
        final List<ConfigInfo> newConfigInfos = getSingleConfigs(newName);
        LOG.finest("newConfigInfos.size: " + newConfigInfos.size());
        lastChange = Collections.min(newConfigInfos, ConfigInfoComparator.INSTANCE);
        timestamp = lastChange.getDate();
        final String timestampedDir = getPlugin().getJobHistoryRootDir().getPath() + "/" + project.getName() + "/" + timestamp;
        
        if ("Created".equals(lastChange.getOperation())) {
            final XmlFile historyDescription = new XmlFile(new File(timestampedDir, JobConfigHistoryConsts.HISTORY_FILE));
            final HistoryDescr myDescr = new HistoryDescr(lastChange.getUser(), lastChange.getUserID(), "Restored", lastChange.getDate());
            historyDescription.write(myDescr);
        }

        rsp.sendRedirect(getHudson().getRootUrl() + project.getUrl());
    }
}
