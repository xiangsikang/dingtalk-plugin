package com.xiangsikang.dingtalkplugin;

import hudson.Extension;
import hudson.Launcher;
import hudson.ProxyConfiguration;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Project;
import hudson.plugins.git.GitSCM;
import hudson.plugins.git.UserRemoteConfig;
import hudson.scm.SCM;
import hudson.scm.SubversionSCM;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import hudson.util.ReflectionUtils;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.UsernamePasswordCredentials;
import org.apache.commons.httpclient.auth.AuthScope;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.httpclient.methods.StringRequestEntity;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class DingTalkNotifier extends Notifier {
    private Logger logger = Logger.getLogger(DingTalkNotifier.class);

    public final String accessToken;

    @DataBoundConstructor
    public DingTalkNotifier(String accessToken) {
        this.accessToken = accessToken;
    }

    @Override
    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    private String getDingTalkToken() {
        return StringUtils.isNotEmpty(accessToken) ? accessToken : getDescriptor().globalAccessToken;
    }

    private String getDingTaskUrl() {
        return "https://oapi.dingtalk.com/robot/send?access_token=" + getDingTalkToken();
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
        try {
            if (StringUtils.isEmpty(getDingTalkToken())) {
                return true;
            }

            File file = new File(Jenkins.getInstance().getRootPath() + "/dingtalk-" + build.getResult().toString().toLowerCase() + ".json");
            if (!file.exists()) {
                return true;
            }
            String content = FileUtils.readFileToString(file, "UTF-8");
            if (StringUtils.isEmpty(content)) {
                return true;
            }
            Map<String, String> contentVariables = new HashMap<String, String>();

            Map<String, String> buildVariables = build.getBuildVariables();
            if (null != build.getBuildVariables()) {
                for (String key : buildVariables.keySet()) {
                    contentVariables.put("param." + key, buildVariables.get(key));
                }
            }

            Project project = (Project) build.getProject();
            Method[] methods = ReflectionUtils.getAllDeclaredMethods(Project.class);
            for (Method method : methods) {
                if (method.getParameterTypes().length == 0 && isSupported(method.getReturnType())) {
                    int start = method.getName().startsWith("is") ? 0 : ((method.getName().startsWith("get")) ? 3 : -1);
                    if (start < 0) {
                        continue;
                    }
                    String value = invokeMethod(method, project);
                    if (null != value) {
                        String fieldName = method.getName().substring(start, start + 1).toLowerCase() + method.getName().substring(start + 1);
                        contentVariables.put("project." + fieldName, value);
                    }
                }
            }

            SCM scm = project.getScm();
            if (null != scm) {
                if (scm instanceof GitSCM) {
                    if (CollectionUtils.isNotEmpty(((GitSCM) scm).getUserRemoteConfigs())) {
                        UserRemoteConfig remoteConfig = ((GitSCM) scm).getUserRemoteConfigs().get(0);
                        contentVariables.put("git.name", remoteConfig.getName());
                        contentVariables.put("git.url", remoteConfig.getUrl().replaceAll("\\.git$", ""));
                        contentVariables.put("git.cloneUrl", remoteConfig.getUrl());
                        contentVariables.put("git.refspec", remoteConfig.getRefspec());
                    }
                } else if (scm instanceof SubversionSCM) {
                    if (ArrayUtils.isNotEmpty(((SubversionSCM) scm).getLocations())) {
                        SubversionSCM.ModuleLocation location = ((SubversionSCM) scm).getLocations()[0];
                        contentVariables.put("svn.url", location.getURL());
                    }
                }
            }

            for (String key : contentVariables.keySet()) {
                String value = contentVariables.get(key);
                System.out.println("===" + key + "=" + value);
                if (null != value) {
                    content = content.replace("${" + key + "}", value);
                }
            }

            sendMessage(content);
        } catch (Exception e) {
            e.printStackTrace();
            logger.error(e);
        }

        return true;
    }

    private boolean isSupported(Class c) {
        return CharSequence.class.isAssignableFrom(c) || Number.class.isAssignableFrom(c)
                || Arrays.asList(int.class, boolean.class, long.class, double.class, float.class, short.class).contains(c);
    }

    private String invokeMethod(Method method, Object target) {
        try {
            return String.valueOf(ReflectionUtils.invokeMethod(method, target));
        } catch (Exception e) {
            return null;
        }
    }

    private void sendMessage(String content) {
        HttpClient client = getHttpClient();
        PostMethod post = new PostMethod(getDingTaskUrl());
        try {
            post.setRequestEntity(new StringRequestEntity(content, "application/json", "UTF-8"));
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
            logger.error("build request error", e);
        }
        try {
            client.executeMethod(post);
            logger.info(post.getResponseBodyAsString());
        } catch (IOException e) {
            logger.error("send msg error", e);
        }
        post.releaseConnection();
    }

    private HttpClient getHttpClient() {
        HttpClient client = new HttpClient();
        Jenkins jenkins = Jenkins.getInstance();
        if (jenkins != null && jenkins.proxy != null) {
            ProxyConfiguration proxy = jenkins.proxy;
            if (proxy != null && client.getHostConfiguration() != null) {
                client.getHostConfiguration().setProxy(proxy.name, proxy.port);
                String username = proxy.getUserName();
                String password = proxy.getPassword();
                // Consider it to be passed if username specified. Sufficient?
                if (username != null && !"".equals(username.trim())) {
                    logger.info("Using proxy authentication (user=" + username + ")");
                    client.getState().setProxyCredentials(AuthScope.ANY,
                            new UsernamePasswordCredentials(username, password));
                }
            }
        }
        return client;
    }

    // 使用自定义的Descriptor
    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {
        public String globalAccessToken;

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject json) throws FormException {
            this.globalAccessToken = json.optString("accessToken");
            save();
            return super.configure(req, json);
        }

        @Override
        public String getDisplayName() { // 插件在界面上展示的名字
            return "DingTalk";
        }
    }
}
