package hudson.model;

import hudson.DescriptorExtensionList;
import hudson.Util;
import hudson.Extension;
import hudson.views.BuildButtonColumn;
import hudson.views.JobColumn;
import hudson.views.LastDurationColumn;
import hudson.views.LastFailureColumn;
import hudson.views.LastSuccessColumn;
import hudson.views.ListViewColumn;
import hudson.views.ListViewColumnDescriptor;
import hudson.views.StatusColumn;
import hudson.views.WeatherColumn;
import hudson.views.StatusColumn.DescriptorImpl;
import hudson.model.Descriptor.FormException;
import hudson.tasks.test.AbstractTestResultAction;
import hudson.triggers.Messages;
import hudson.triggers.SCMTrigger.SCMTriggerCause;
import hudson.util.CaseInsensitiveComparator;
import hudson.util.DescribableList;
import hudson.util.FormValidation;
import hudson.model.Cause.UserCause;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import javax.servlet.ServletException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import javax.servlet.ServletException;

/**
 * Displays {@link Job}s in a flat list view.
 * 
 * @author Kohsuke Kawaguchi
 */
public class OneAndOneView extends View implements Saveable {
    /**
     * List of job names. This is what gets serialized.
     */
    /* package */final SortedSet<String> jobNames = new TreeSet<String>(
            CaseInsensitiveComparator.INSTANCE);

    private DescribableList<ListViewColumn, Descriptor<ListViewColumn>> columns;

    /**
     * Include regex string.
     */
    private String includeRegex;

    /**
     * Compiled include pattern from the includeRegex string.
     */
    private transient Pattern includePattern;

    private String showType;

    public String getShowType() {
        return showType;
    }

    private static final String TYPEALL = "ALL";
    private static final String TYPESUCCESS = "Successes";
    private static final String TYPEFAILURE = "Failures";
    private static final String TYPEUNSTABLE = "Unstables";
    private static final String TYPEDISABLED = "Disabled";
    private static final String TYPETESTERRORS = "TestErrors";

    public String getTYPEALL() {
        return TYPEALL;
    }

    public String getTYPESUCCESS() {
        return TYPESUCCESS;
    }

    public String getTYPEFAILURE() {
        return TYPEFAILURE;
    }

    public String getTYPEUNSTABLE() {
        return TYPEUNSTABLE;
    }

    public String getTYPEDISABLED() {
        return TYPEDISABLED;
    }

    public String getTYPETESTERRORS() {
        return TYPETESTERRORS;
    }

    private String viewType;

    public String getViewType() {
        return viewType;
    }

    public static final String VIEWTYPEGLOBAL = "Global";
    public static final String VIEWTYPEADMIN = "Admin";
    public static final String VIEWTYPEUSER = "User";

    public String getVIEWTYPEGLOBAL() {
        return VIEWTYPEGLOBAL;
    }

    public String getVIEWTYPEADMIN() {
        return VIEWTYPEADMIN;
    }

    public String getVIEWTYPEUSER() {
        return VIEWTYPEUSER;
    }

    private String viewUserName;

    public String getViewUserName() {
        if (viewUserName == null)
            viewUserName = "";
        return viewUserName;
    }

    @DataBoundConstructor
    public OneAndOneView(String name) {

        super(name);
        initColumns();

    }
    
    public OneAndOneView(String name, ViewGroup owner) {
        this(name);
        this.owner = owner;
    }

    public void save() throws IOException {
        // persistence is a part of the owner.
        // due to the initialization timing issue, it can be null when this method is called.
        if (owner!=null)
            owner.save();
    }
    
    private Object readResolve() {
        if (includeRegex != null)
            includePattern = Pattern.compile(includeRegex);
        initColumns();
        return this;
    }

    protected void initColumns() {
        if (columns != null) {
            // already persisted
            return;
        }

        // OK, set up default list of columns:
        // create all instances
        ArrayList<ListViewColumn> r = new ArrayList<ListViewColumn>();
        DescriptorExtensionList<ListViewColumn, Descriptor<ListViewColumn>> all = ListViewColumn.all();
        ArrayList<Descriptor<ListViewColumn>> left = new ArrayList<Descriptor<ListViewColumn>>(all);

        for (Class<? extends ListViewColumn> d: DEFAULT_COLUMNS) {
            Descriptor<ListViewColumn> des = all.find(d);
            if (des  != null) {
                try {
                    r.add(des.newInstance(null, null));
                    left.remove(des);
                } catch (FormException e) {
                    LOGGER.log(Level.WARNING, "Failed to instantiate "+des.clazz,e);
                }
            }
        }
        for (Descriptor<ListViewColumn> d : left)
            try {
                if (d instanceof ListViewColumnDescriptor) {
                    ListViewColumnDescriptor ld = (ListViewColumnDescriptor) d;
                    if (!ld.shownByDefault())       continue;   // skip this
                }
                ListViewColumn lvc = d.newInstance(null, null);
                if (!lvc.shownByDefault())      continue; // skip this

                r.add(lvc);
            } catch (FormException e) {
                LOGGER.log(Level.WARNING, "Failed to instantiate "+d.clazz,e);
            }

        columns = new DescribableList<ListViewColumn, Descriptor<ListViewColumn>>(this,r);
    }


    /**
     * Returns the transient {@link Action}s associated with the top page.
     * 
     * @see Hudson#getActions()
     */
    public List<Action> getActions() {
        return Hudson.getInstance().getActions();
    }

    public Iterable<ListViewColumn> getColumns() {
        return columns;
    }

    /**
     * Returns a read-only view of all {@link Job}s in this view.
     * 
     * <p>
     * This method returns a separate copy each time to avoid concurrent
     * modification issue.
     */
    public synchronized List<TopLevelItem> getItems() {
        SortedSet<String> names = new TreeSet<String>(jobNames);

        if (includePattern != null) {
            for (TopLevelItem item : Hudson.getInstance().getItems()) {
                String itemName = item.getName();
                if (includePattern.matcher(itemName).matches()) {
                    names.add(itemName);
                }
            }
        }
        for (Iterator<String> iterator = names.iterator(); iterator.hasNext();) {
            String name = iterator.next();
            Result result = Result.NOT_BUILT;
            AbstractTestResultAction testResultAction = null;
            if (Hudson.getInstance().getItem(name) instanceof AbstractProject){
                if (((AbstractProject<?, ?>) Hudson.getInstance().getItem(name))
                        .getLastBuild() != null) {
                    testResultAction = ((AbstractProject<?, ?>) Hudson
                            .getInstance().getItem(name)).getLastBuild()
                            .getTestResultAction(); 
                    result = ((AbstractProject<?, ?>) Hudson.getInstance().getItem(
                            name)).getLastBuild().getResult();
                }
                boolean isDisabled = ((AbstractProject<?, ?>) Hudson.getInstance()
                        .getItem(name)).isDisabled();

                int failedTestCount = 0;
                if (testResultAction != null)
                    failedTestCount = testResultAction.getFailCount();
                if (showType != null) {
                    if ((showType.equals(TYPEFAILURE))
                            && ((result != Result.FAILURE) || isDisabled)) {
                        iterator.remove();
                    } else if ((showType.equals(TYPESUCCESS))
                            && ((result != Result.SUCCESS) || isDisabled)) {
                        iterator.remove();
                    } else if ((showType.equals(TYPEUNSTABLE))
                            && ((result != Result.UNSTABLE) || isDisabled)) {
                        iterator.remove();
                    } else if ((showType.equals(TYPEDISABLED)) && (!isDisabled)) {
                        iterator.remove();
                    } else if ((showType.equals(TYPETESTERRORS))
                            && (failedTestCount == 0)) {
                        iterator.remove();
                    }
                }
            }
            else{
               iterator.remove();
            }
        }

        List<TopLevelItem> items = new ArrayList<TopLevelItem>(names.size());
        for (String name : names) {
            TopLevelItem item = Hudson.getInstance().getItem(name);
            if (item != null)
                items.add(item);
        }
        return items;
    }

    public boolean contains(TopLevelItem item) {
        return jobNames.contains(item.getName());
    }

    public String getIncludeRegex() {
        return includeRegex;
    }

    public Item doCreateItem(StaplerRequest req, StaplerResponse rsp)
            throws IOException, ServletException {
        Item item = Hudson.getInstance().doCreateItem(req, rsp);
        if (item != null) {
            jobNames.add(item.getName());
            owner.save();
        }
        return item;
    }

    @Override
    public synchronized void onJobRenamed(Item item, String oldName,
            String newName) {
        if (jobNames.remove(oldName) && newName != null)
            jobNames.add(newName);
    }

    /**
     * Build all jobs of this view.
     * 
     * @throws ServletException
     */
    public synchronized void doBuild(StaplerRequest req, StaplerResponse rsp)
            throws IOException, ServletException {

        for (TopLevelItem item : this.getItems()) {

            ((AbstractProject<?, ?>) item).scheduleBuild(new UserCause());
        }
        rsp.sendRedirect(".");
    }

    /**
     * Handles the configuration submission.
     * 
     * Load view-specific properties here.
     */
    @Override
    protected void submit(StaplerRequest req) throws ServletException,
            FormException {
        jobNames.clear();
        for (TopLevelItem item : Hudson.getInstance().getItems()) {
            if (req.getParameter(item.getName()) != null)
                jobNames.add(item.getName());
        }

        if (req.getParameter("showType") != null)
            showType = req.getParameter("showType");
        else
            showType = TYPEALL;

        if (req.getParameter("viewType") != null)
            viewType = req.getParameter("viewType");
        else
            viewType = VIEWTYPEGLOBAL;

        if (req.getParameter("viewUserName") != null)
            viewUserName = req.getParameter("viewUserName");
        else
            viewUserName = "";

        if (req.getParameter("useincluderegex") != null) {
            includeRegex = Util.nullify(req.getParameter("includeRegex"));
            includePattern = Pattern.compile(includeRegex);
        } else {
            includeRegex = null;
            includePattern = null;
        }
        if (columns == null) {
            columns = new DescribableList<ListViewColumn, Descriptor<ListViewColumn>>(
                    Saveable.NOOP);
        }
        columns.rebuildHetero(req, req.getSubmittedForm(), Hudson.getInstance()
                .getDescriptorList(ListViewColumn.class), "columns");

    }

    @Extension
    public static final class DescriptorImpl extends ViewDescriptor {

        public String getDisplayName() {
            return "1&1 View";
        }

        /**
         * Checks if the include regular expression is valid.
         */
        public FormValidation doCheckIncludeRegex(@QueryParameter String value)
                throws IOException, ServletException, InterruptedException {
            String v = Util.fixEmpty(value);
            if (v != null) {
                try {
                    Pattern.compile(v);
                } catch (PatternSyntaxException pse) {
                    return FormValidation.error(pse.getMessage());
                }
            }
            return FormValidation.ok();
        }
    }
    
    public static List<ListViewColumn> getDefaultColumns() {
        ArrayList<ListViewColumn> r = new ArrayList<ListViewColumn>();
        DescriptorExtensionList<ListViewColumn, Descriptor<ListViewColumn>> all = ListViewColumn.all();
        for (Class<? extends ListViewColumn> t : DEFAULT_COLUMNS) {
            Descriptor<ListViewColumn> d = all.find(t);
            if (d  != null) {
                try {
                    r.add (d.newInstance(null, null));
                } catch (FormException e) {
                    LOGGER.log(Level.WARNING, "Failed to instantiate "+d.clazz,e);
                }
            }
        }
        return Collections.unmodifiableList(r);
    }
    public static class ViewCause extends Cause {
        @Override
        public String getShortDescription() {
        	 if (Hudson.getAuthentication().getName() != null) {
                 return Hudson.getAuthentication().getName()
                         + " triggered this build via complete view build of ";
                         //+ "View"
                 } else {
                 return "Unknown triggered this build via complete view build of ";
                         //+ getDisplayName();
             }
           
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof ViewCause;
        }

        @Override
        public int hashCode() {
            return 7;
        }
    }


    private static final Logger LOGGER = Logger.getLogger(ListView.class.getName());

    /**
     * Traditional column layout before the {@link ListViewColumn} becomes extensible.
     */
    private static final List<Class<? extends ListViewColumn>> DEFAULT_COLUMNS =  Arrays.asList(
        StatusColumn.class,
        WeatherColumn.class,
        BuildButtonColumn.class,
        JobColumn.class,
        LastSuccessColumn.class,
        LastFailureColumn.class,
        LastDurationColumn.class
    );
}
