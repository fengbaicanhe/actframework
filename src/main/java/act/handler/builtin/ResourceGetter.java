package act.handler.builtin;

/*-
 * #%L
 * ACT Framework
 * %%
 * Copyright (C) 2014 - 2017 ActFramework
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import act.Act;
import act.ActResponse;
import act.app.ActionContext;
import act.controller.ParamNames;
import act.handler.RequestHandler;
import act.handler.builtin.controller.FastRequestHandler;
import org.osgl.$;
import org.osgl.http.H;
import org.osgl.mvc.result.NotFound;
import org.osgl.util.E;
import org.osgl.util.IO;
import org.osgl.util.S;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.osgl.http.H.Format.*;
import static org.osgl.http.H.Header.Names.CACHE_CONTROL;

/**
 * Unlike a {@link FileGetter}, the
 * `StaticResourceGetter` read resource from jar packages
 */
public class ResourceGetter extends FastRequestHandler {

    private static final char SEP = '/';

    private FastRequestHandler delegate;

    private String base;
    private URL baseUrl;
    private int preloadSizeLimit;
    private boolean isFolder;
    private ByteBuffer buffer;
    private H.Format preloadedContentType;
    private boolean preloadFailure;
    private boolean preloaded;
    private String etag;
    private volatile RequestHandler indexHandler;
    private ConcurrentMap<String, RequestHandler> subFolderIndexHandlers = new ConcurrentHashMap<>();

    private Set<URL> folders = new HashSet<>();
    private Map<String, String> etags = new HashMap<>();
    private Map<String, ByteBuffer> cachedBuffers = new HashMap<>();
    private Map<String, H.Format> cachedContentType = new HashMap<>();
    private Map<String, Boolean> cachedFailures = new HashMap<>();

    public ResourceGetter(String base) {
        E.illegalArgumentIf(S.blank(base), "empty resource string encountered");
        String path = S.ensureStartsWith(base, SEP);
        this.base = path;
        this.baseUrl = FileGetter.class.getResource(path);
        this.delegate = verifyBase(this.baseUrl, base);
        if (null == delegate) {
            this.isFolder = isFolder(this.baseUrl, path);
            if (!this.isFolder && "file".equals(baseUrl.getProtocol())) {
                Act.jobManager().beforeAppStart(new Runnable() {
                    @Override
                    public void run() {
                        preloadCache();
                    }
                });
            }
            this.preloadSizeLimit = Act.appConfig().resourcePreloadSizeLimit();
        }
    }

    @Override
    protected void releaseResources() {
    }

    @Override
    public boolean express(ActionContext context) {
        if (preloaded || null != delegate) {
            return true;
        }
        String path = context.paramVal(ParamNames.PATH);
        return Act.isProd() &&
                (cachedBuffers.containsKey(path)
                        || cachedFailures.containsKey(path)
                        || (null != context.req().etag() && context.req().etagMatches(etags.get(path))));
    }

    @Override
    public void handle(ActionContext context) {
        if (null != delegate) {
            delegate.handle(context);
            return;
        }
        context.handler(this);
        String path = path(context);
        handle(path, context);
    }

    protected String path(ActionContext context) {
        return context.paramVal(ParamNames.PATH);
    }

    protected void handle(String path, ActionContext context) {
        H.Request req = context.req();
        if (Act.isProd()) {
            ActResponse resp = context.prepareRespForWrite();
            if (preloaded) {
                // this is a reloaded file resource
                if (preloadFailure) {
                    AlwaysNotFound.INSTANCE.handle(context);
                } else {
                    resp.contentType(preloadedContentType);
                    if (req.etagMatches(etag)) {
                        AlwaysNotModified.INSTANCE.handle(context);
                    } else {
                        H.Format contentType = cachedContentType.get(path);
                        if (null == contentType) {
                            contentType = req.contentType();
                        }
                        resp
                                .contentType(contentType)
                                .header(CACHE_CONTROL, "public, max-age=7200")
                                .etag(this.etag)
                                .writeContent(buffer.duplicate());
                    }
                }
                return;
            }

            if (cachedFailures.containsKey(path)) {
                AlwaysNotFound.INSTANCE.handle(context);
                return;
            }

            if (null != req.etag() && req.etagMatches(etags.get(path))) {
                H.Format contentType = cachedContentType.get(path);
                if (null == contentType) {
                    contentType = req.contentType();
                }
                resp.contentType(contentType);
                AlwaysNotModified.INSTANCE.handle(context);
                return;
            }
        }
        ByteBuffer buffer = cachedBuffers.get(path);
        if (null != buffer) {
            context.resp()
                    .contentType(cachedContentType.get(path))
                    .header(CACHE_CONTROL, "public, max-age=7200");
            context.applyContentType();
            context.prepareRespForWrite()
                    .etag(etags.get(path))
                    .writeContent(buffer.duplicate());
            return;
        }
        try {
            URL target;
            String loadPath;
            if (S.blank(path)) {
                target = baseUrl;
                loadPath = base;
                if (isFolder) {
                    if (null == indexHandler) {
                        synchronized (this) {
                            if (null == indexHandler) {
                                loadPath = S.pathConcat(base, SEP, "index.html");
                                target = FileGetter.class.getResource(loadPath);
                            }
                            indexHandler = null == target ? AlwaysForbidden.INSTANCE : new FixedResourceGetter(loadPath);
                        }
                    }
                    indexHandler.handle(context);
                    return;
                }
            } else {
                loadPath = S.pathConcat(base, SEP, path);
                target = FileGetter.class.getResource(loadPath);
                if (null == target) {
                    throw NotFound.get();
                }
            }
            if (preventFolderAccess(target, loadPath, context)) {
                return;
            }
            H.Format contentType = FileGetter.contentType(target.getPath());
            ActResponse resp = context.prepareRespForWrite();
            resp.contentType(contentType);
            if (Act.isProd()) {
                resp.header(CACHE_CONTROL, "public, max-age=7200");
            }
            context.applyCorsSpec().applyContentSecurityPolicy().applyContentType();
            try {
                int n = IO.copy(target.openStream(), resp.outputStream());
                if (Act.isProd()) {
                    etags.put(path, String.valueOf(n));
                    if (n < context.config().resourcePreloadSizeLimit()) {
                        $.Var<String> etagBag = $.var();
                        buffer = doPreload(target, etagBag);
                        if (null == buffer) {
                            cachedFailures.put(path, true);
                        } else {
                            cachedBuffers.put(path, buffer);
                            cachedContentType.put(path, contentType);
                        }
                    }
                }
            } catch (NullPointerException e) {
                // this is caused by accessing folder inside jar URL
                folders.add(target);
                AlwaysForbidden.INSTANCE.handle(context);
            }
        } catch (IOException e) {
            logger.warn(e, "Error servicing static resource request");
            throw NotFound.get();
        }
    }

    private boolean preventFolderAccess(URL target, String path, ActionContext context) {
        RequestHandler folderHandler = subFolderIndexHandlers.get(path);
        if (null != folderHandler) {
            folderHandler.handle(context);
            return true;
        }
        if (isFolder(target, path)) {
            String indexPath = S.pathConcat(path, SEP, "index.html");
            URL indexTarget = ResourceGetter.class.getResource(indexPath);
            if (null != indexTarget) {
                folderHandler = exists(indexTarget, indexPath) ? new FixedResourceGetter(indexPath) : AlwaysForbidden.INSTANCE;
                subFolderIndexHandlers.putIfAbsent(path, folderHandler);
                folderHandler.handle(context);
            }
            return true;
        }
        return false;
    }

    private boolean isFolder(URL target, String path) {
        if ("file".equals(target.getProtocol())) {
            File file = new File(target.getFile());
            return file.isDirectory();
        }
        if ("jar".equals(target.getProtocol())) {
            if (path.endsWith("/")) {
                return true;
            }
            URL url = FileGetter.class.getResource(S.ensureEndsWith(path, "/"));
            return null != url;
        }
        return false;
    }

    private boolean exists(URL target, String path) {
        if ("file".equals(target.getProtocol())) {
            File file = new File(target.getFile());
            return file.exists();
        }
        if ("jar".equals(target.getProtocol())) {
            URL url = FileGetter.class.getResource(path);
            return null != url;
        }
        return false;
    }

    private void preloadCache() {
        if (Act.isDev()) {
            return;
        }
        H.Format contentType = FileGetter.contentType(baseUrl.getPath());
        if (HTML == contentType || CSS == contentType || JAVASCRIPT == contentType
                || TXT == contentType || CSV == contentType
                || JSON == contentType || XML == contentType
                || resourceSizeIsOkay()) {
            $.Var<String> etagBag = $.var();
            buffer = doPreload(baseUrl, etagBag);
            if (null == buffer) {
                preloadFailure = true;
            } else {
                this.etag = etagBag.get();
            }
            preloadedContentType = contentType;
            preloaded = true;
        }
    }

    private ByteBuffer doPreload(URL target, $.Var<String> etagBag) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            IO.copy(target.openStream(), baos);
            byte[] ba = baos.toByteArray();
            buffer = ByteBuffer.wrap(ba);
            etagBag.set(String.valueOf(Arrays.hashCode(ba)));
            return buffer;
        } catch (IOException e) {
            Act.LOGGER.warn(e, "Error loading resource: %s", baseUrl.getPath());
        }
        return null;
    }

    private boolean resourceSizeIsOkay() {
        if (preloadSizeLimit <= 0) {
            return false;
        }
        if ("file".equals(baseUrl.getProtocol())) {
            File file = new File(baseUrl.getFile());
            return file.length() < preloadSizeLimit;
        }
        return false;
    }

    @Override
    public boolean supportPartialPath() {
        return isFolder;
    }

    @Override
    public String toString() {
        S.Buffer buf = S.buffer().append("resource: ").append(base);
        if (null == baseUrl) {
            buf.append("(not found)");
        }
        return buf.toString();
    }

    /*
     * If base is valid then return null
     * otherwise return delegate request handler
     */
    private FastRequestHandler verifyBase(URL baseUrl, String baseSupplied) {
        if (null == baseUrl) {
            logger.warn("URL base not exists: " + baseSupplied);
            return AlwaysNotFound.INSTANCE;
        }
        return null;
    }

}
