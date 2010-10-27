/*
 * The MIT License
 * 
 * Copyright (c) 2004-2010, Sun Microsystems, Inc., Kohsuke Kawaguchi,
 * Martin Eigenbrodt. Seiji Sogabe, Alan Harder
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.model;

import hudson.security.ACL;
import hudson.security.NotSerilizableSecurityContext;
import org.acegisecurity.context.SecurityContext;
import org.acegisecurity.context.SecurityContextHolder;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.graph_layouter.Layout;
import org.kohsuke.graph_layouter.Navigator;
import org.kohsuke.graph_layouter.Direction;

import javax.servlet.ServletOutputStream;
import javax.imageio.ImageIO;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.Stack;
import java.io.IOException;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Rectangle;
import java.awt.Graphics2D;
import java.awt.Color;
import java.awt.Point;
import java.awt.HeadlessException;
import java.awt.FontMetrics;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;

/**
 * Maintains the build dependencies between {@link AbstractProject}s
 * for efficient dependency computation.
 *
 * <p>
 * The "master" data of dependencies are owned/persisted/maintained by
 * individual {@link AbstractProject}s, but because of that, it's relatively
 * slow  to compute backward edges.
 *
 * <p>
 * This class builds the complete bi-directional dependency graph
 * by collecting information from all {@link AbstractProject}s.
 *
 * <p>
 * Once built, {@link DependencyGraph} is immutable, and every time
 * there's a change (which is relatively rare), a new instance
 * will be created. This eliminates the need of synchronization.
 *
 * @see Hudson#getDependencyGraph() 
 * @author Kohsuke Kawaguchi
 */
public final class DependencyGraph implements Comparator<AbstractProject> {

    private Map<AbstractProject, List<DependencyGroup>> forward = new HashMap<AbstractProject, List<DependencyGroup>>();
    private Map<AbstractProject, List<DependencyGroup>> backward = new HashMap<AbstractProject, List<DependencyGroup>>();

    private boolean built;

    /**
     * Builds the dependency graph.
     */
    public DependencyGraph() {
        // Set full privileges while computing to avoid missing any projects the current user cannot see.
        // Use setContext (NOT getContext().setAuthentication()) so we don't affect concurrent threads for same HttpSession.
        SecurityContext saveCtx = SecurityContextHolder.getContext();
        try {
            NotSerilizableSecurityContext system = new NotSerilizableSecurityContext();
            system.setAuthentication(ACL.SYSTEM);
            SecurityContextHolder.setContext(system);
            for( AbstractProject p : Hudson.getInstance().getAllItems(AbstractProject.class) )
                p.buildDependencyGraph(this);

            forward = finalize(forward);
            backward = finalize(backward);

            built = true;
        } finally {
            SecurityContextHolder.setContext(saveCtx);
        }
    }

    /**
     * Special constructor for creating an empty graph
     */
    private DependencyGraph(boolean dummy) {
        forward = backward = Collections.emptyMap();
        built = true;
    }

    /**
     * Gets all the immediate downstream projects (IOW forward edges) of the given project.
     *
     * @return
     *      can be empty but never null.
     */
    public List<AbstractProject> getDownstream(AbstractProject p) {
        return get(forward,p,false);
    }

    /**
     * Gets all the immediate upstream projects (IOW backward edges) of the given project.
     *
     * @return
     *      can be empty but never null.
     */
    public List<AbstractProject> getUpstream(AbstractProject p) {
        return get(backward,p,true);
    }

    private List<AbstractProject> get(Map<AbstractProject, List<DependencyGroup>> map, AbstractProject src, boolean up) {
        List<DependencyGroup> v = map.get(src);
        if(v==null) return Collections.emptyList();
        List<AbstractProject> result = new ArrayList<AbstractProject>(v.size());
        for (Dependency d : v) result.add(up ? d.getUpstreamProject() : d.getDownstreamProject());
        return result;
    }

    /**
     * @since 1.341
     */
    public List<Dependency> getDownstreamDependencies(AbstractProject p) {
        return get(forward,p);
    }

    /**
     * @since 1.341
     */
    public List<Dependency> getUpstreamDependencies(AbstractProject p) {
        return get(backward,p);
    }

    private List<Dependency> get(Map<AbstractProject, List<DependencyGroup>> map, AbstractProject src) {
        List<DependencyGroup> v = map.get(src);
        if(v!=null) return Collections.<Dependency>unmodifiableList(v);
        else        return Collections.emptyList();
    }

    /**
     * @deprecated since 1.341; use {@link #addDependency(Dependency)}
     */
    @Deprecated
    public void addDependency(AbstractProject upstream, AbstractProject downstream) {
        addDependency(new Dependency(upstream,downstream));
    }

    /**
     * Called during the dependency graph build phase to add a dependency edge.
     */
    public void addDependency(Dependency dep) {
        if(built)
            throw new IllegalStateException();
        add(forward,dep.getUpstreamProject(),dep);
        add(backward,dep.getDownstreamProject(),dep);
    }

    /**
     * @deprecated since 1.341
     */
    @Deprecated
    public void addDependency(AbstractProject upstream, Collection<? extends AbstractProject> downstream) {
        for (AbstractProject p : downstream)
            addDependency(upstream,p);
    }

    /**
     * @deprecated since 1.341
     */
    @Deprecated
    public void addDependency(Collection<? extends AbstractProject> upstream, AbstractProject downstream) {
        for (AbstractProject p : upstream)
            addDependency(p,downstream);
    }

    /**
     * Lists up {@link DependecyDeclarer} from the collection and let them builds dependencies.
     */
    public void addDependencyDeclarers(AbstractProject upstream, Collection<?> possibleDependecyDeclarers) {
        for (Object o : possibleDependecyDeclarers) {
            if (o instanceof DependecyDeclarer) {
                DependecyDeclarer dd = (DependecyDeclarer) o;
                dd.buildDependencyGraph(upstream,this);
            }
        }
    }

    /**
     * Returns true if a project has a non-direct dependency to another project.
     * <p>
     * A non-direct dependency is a path of dependency "edge"s from the source to the destination,
     * where the length is greater than 1.
     */
    public boolean hasIndirectDependencies(AbstractProject src, AbstractProject dst) {
        Set<AbstractProject> visited = new HashSet<AbstractProject>();
        Stack<AbstractProject> queue = new Stack<AbstractProject>();

        queue.addAll(getDownstream(src));
        queue.remove(dst);

        while(!queue.isEmpty()) {
            AbstractProject p = queue.pop();
            if(p==dst)
                return true;
            if(visited.add(p))
                queue.addAll(getDownstream(p));
        }

        return false;
    }

    /**
     * Gets all the direct and indirect upstream dependencies of the given project.
     */
    public Set<AbstractProject> getTransitiveUpstream(AbstractProject src) {
        return getTransitive(backward,src,true);
    }

    /**
     * Gets all the direct and indirect downstream dependencies of the given project.
     */
    public Set<AbstractProject> getTransitiveDownstream(AbstractProject src) {
        return getTransitive(forward,src,false);
    }

    private Set<AbstractProject> getTransitive(Map<AbstractProject, List<DependencyGroup>> direction, AbstractProject src, boolean up) {
        Set<AbstractProject> visited = new HashSet<AbstractProject>();
        Stack<AbstractProject> queue = new Stack<AbstractProject>();

        queue.add(src);

        while(!queue.isEmpty()) {
            AbstractProject p = queue.pop();

            for (AbstractProject child : get(direction,p,up)) {
                if(visited.add(child))
                    queue.add(child);
            }
        }

        return visited;
    }

    private void add(Map<AbstractProject, List<DependencyGroup>> map, AbstractProject key, Dependency dep) {
        List<DependencyGroup> set = map.get(key);
        if(set==null) {
            set = new ArrayList<DependencyGroup>();
            map.put(key,set);
        }
        for (ListIterator<DependencyGroup> it = set.listIterator(); it.hasNext();) {
            DependencyGroup d = it.next();
            // Check for existing edge that connects the same two projects:
            if (d.getUpstreamProject()==dep.getUpstreamProject() && d.getDownstreamProject()==dep.getDownstreamProject()) {
                d.add(dep);
                return;
            }
        }
        // Otherwise add to list:
        set.add(new DependencyGroup(dep));
    }

    private Map<AbstractProject, List<DependencyGroup>> finalize(Map<AbstractProject, List<DependencyGroup>> m) {
        for (Entry<AbstractProject, List<DependencyGroup>> e : m.entrySet()) {
            Collections.sort( e.getValue(), NAME_COMPARATOR );
            e.setValue( Collections.unmodifiableList(e.getValue()) );
        }
        return Collections.unmodifiableMap(m);
    }

    /**
     * Experimental visualization of project dependencies.
     */
    public void doGraph( StaplerRequest req, StaplerResponse rsp ) throws IOException {
        // Require admin permission for now (avoid exposing project names with restricted permissions)
        Hudson.getInstance().checkPermission(Hudson.ADMINISTER);
        try {

            // creates a dummy graphics just so that we can measure font metrics
            BufferedImage emptyImage = new BufferedImage(1,1, BufferedImage.TYPE_INT_RGB );
            Graphics2D graphics = emptyImage.createGraphics();
            graphics.setFont(FONT);
            final FontMetrics fontMetrics = graphics.getFontMetrics();

            // TODO: timestamp check
            Layout<AbstractProject> layout = new Layout<AbstractProject>(new Navigator<AbstractProject>() {
                public Collection<AbstractProject> vertices() {
                    // only include projects that have some dependency
                    List<AbstractProject> r = new ArrayList<AbstractProject>();
                    for (AbstractProject p : Hudson.getInstance().getAllItems(AbstractProject.class)) {
                        if(!getDownstream(p).isEmpty() || !getUpstream(p).isEmpty())
                            r.add(p);
                    }
                    return r;
                }

                public Collection<AbstractProject> edge(AbstractProject p) {
                    return getDownstream(p);
                }

                public Dimension getSize(AbstractProject p) {
                    int w = fontMetrics.stringWidth(p.getDisplayName()) + MARGIN*2;
                    return new Dimension(w, fontMetrics.getHeight() + MARGIN*2);
                }
            }, Direction.LEFTRIGHT);

            Rectangle area = layout.calcDrawingArea();
            area.grow(4,4); // give it a bit of margin
            BufferedImage image = new BufferedImage(area.width, area.height, BufferedImage.TYPE_INT_RGB );
            Graphics2D g2 = image.createGraphics();
            g2.setTransform(AffineTransform.getTranslateInstance(-area.x,-area.y));
            g2.setPaint(Color.WHITE);
            g2.fill(area);
            g2.setFont(FONT);

            g2.setPaint(Color.BLACK);
            for( AbstractProject p : layout.vertices() ) {
                final Point sp = center(layout.vertex(p));

                for (AbstractProject q : layout.edges(p)) {
                    Point cur=sp;
                    for( Point pt : layout.edge(p,q) ) {
                        g2.drawLine(cur.x, cur.y, pt.x, pt.y);
                        cur=pt;
                    }

                    final Point ep = center(layout.vertex(q));
                    g2.drawLine(cur.x, cur.y, ep.x, ep.y);
                }
            }

            int diff = fontMetrics.getAscent()+fontMetrics.getLeading()/2;
            
            for( AbstractProject p : layout.vertices() ) {
                Rectangle r = layout.vertex(p);
                g2.setPaint(Color.WHITE);
                g2.fillRect(r.x, r.y, r.width, r.height);
                g2.setPaint(Color.BLACK);
                g2.drawRect(r.x, r.y, r.width, r.height);
                g2.drawString(p.getDisplayName(), r.x+MARGIN, r.y+MARGIN+ diff);
            }

            rsp.setContentType("image/png");
            ServletOutputStream os = rsp.getOutputStream();
            ImageIO.write(image, "PNG", os);
            os.close();
        } catch(HeadlessException e) {
            // not available. send out error message
            rsp.sendRedirect2(req.getContextPath()+"/images/headless.png");
        }
    }

    private Point center(Rectangle r) {
        return new Point(r.x+r.width/2,r.y+r.height/2);
    }

    private static final Font FONT = new Font("TimesRoman", Font.PLAIN, 10);
    /**
     * Margins between the project name and its bounding box.
     */
    private static final int MARGIN = 4;


    private static final Comparator<Dependency> NAME_COMPARATOR = new Comparator<Dependency>() {
        public int compare(Dependency lhs, Dependency rhs) {
            int cmp = lhs.getUpstreamProject().getName().compareTo(rhs.getUpstreamProject().getName());
            return cmp != 0 ? cmp : lhs.getDownstreamProject().getName().compareTo(rhs.getDownstreamProject().getName());
        }
    };

    public static final DependencyGraph EMPTY = new DependencyGraph(false);

    /**
     * Compare to Projects based on the topological order defined by this Dependency Graph
     */
    public int compare(AbstractProject o1, AbstractProject o2) {
        Set<AbstractProject> o1sdownstreams = getTransitiveDownstream(o1);
        Set<AbstractProject> o2sdownstreams = getTransitiveDownstream(o2);
        if (o1sdownstreams.contains(o2)) {
            if (o2sdownstreams.contains(o1)) return 0; else return 1;                       
        } else {
            if (o2sdownstreams.contains(o1)) return -1; else return 0; 
        }               
    }

    /**
     * Represents an edge in the dependency graph.
     * @since 1.341
     */
    public static class Dependency {
        private AbstractProject upstream, downstream;

        public Dependency(AbstractProject upstream, AbstractProject downstream) {
            this.upstream = upstream;
            this.downstream = downstream;
        }

        public AbstractProject getUpstreamProject() {
            return upstream;
        }

        public AbstractProject getDownstreamProject() {
            return downstream;
        }

        /**
         * Decide whether build should be triggered and provide any Actions for the build.
         * Default implementation always returns true (for backward compatibility), and
         * adds no Actions. Subclasses may override to control how/if the build is triggered.
         * @param build Build of upstream project that just completed
         * @param listener For any error/log output
         * @param actions Add Actions for the triggered build to this list; never null
         * @return True to trigger a build of the downstream project
         */
        public boolean shouldTriggerBuild(AbstractBuild build, TaskListener listener,
                                          List<Action> actions) {
            return true;
        }

        /**
         * Does this method point to itself?
         */
        public boolean pointsItself() {
            return upstream==downstream;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == null) return false;
            if (getClass() != obj.getClass()) return false;

            final Dependency that = (Dependency) obj;
            return this.upstream == that.upstream || this.downstream == that.downstream;
        }

        @Override
        public int hashCode() {
            int hash = 7;
            hash = 23 * hash + this.upstream.hashCode();
            hash = 23 * hash + this.downstream.hashCode();
            return hash;
        }
    }

    /**
     * Collect multiple dependencies between the same two projects.
     */
    private static class DependencyGroup extends Dependency {
        private Set<Dependency> group = new LinkedHashSet<Dependency>();

        DependencyGroup(Dependency first) {
            super(first.getUpstreamProject(), first.getDownstreamProject());
            group.add(first);
        }

        void add(Dependency next) {
            group.add(next);
        }

        @Override
        public boolean shouldTriggerBuild(AbstractBuild build, TaskListener listener,
                                          List<Action> actions) {
            List<Action> check = new ArrayList<Action>();
            for (Dependency d : group) {
                if (d.shouldTriggerBuild(build, listener, check)) {
                    actions.addAll(check);
                    return true;
                } else
                    check.clear();
            }
            return false;
        }
    }
}
