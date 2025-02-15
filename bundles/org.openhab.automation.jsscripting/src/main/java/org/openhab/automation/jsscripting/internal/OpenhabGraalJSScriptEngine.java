/**
 * Copyright (c) 2010-2021 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.automation.jsscripting.internal;

import static org.openhab.core.automation.module.script.ScriptEngineFactory.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessMode;
import java.nio.file.FileSystems;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileAttribute;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.script.ScriptContext;
import javax.script.ScriptException;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.openhab.automation.jsscripting.internal.fs.DelegatingFileSystem;
import org.openhab.automation.jsscripting.internal.fs.PrefixedSeekableByteChannel;
import org.openhab.automation.jsscripting.internal.fs.ReadOnlySeekableByteArrayChannel;
import org.openhab.automation.jsscripting.internal.fs.watch.JSDependencyTracker;
import org.openhab.automation.jsscripting.internal.scriptengine.InvocationInterceptingScriptEngineWithInvocable;
import org.openhab.core.automation.module.script.ScriptExtensionAccessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.oracle.truffle.js.scriptengine.GraalJSScriptEngine;

/**
 * GraalJS Script Engine implementation
 *
 * @author Jonathan Gilbert - Initial contribution
 * @author Dan Cunningham - Script injections
 */
public class OpenhabGraalJSScriptEngine extends InvocationInterceptingScriptEngineWithInvocable<GraalJSScriptEngine> {

    private static final Logger LOGGER = LoggerFactory.getLogger(OpenhabGraalJSScriptEngine.class);
    private static final String GLOBAL_REQUIRE = "require(\"@jsscripting-globals\");";
    private static final String REQUIRE_WRAPPER_NAME = "__wraprequire__";
    // final CommonJS search path for our library
    private static final Path LOCAL_NODE_PATH = Paths.get("/node_modules");

    // these fields start as null because they are populated on first use
    private @NonNullByDefault({}) String engineIdentifier;
    private @NonNullByDefault({}) Consumer<String> scriptDependencyListener;

    private boolean initialized = false;
    private String globalScript;

    /**
     * Creates an implementation of ScriptEngine (& Invocable), wrapping the contained engine, that tracks the script
     * lifecycle and provides hooks for scripts to do so too.
     */
    public OpenhabGraalJSScriptEngine(@Nullable String injectionCode) {
        super(null); // delegate depends on fields not yet initialised, so we cannot set it immediately
        this.globalScript = GLOBAL_REQUIRE + (injectionCode != null ? injectionCode : "");
        delegate = GraalJSScriptEngine.create(
                Engine.newBuilder().allowExperimentalOptions(true).option("engine.WarnInterpreterOnly", "false")
                        .build(),
                Context.newBuilder("js").allowExperimentalOptions(true).allowAllAccess(true)
                        .option("js.commonjs-require-cwd", JSDependencyTracker.LIB_PATH)
                        .option("js.nashorn-compat", "true") // to ease migration
                        .option("js.ecmascript-version", "2021") // nashorn compat will enforce es5 compatibility, we
                                                                 // want ecma2021
                        .option("js.commonjs-require", "true") // enable CommonJS module support
                        .hostClassLoader(getClass().getClassLoader())
                        .fileSystem(new DelegatingFileSystem(FileSystems.getDefault().provider()) {
                            @Override
                            public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options,
                                    FileAttribute<?>... attrs) throws IOException {
                                if (scriptDependencyListener != null) {
                                    scriptDependencyListener.accept(path.toString());
                                }
                                if (path.toString().endsWith(".js")) {
                                    SeekableByteChannel sbc = null;
                                    if (path.startsWith(LOCAL_NODE_PATH)) {
                                        InputStream is = getClass().getResourceAsStream(path.toString());
                                        if (is == null) {
                                            throw new IOException("Could not read " + path.toString());
                                        }
                                        sbc = new ReadOnlySeekableByteArrayChannel(is.readAllBytes());
                                    } else {
                                        sbc = super.newByteChannel(path, options, attrs);
                                    }
                                    return new PrefixedSeekableByteChannel(
                                            ("require=" + REQUIRE_WRAPPER_NAME + "(require);").getBytes(), sbc);
                                } else {
                                    return super.newByteChannel(path, options, attrs);
                                }
                            }

                            @Override
                            public void checkAccess(Path path, Set<? extends AccessMode> modes,
                                    LinkOption... linkOptions) throws IOException {
                                if (path.startsWith(LOCAL_NODE_PATH)) {
                                    if (getClass().getResource(path.toString()) == null) {
                                        throw new NoSuchFileException(path.toString());
                                    }
                                } else {
                                    super.checkAccess(path, modes, linkOptions);
                                }
                            }

                            @Override
                            public Map<String, Object> readAttributes(Path path, String attributes,
                                    LinkOption... options) throws IOException {
                                if (path.startsWith(LOCAL_NODE_PATH)) {
                                    return Collections.singletonMap("isRegularFile", true);
                                }
                                return super.readAttributes(path, attributes, options);
                            }

                            @Override
                            public Path toRealPath(Path path, LinkOption... linkOptions) throws IOException {
                                if (path.startsWith(LOCAL_NODE_PATH)) {
                                    return path;
                                }
                                return super.toRealPath(path, linkOptions);
                            }
                        }));
    }

    @Override
    protected void beforeInvocation() {
        if (initialized) {
            return;
        }

        ScriptContext ctx = delegate.getContext();

        // these are added post-construction, so we need to fetch them late
        this.engineIdentifier = (String) ctx.getAttribute(CONTEXT_KEY_ENGINE_IDENTIFIER);
        if (this.engineIdentifier == null) {
            throw new IllegalStateException("Failed to retrieve engine identifier from engine bindings");
        }

        ScriptExtensionAccessor scriptExtensionAccessor = (ScriptExtensionAccessor) ctx
                .getAttribute(CONTEXT_KEY_EXTENSION_ACCESSOR);
        if (scriptExtensionAccessor == null) {
            throw new IllegalStateException("Failed to retrieve script extension accessor from engine bindings");
        }

        scriptDependencyListener = (Consumer<String>) ctx
                .getAttribute("oh.dependency-listener"/* CONTEXT_KEY_DEPENDENCY_LISTENER */);
        if (scriptDependencyListener == null) {
            LOGGER.warn(
                    "Failed to retrieve script script dependency listener from engine bindings. Script dependency tracking will be disabled.");
        }

        ScriptExtensionModuleProvider scriptExtensionModuleProvider = new ScriptExtensionModuleProvider(
                scriptExtensionAccessor);

        Function<Function<Object[], Object>, Function<String, Object>> wrapRequireFn = originalRequireFn -> moduleName -> scriptExtensionModuleProvider
                .locatorFor(delegate.getPolyglotContext(), engineIdentifier).locateModule(moduleName)
                .map(m -> (Object) m).orElseGet(() -> originalRequireFn.apply(new Object[] { moduleName }));

        delegate.getBindings(ScriptContext.ENGINE_SCOPE).put(REQUIRE_WRAPPER_NAME, wrapRequireFn);
        delegate.put("require", wrapRequireFn.apply((Function<Object[], Object>) delegate.get("require")));

        initialized = true;

        try {
            eval(globalScript);
        } catch (ScriptException e) {
            LOGGER.error("Could not inject global script", e);
        }
    }
}
