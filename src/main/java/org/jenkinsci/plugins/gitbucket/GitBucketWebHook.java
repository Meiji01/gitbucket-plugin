/*
 * The MIT License
 *
 * Copyright (c) 2013, Seiji Sogabe
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
package org.jenkinsci.plugins.gitbucket;

import hudson.Extension;
import hudson.model.Job;
import hudson.model.UnprotectedRootAction;
import hudson.plugins.git.GitSCM;
import hudson.scm.SCM;
import hudson.security.ACL;
import hudson.security.csrf.CrumbExclusion;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.acegisecurity.Authentication;
import org.acegisecurity.context.SecurityContextHolder;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;
import org.jenkinsci.plugins.multiplescms.MultiSCM;
import java.lang.reflect.Method;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.interceptor.RequirePOST;

/**
 * Receives GitBucket WebHook.
 *
 * @author sogabe
 */
@Extension
public class GitBucketWebHook implements UnprotectedRootAction {

    public static final String WEBHOOK_URL = "gitbucket-webhook";

    @Override
    public String getIconFileName() {
        return null;
    }

    @Override
    public String getDisplayName() {
        return null;
    }

    @Override
    public String getUrlName() {
        return WEBHOOK_URL;
    }

    @RequirePOST
    public void doIndex(StaplerRequest req) {
        String event = req.getHeader("X-Github-Event");
        LOGGER.log(Level.FINE, "WebHook called. event: {0}", event);
        if (!"push".equals(event)) {
            LOGGER.log(Level.FINE, "Only push event can be accepted.");
            return;
        }

        String payload = req.getParameter("payload");
        if (payload == null) {
            throw new IllegalArgumentException(
                    "Not intended to be browsed interactively (must specify payload parameter)");
        }

        processPayload(payload);
    }

    private void processPayload(String payload) {
        JSONObject json = JSONObject.fromObject(payload);
        LOGGER.log(Level.FINE, "payload: {0}", json.toString(4));

        GitBucketPushRequest req = GitBucketPushRequest.create(json);
        String repositoryUrl = getRepositoryUrl(req);
        if (repositoryUrl == null) {
            LOGGER.log(Level.WARNING, "No repository url found.");
            return;
        }

        Authentication old = SecurityContextHolder.getContext().getAuthentication();
        SecurityContextHolder.getContext().setAuthentication(ACL.SYSTEM);
        try {
            for (Job<?, ?> job : Jenkins.getInstance().getAllItems(Job.class)) {
                GitBucketPushTrigger trigger = getTriggerFromJob(job);
                if (trigger == null) {
                    continue;
                }
                List<String> urls = RepositoryUrlCollector.collect(job);
                if (urls.contains(repositoryUrl.toLowerCase())) {
                    trigger.onPost(req);
                }
            }
        } finally {
            SecurityContextHolder.getContext().setAuthentication(old);
        }
    }

    private static GitBucketPushTrigger getTriggerFromJob(Job<?, ?> job) {
        // Try AbstractProject.getTrigger first
        try {
            if (job instanceof hudson.model.AbstractProject) {
                hudson.model.AbstractProject<?, ?> ap = (hudson.model.AbstractProject<?, ?>) job;
                return ap.getTrigger(GitBucketPushTrigger.class);
            }
        } catch (NoClassDefFoundError e) {
            // ignore
        }

        // Try calling getTrigger(Class) reflectively (for ParameterizedJobMixIn.ParameterizedJob)
        try {
            Method getTrigger = job.getClass().getMethod("getTrigger", Class.class);
            Object t = getTrigger.invoke(job, GitBucketPushTrigger.class);
            if (t instanceof GitBucketPushTrigger) {
                return (GitBucketPushTrigger) t;
            }
        } catch (NoSuchMethodException nsme) {
            // fall through
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Failed to call getTrigger reflectively: {0}", e.toString());
        }

        // Try inspecting a getTriggers() map reflectively (for WorkflowJob and other pipeline jobs)
        try {
            Method getTriggers = job.getClass().getMethod("getTriggers");
            Object triggers = getTriggers.invoke(job);
            if (triggers instanceof java.util.Map) {
                java.util.Map<?, ?> triggerMap = (java.util.Map<?, ?>) triggers;
                // Check if the key is the descriptor or the class
                for (java.util.Map.Entry<?, ?> entry : triggerMap.entrySet()) {
                    Object value = entry.getValue();
                    if (value instanceof GitBucketPushTrigger) {
                        return (GitBucketPushTrigger) value;
                    }
                }
            }
        } catch (NoSuchMethodException nsme) {
            // ignore
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Failed to inspect triggers map: {0}", e.toString());
        }

        return null;
    }

    private String getRepositoryUrl(GitBucketPushRequest req) {
        // current gutbucket returns "clone_url", but old one returs "url",
        // so we check both for compatbility older than gitbucket 3.1
        String url = req.getRepository().getUrl();
        // gitbucket 3.1 or later
        String cloneUrl = req.getRepository().getCloneUrl();
        return (cloneUrl != null) ? cloneUrl : url;
    }

    private static class RepositoryUrlCollector {

        public static List<String> collect(Job<?, ?> job) {
            List<String> urls = new ArrayList<String>();
            // For traditional projects (AbstractProject), try to read SCM directly.
            if (job instanceof hudson.model.AbstractProject) {
                hudson.model.AbstractProject<?, ?> ap = (hudson.model.AbstractProject<?, ?>) job;
                SCM scm = ap.getScm();
                if (scm instanceof GitSCM) {
                    urls.addAll(collect((GitSCM) scm));
                } else if (Jenkins.getInstance().getPlugin("multiple-scms") != null
                        && scm instanceof MultiSCM) {
                    MultiSCM multiSCM = (MultiSCM) scm;
                    List<SCM> scms = multiSCM.getConfiguredSCMs();
                    for (SCM s : scms) {
                        if (s instanceof GitSCM) {
                            urls.addAll(collect((GitSCM) s));
                        }
                    }
                }
            } else {
                // For Pipeline (WorkflowJob), attempt to extract SCM from its definition via reflection
                try {
                    Class<?> workflowJobClass = Class.forName("org.jenkinsci.plugins.workflow.job.WorkflowJob");
                    if (workflowJobClass.isInstance(job)) {
                        Method getDefinition = workflowJobClass.getMethod("getDefinition");
                        Object def = getDefinition.invoke(job);
                        if (def != null) {
                            try {
                                Method getScm = def.getClass().getMethod("getScm");
                                Object scm = getScm.invoke(def);
                                if (scm instanceof GitSCM) {
                                    urls.addAll(collect((GitSCM) scm));
                                }
                            } catch (NoSuchMethodException nsme) {
                                // definition has no getScm; ignore
                            }
                        }

                        // additionally, check if this job is part of a multibranch project by inspecting its parent
                        try {
                            Method getParent = job.getClass().getMethod("getParent");
                            Object parent = getParent.invoke(job);
                            if (parent != null) {
                                try {
                                    Class<?> mbClass = Class.forName("org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject");
                                    if (mbClass.isInstance(parent)) {
                                        // try to get sources via getSCMSources or getSources
                                        try {
                                            Method getSources = parent.getClass().getMethod("getSCMSources");
                                            Object sources = getSources.invoke(parent);
                                            // sources could be a List of SCMSourceOwners or SCMSource objects
                                            inspectScmSources(sources, urls);
                                        } catch (NoSuchMethodException nsme2) {
                                            try {
                                                Method getSources2 = parent.getClass().getMethod("getSources");
                                                Object sources = getSources2.invoke(parent);
                                                inspectScmSources(sources, urls);
                                            } catch (NoSuchMethodException nsme3) {
                                                // give up
                                            }
                                        }
                                    }
                                } catch (ClassNotFoundException cnfe) {
                                    // multibranch plugin not installed
                                }
                            }
                        } catch (NoSuchMethodException nsme) {
                            // ignore
                        }
                    }
                } catch (ClassNotFoundException e) {
                    // workflow plugin is not installed; nothing to do
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Failed to collect SCM from workflow job: {0}", e.toString());
                }

                // Fallback: try to read SCMs from the most recent build (useful for inline Pipeline scripts).
                try {
                    Method getLastBuild = job.getClass().getMethod("getLastBuild");
                    Object lastBuild = getLastBuild.invoke(job);
                    if (lastBuild != null) {
                        inspectScmFromBuild(lastBuild, urls);
                    }
                } catch (NoSuchMethodException nsme) {
                    // ignore
                } catch (Exception e) {
                    LOGGER.log(Level.FINE, "Failed to inspect SCMs from last build: {0}", e.toString());
                }
            }
            return urls;
        }

        private static void inspectScmSources(Object sourcesObj, List<String> urls) {
            if (sourcesObj == null) {
                return;
            }
            try {
                Iterable iterable = null;
                if (sourcesObj instanceof Iterable) {
                    iterable = (Iterable) sourcesObj;
                } else if (sourcesObj.getClass().isArray()) {
                    // convert array to iterable
                    java.util.List list = new java.util.ArrayList();
                    for (Object o : (Object[]) sourcesObj) {
                        list.add(o);
                    }
                    iterable = list;
                }
                if (iterable == null) {
                    return;
                }

                for (Object s : iterable) {
                    if (s == null) continue;
                    // SCMSource wrappers sometimes hold SCMSourceBinding with 'source' field
                    try {
                        // direct SCMSource (e.g., GitSCMSource)
                        Method getSource = s.getClass().getMethod("getSource");
                        Object src = getSource.invoke(s);
                        s = (src != null) ? src : s;
                    } catch (NoSuchMethodException e) {
                        // ignore
                    }

                    // Try common methods on GitSCMSource: getRemote, getRepoOwner, getRepo, etc.
                    try {
                        Method getRemote = s.getClass().getMethod("getRemote");
                        Object remote = getRemote.invoke(s);
                        if (remote != null) {
                            urls.add(remote.toString().trim().toLowerCase());
                            continue;
                        }
                    } catch (NoSuchMethodException e) {
                        // ignore
                    }
                    try {
                        Method getRepo = s.getClass().getMethod("getRepo");
                        Object repo = getRepo.invoke(s);
                        if (repo != null) {
                            urls.add(repo.toString().trim().toLowerCase());
                            continue;
                        }
                    } catch (NoSuchMethodException e) {
                        // ignore
                    }
                }
            } catch (Exception e) {
                LOGGER.log(Level.FINE, "Failed to inspect SCM sources: {0}", e.toString());
            }
        }

        private static List<String> collect(GitSCM scm) {
            List<String> urls = new ArrayList<String>();
            for (RemoteConfig config : scm.getRepositories()) {
                for (URIish uri : config.getURIs()) {
                    uri = uri.setUser(null).setPass(null); // ignore user and password
                    String u = uri.toString();
                    urls.add(u.trim().toLowerCase());
                }
            }
            return urls;
        }

        private static void inspectScmFromBuild(Object build, List<String> urls) {
            if (build == null) {
                return;
            }
            try {
                // Try getSCMs() first (WorkflowRun exposes this).
                try {
                    Method getSCMs = build.getClass().getMethod("getSCMs");
                    Object scmsObj = getSCMs.invoke(build);
                    if (scmsObj instanceof Iterable) {
                        for (Object scmObj : (Iterable) scmsObj) {
                            if (scmObj instanceof GitSCM) {
                                urls.addAll(collect((GitSCM) scmObj));
                            }
                        }
                        return;
                    }
                } catch (NoSuchMethodException nsme) {
                    // ignore
                }

                // Fallback: try getSCM().
                try {
                    Method getSCM = build.getClass().getMethod("getSCM");
                    Object scmObj = getSCM.invoke(build);
                    if (scmObj instanceof GitSCM) {
                        urls.addAll(collect((GitSCM) scmObj));
                    }
                } catch (NoSuchMethodException nsme) {
                    // ignore
                }
            } catch (Exception e) {
                LOGGER.log(Level.FINE, "Failed to inspect SCMs from build: {0}", e.toString());
            }
        }
    }

    @Extension
    public static class GitBucketWebHookCrumbExclusion extends CrumbExclusion {

        @Override
        public boolean process(HttpServletRequest req, HttpServletResponse resp,
                FilterChain chain) throws IOException, ServletException {
            String pathInfo = req.getPathInfo();
            if (pathInfo != null && pathInfo.equals(getExclusionPath())) {
                chain.doFilter(req, resp);
                return true;
            }
            return false;
        }

        private String getExclusionPath() {
            return '/' + WEBHOOK_URL + '/';
        }
    }

    private static final Logger LOGGER = Logger.getLogger(GitBucketWebHook.class.getName());
}
