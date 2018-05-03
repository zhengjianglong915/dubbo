/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.dubbo.common.extension;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.support.ActivateComparator;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.ConcurrentHashSet;
import com.alibaba.dubbo.common.utils.ConfigUtils;
import com.alibaba.dubbo.common.utils.Holder;
import com.alibaba.dubbo.common.utils.StringUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

/**
 * Load dubbo extensions
 * <ul>
 * <li>auto inject dependency extension </li>
 * <li>auto wrap extension in wrapper </li>
 * <li>default extension is an adaptive instance</li>
 * </ul>
 *
 * @see <a href="http://java.sun.com/j2se/1.5.0/docs/guide/jar/jar.html#Service%20Provider">Service Provider in Java 5</a>
 * @see com.alibaba.dubbo.common.extension.SPI
 * @see com.alibaba.dubbo.common.extension.Adaptive
 * @see com.alibaba.dubbo.common.extension.Activate
 */
public class ExtensionLoader<T> {

    private static final Logger logger = LoggerFactory.getLogger(ExtensionLoader.class);

    private static final String SERVICES_DIRECTORY = "META-INF/services/";

    private static final String DUBBO_DIRECTORY = "META-INF/dubbo/";

    private static final String DUBBO_INTERNAL_DIRECTORY = DUBBO_DIRECTORY + "internal/";

    private static final Pattern NAME_SEPARATOR = Pattern.compile("\\s*[,]+\\s*");

    private static final ConcurrentMap<Class<?>, ExtensionLoader<?>> EXTENSION_LOADERS = new ConcurrentHashMap<Class<?>, ExtensionLoader<?>>();

    private static final ConcurrentMap<Class<?>, Object> EXTENSION_INSTANCES = new ConcurrentHashMap<Class<?>, Object>();

    // ============================== 下面都是针对实例的变量

    private final Class<?> type;

    private final ExtensionFactory objectFactory;

    private final ConcurrentMap<Class<?>, String> cachedNames = new ConcurrentHashMap<Class<?>, String>();

    private final Holder<Map<String, Class<?>>> cachedClasses = new Holder<Map<String, Class<?>>>();

    private final Map<String, Activate> cachedActivates = new ConcurrentHashMap<String, Activate>();
    private final ConcurrentMap<String, Holder<Object>> cachedInstances = new ConcurrentHashMap<String, Holder<Object>>();
    private final Holder<Object> cachedAdaptiveInstance = new Holder<Object>();
    private volatile Class<?> cachedAdaptiveClass = null;
    private String cachedDefaultName;
    private volatile Throwable createAdaptiveInstanceError;

    private Set<Class<?>> cachedWrapperClasses;

    private Map<String, IllegalStateException> exceptions = new ConcurrentHashMap<String, IllegalStateException>();

    private ExtensionLoader(Class<?> type) {
        this.type = type;

        /**
         * 1. 对于扩展类型是ExtensionFactory的，设置为null
         * 2. getAdaptiveExtension方法获取一个运行时自适应的扩展类型
         * 3. 每个Extension只能有一个@Adaptive类型的实现，如果没有，dubbo会自动生成一个类
         * 4. objectFactory是一个ExtensionFactory类型的属性，主要用于加载需要注入的类型的实现
         * 5. objectFactory主要用在注入那一步，详细说明见注入时候的说明
         * 6. 这里记住非ExtensionFactory类型的返回的都是一个AdaptiveExtensionFactory
         *
         * 需要先加载ExtensionFactory，使用工厂获取方式获取。 所以需要先加载工厂
         */
        objectFactory = (type == ExtensionFactory.class ? null : ExtensionLoader.getExtensionLoader(ExtensionFactory.class).getAdaptiveExtension());
    }

    private static <T> boolean withExtensionAnnotation(Class<T> type) {
        return type.isAnnotationPresent(SPI.class);
    }

    @SuppressWarnings("unchecked")
    public static <T> ExtensionLoader<T> getExtensionLoader(Class<T> type) {
        if (type == null)
            throw new IllegalArgumentException("Extension type == null");
        if (!type.isInterface()) {
            throw new IllegalArgumentException("Extension type(" + type + ") is not interface!");
        }
        if (!withExtensionAnnotation(type)) {
            throw new IllegalArgumentException("Extension type(" + type +
                    ") is not extension, because WITHOUT @" + SPI.class.getSimpleName() + " Annotation!");
        }

        /**
         * 1. 先从缓存中获取指定类型的ExtensionLoader
         * 2. EXTENSION_LOADERS是一个ConcurrentHashMap，缓存了所有已经加载的ExtensionLoader的实例
         * 3. 比如这里加载Protocol.class，就以Protocol.class作为key，以新创建的ExtensionLoader作为value
         * 4. 每一个要加载的扩展点只会对应一个ExtensionLoader实例，也就是只会存在一个Protocol.class在缓存中
         *
         */
        ExtensionLoader<T> loader = (ExtensionLoader<T>) EXTENSION_LOADERS.get(type); // 优先从缓存获取EXTENSION_LOADERS是一个线程安全类
        if (loader == null) {
            EXTENSION_LOADERS.putIfAbsent(type, new ExtensionLoader<T>(type)); // 创建一个新的
            loader = (ExtensionLoader<T>) EXTENSION_LOADERS.get(type); // 保存到缓存中
        }
        return loader;
    }

    private static ClassLoader findClassLoader() {
        /**
         * 获取类加载器
         */
        return ExtensionLoader.class.getClassLoader();
    }

    public String getExtensionName(T extensionInstance) {
        return getExtensionName(extensionInstance.getClass());
    }

    public String getExtensionName(Class<?> extensionClass) {
        return cachedNames.get(extensionClass);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, key, null)}
     *
     * @param url url
     * @param key url parameter key which used to get extension point names
     * @return extension list which are activated.
     * @see #getActivateExtension(com.alibaba.dubbo.common.URL, String, String)
     */
    public List<T> getActivateExtension(URL url, String key) {
        return getActivateExtension(url, key, null);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, values, null)}
     *
     * @param url    url
     * @param values extension point names
     * @return extension list which are activated
     * @see #getActivateExtension(com.alibaba.dubbo.common.URL, String[], String)
     */
    public List<T> getActivateExtension(URL url, String[] values) {
        return getActivateExtension(url, values, null);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, url.getParameter(key).split(","), null)}
     *
     * @param url   url
     * @param key   url parameter key which used to get extension point names
     * @param group group
     * @return extension list which are activated.
     * @see #getActivateExtension(com.alibaba.dubbo.common.URL, String[], String)
     */
    public List<T> getActivateExtension(URL url, String key, String group) {
        String value = url.getParameter(key);
        return getActivateExtension(url, value == null || value.length() == 0 ? null : Constants.COMMA_SPLIT_PATTERN.split(value), group);
    }

    /**
     * Get activate extensions.
     *
     * @param url    url
     * @param values extension point names
     * @param group  group
     * @return extension list which are activated
     * @see com.alibaba.dubbo.common.extension.Activate
     */
    public List<T> getActivateExtension(URL url, String[] values, String group) {
        List<T> exts = new ArrayList<T>();
        List<String> names = values == null ? new ArrayList<String>(0) : Arrays.asList(values);
        if (!names.contains(Constants.REMOVE_VALUE_PREFIX + Constants.DEFAULT_KEY)) {
            getExtensionClasses();
            for (Map.Entry<String, Activate> entry : cachedActivates.entrySet()) {
                String name = entry.getKey();
                Activate activate = entry.getValue();
                if (isMatchGroup(group, activate.group())) {
                    T ext = getExtension(name);
                    if (!names.contains(name)
                            && !names.contains(Constants.REMOVE_VALUE_PREFIX + name)
                            && isActive(activate, url)) {
                        exts.add(ext);
                    }
                }
            }
            Collections.sort(exts, ActivateComparator.COMPARATOR);
        }
        List<T> usrs = new ArrayList<T>();
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            if (!name.startsWith(Constants.REMOVE_VALUE_PREFIX)
                    && !names.contains(Constants.REMOVE_VALUE_PREFIX + name)) {
                if (Constants.DEFAULT_KEY.equals(name)) {
                    if (!usrs.isEmpty()) {
                        exts.addAll(0, usrs);
                        usrs.clear();
                    }
                } else {
                    T ext = getExtension(name);
                    usrs.add(ext);
                }
            }
        }
        if (!usrs.isEmpty()) {
            exts.addAll(usrs);
        }
        return exts;
    }

    private boolean isMatchGroup(String group, String[] groups) {
        if (group == null || group.length() == 0) {
            return true;
        }
        if (groups != null && groups.length > 0) {
            for (String g : groups) {
                if (group.equals(g)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isActive(Activate activate, URL url) {
        String[] keys = activate.value();
        if (keys == null || keys.length == 0) {
            return true;
        }
        for (String key : keys) {
            for (Map.Entry<String, String> entry : url.getParameters().entrySet()) {
                String k = entry.getKey();
                String v = entry.getValue();
                if ((k.equals(key) || k.endsWith("." + key))
                        && ConfigUtils.isNotEmpty(v)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Get extension's instance. Return <code>null</code> if extension is not found or is not initialized. Pls. note
     * that this method will not trigger extension load.
     * <p>
     * In order to trigger extension load, call {@link #getExtension(String)} instead.
     *
     * @see #getExtension(String)
     */
    @SuppressWarnings("unchecked")
    public T getLoadedExtension(String name) {
        if (name == null || name.length() == 0)
            throw new IllegalArgumentException("Extension name == null");
        Holder<Object> holder = cachedInstances.get(name);
        if (holder == null) {
            cachedInstances.putIfAbsent(name, new Holder<Object>());
            holder = cachedInstances.get(name);
        }
        return (T) holder.get();
    }

    /**
     * Return the list of extensions which are already loaded.
     * <p>
     * Usually {@link #getSupportedExtensions()} should be called in order to get all extensions.
     *
     * @see #getSupportedExtensions()
     */
    public Set<String> getLoadedExtensions() {
        return Collections.unmodifiableSet(new TreeSet<String>(cachedInstances.keySet()));
    }

    /**
     * 根据名字来获取
     * Find the extension with the given name. If the specified name is not found, then {@link IllegalStateException}
     * will be thrown.
     */
    @SuppressWarnings("unchecked")
    public T getExtension(String name) {
        if (name == null || name.length() == 0)
            throw new IllegalArgumentException("Extension name == null");

        // 如果name指定为true，则获取默认实现
        if ("true".equals(name)) {
            // 默认实现查找在下面解析
            return getDefaultExtension();
        }

        // 先从缓存获取Holder，cachedInstance是一个ConcurrentHashMap，键是扩展的name，值是一个持有name对应的实现类实例的Holder。
        Holder<Object> holder = cachedInstances.get(name);
        // 如果当前name对应的Holder不存在，就创建一个，添加进map中
        if (holder == null) {
            cachedInstances.putIfAbsent(name, new Holder<Object>());
            holder = cachedInstances.get(name);
        }
        // 从Holder中获取保存的实例
        Object instance = holder.get();
        if (instance == null) {
            synchronized (holder) {
                instance = holder.get();
                if (instance == null) {
                    /**
                     * 缓存不存在，创建实例
                     */
                    instance = createExtension(name);
                    //加入缓存
                    holder.set(instance);
                }
            }
        }
        return (T) instance;
    }

    /**
     * Return default extension, return <code>null</code> if it's not configured.
     */
    public T getDefaultExtension() {
        // 首先还是先去加载所有的扩展实现
        // 加载的时候会设置默认的名字cachedDefaultName，这个名字是在@SPI中指定的，比如Compiler就指定了@SPI("javassist")，所以这里是javassist
        getExtensionClasses();
        if (null == cachedDefaultName || cachedDefaultName.length() == 0
                || "true".equals(cachedDefaultName)) {
            return null;
        }

        // 根据javassist这个名字去查找扩展实现
        return getExtension(cachedDefaultName);
    }

    public boolean hasExtension(String name) {
        if (name == null || name.length() == 0)
            throw new IllegalArgumentException("Extension name == null");
        try {
            this.getExtensionClass(name);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public Set<String> getSupportedExtensions() {
        Map<String, Class<?>> clazzes = getExtensionClasses();
        return Collections.unmodifiableSet(new TreeSet<String>(clazzes.keySet()));
    }

    /**
     * Return default extension name, return <code>null</code> if not configured.
     */
    public String getDefaultExtensionName() {
        getExtensionClasses();
        return cachedDefaultName;
    }

    /**
     * Register new extension via API
     *
     * @param name  extension name
     * @param clazz extension class
     * @throws IllegalStateException when extension with the same name has already been registered.
     */
    public void addExtension(String name, Class<?> clazz) {
        getExtensionClasses(); // load classes

        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Input type " +
                    clazz + "not implement Extension " + type);
        }
        if (clazz.isInterface()) {
            throw new IllegalStateException("Input type " +
                    clazz + "can not be interface!");
        }

        if (!clazz.isAnnotationPresent(Adaptive.class)) {
            if (StringUtils.isBlank(name)) {
                throw new IllegalStateException("Extension name is blank (Extension " + type + ")!");
            }
            if (cachedClasses.get().containsKey(name)) {
                throw new IllegalStateException("Extension name " +
                        name + " already existed(Extension " + type + ")!");
            }

            cachedNames.put(clazz, name);
            cachedClasses.get().put(name, clazz);
        } else {
            if (cachedAdaptiveClass != null) {
                throw new IllegalStateException("Adaptive Extension already existed(Extension " + type + ")!");
            }

            cachedAdaptiveClass = clazz;
        }
    }

    /**
     * Replace the existing extension via API
     *
     * @param name  extension name
     * @param clazz extension class
     * @throws IllegalStateException when extension to be placed doesn't exist
     * @deprecated not recommended any longer, and use only when test
     */
    @Deprecated
    public void replaceExtension(String name, Class<?> clazz) {
        getExtensionClasses(); // load classes

        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Input type " +
                    clazz + "not implement Extension " + type);
        }
        if (clazz.isInterface()) {
            throw new IllegalStateException("Input type " +
                    clazz + "can not be interface!");
        }

        if (!clazz.isAnnotationPresent(Adaptive.class)) {
            if (StringUtils.isBlank(name)) {
                throw new IllegalStateException("Extension name is blank (Extension " + type + ")!");
            }
            if (!cachedClasses.get().containsKey(name)) {
                throw new IllegalStateException("Extension name " +
                        name + " not existed(Extension " + type + ")!");
            }

            cachedNames.put(clazz, name);
            cachedClasses.get().put(name, clazz);
            cachedInstances.remove(name);
        } else {
            if (cachedAdaptiveClass == null) {
                throw new IllegalStateException("Adaptive Extension not existed(Extension " + type + ")!");
            }

            cachedAdaptiveClass = clazz;
            cachedAdaptiveInstance.set(null);
        }
    }

    @SuppressWarnings("unchecked")
    public T getAdaptiveExtension() {
        /**
         * 1. 先从实例缓存中查找实例对象
         * 2. private final Holder<Object> cachedAdaptiveInstance = new Holder<Object>();
         * 3. 在当前的ExtensionLoader中保存着一个Holder实例，用来缓存自适应实现类的实例
         *
         */
        Object instance = cachedAdaptiveInstance.get();
        if (instance == null) {
            if (createAdaptiveInstanceError == null) {
                synchronized (cachedAdaptiveInstance) {
                    // 获取锁之后再检查一次缓存中是不是已经存在
                    instance = cachedAdaptiveInstance.get();
                    if (instance == null) {
                        try {
                            /**
                             * 如果没有则创建
                             */
                            instance = createAdaptiveExtension();
                            // 保存到缓存中
                            cachedAdaptiveInstance.set(instance);
                        } catch (Throwable t) {
                            createAdaptiveInstanceError = t;
                            throw new IllegalStateException("fail to create adaptive instance: " + t.toString(), t);
                        }
                    }
                }
            } else {
                throw new IllegalStateException("fail to create adaptive instance: " + createAdaptiveInstanceError.toString(), createAdaptiveInstanceError);
            }
        }

        return (T) instance;
    }

    private IllegalStateException findException(String name) {
        for (Map.Entry<String, IllegalStateException> entry : exceptions.entrySet()) {
            if (entry.getKey().toLowerCase().contains(name.toLowerCase())) {
                return entry.getValue();
            }
        }
        StringBuilder buf = new StringBuilder("No such extension " + type.getName() + " by name " + name);


        int i = 1;
        for (Map.Entry<String, IllegalStateException> entry : exceptions.entrySet()) {
            if (i == 1) {
                buf.append(", possible causes: ");
            }

            buf.append("\r\n(");
            buf.append(i++);
            buf.append(") ");
            buf.append(entry.getKey());
            buf.append(":\r\n");
            buf.append(StringUtils.toString(entry.getValue()));
        }
        return new IllegalStateException(buf.toString());
    }

    @SuppressWarnings("unchecked")
    private T createExtension(String name) {
        /**
         * getExtension 返回的是一个Map，键是name，值是name对应的Class
         * 根据name查找对应的Class
         */
        Class<?> clazz = getExtensionClasses().get(name);
        // 如果这时候class还不存在，说明在所有的配置文件中都没找到定义，抛异常
        if (clazz == null) {
            throw findException(name);
        }
        try {
            // 从已创建实例缓存中获取
            T instance = (T) EXTENSION_INSTANCES.get(clazz);
            // 不存在的话就创建一个新实例，加入到缓存中去
            if (instance == null) {
                EXTENSION_INSTANCES.putIfAbsent(clazz, (T) clazz.newInstance());
                instance = (T) EXTENSION_INSTANCES.get(clazz);
            }
            /**
             * 属性注入
             */
            injectExtension(instance);
            /**
             * Wrapper的包装
             */
            Set<Class<?>> wrapperClasses = cachedWrapperClasses;
            if (wrapperClasses != null && !wrapperClasses.isEmpty()) {
                for (Class<?> wrapperClass : wrapperClasses) {
                    /**
                     * injectExtension, 则是为生成的instance注入变量；
                     * wrapperClass
                     */
                    instance = injectExtension((T) wrapperClass.getConstructor(type).newInstance(instance));
                }
            }
            return instance;
        } catch (Throwable t) {
            throw new IllegalStateException("Extension instance(name: " + name + ", class: " +
                    type + ")  could not be instantiated: " + t.getMessage(), t);
        }
    }

    private T injectExtension(T instance) {
        try {
            // 关于objectFactory的来路，先看下面的解析
            // 这里的objectFactory是AdaptiveExtensionFactory
            if (objectFactory != null) {
                // 遍历扩展实现类实例的方法
                for (Method method : instance.getClass().getMethods()) {
                    //只处理set方法, set开头，只有一个参数，public
                    if (method.getName().startsWith("set")
                            && method.getParameterTypes().length == 1
                            && Modifier.isPublic(method.getModifiers())) {
                        // set方法参数类型
                        Class<?> pt = method.getParameterTypes()[0];
                        try {
                            // 将set方法改成 属性变量名字
                            String property = method.getName().length() > 3 ? method.getName().substring(3, 4).toLowerCase() + method.getName().substring(4) : "";
                            /**
                             * 1. 根据类型和名称信息从ExtensionFactory中获取
                             * 2. 比如在某个扩展实现类中会有setProtocol(Protocol protocol)这样的set方法
                             * 3. 这里pt就是Protocol，property就是protocol
                             * 4. AdaptiveExtensionFactory就会根据这两个参数去查找对应的扩展实现类
                             * 5. 这里就会返回Protocol$Adaptive
                             */
                            Object object = objectFactory.getExtension(pt, property);
                            if (object != null) {
                                // 调用set方法
                                method.invoke(instance, object);
                            }
                        } catch (Exception e) {
                            logger.error("fail to inject via method " + method.getName()
                                    + " of interface " + type.getName() + ": " + e.getMessage(), e);
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
        return instance;
    }

    private Class<?> getExtensionClass(String name) {
        if (type == null)
            throw new IllegalArgumentException("Extension type == null");
        if (name == null)
            throw new IllegalArgumentException("Extension name == null");
        Class<?> clazz = getExtensionClasses().get(name);
        if (clazz == null)
            throw new IllegalStateException("No such extension \"" + name + "\" for " + type.getName() + "!");
        return clazz;
    }

    private Map<String, Class<?>> getExtensionClasses() {
        /**
         * 从缓存中获取，cachedClasses也是一个Holder，Holder这里持有的是一个Map，key是扩展点实现名，value是扩展点实现类
         * 这里会存放当前扩展点类型的所有的扩展点的实现类
         * 这里以Protocol为例，就是会存放Protocol的所有实现类
         * 比如key为dubbo，value为com.alibaba.dubbo.rpc.protocol.dubbo.DubboProtocol
         * cachedClasses扩展点实现名称对应的实现类。 这里没有实例化，只是将class缓存起来，在使用的时候进行修改。
         */
        Map<String, Class<?>> classes = cachedClasses.get();
        if (classes == null) {
            synchronized (cachedClasses) {
                classes = cachedClasses.get();
                if (classes == null) {
                    /**
                     * 如果没有加载过Extension的实现，进行扫描加载，完成后缓存起来
                     * 每个扩展点，其实现的加载只会这执行一次。
                     */
                    classes = loadExtensionClasses();
                    cachedClasses.set(classes);
                }
            }
        }
        return classes;
    }

    // synchronized in getExtensionClasses
    private Map<String, Class<?>> loadExtensionClasses() {
        final SPI defaultAnnotation = type.getAnnotation(SPI.class);
        if (defaultAnnotation != null) {
            /**
             * 当前Extension的默认实现名字, 比如说Protocol接口，注解是@SPI("dubbo") 这里dubbo就是默认的值
             */
            String value = defaultAnnotation.value();

            // 只能有一个默认的名字，如果多了，谁也不知道该用哪一个实现了。
            if (value != null && (value = value.trim()).length() > 0) {
                String[] names = NAME_SEPARATOR.split(value);
                if (names.length > 1) {
                    throw new IllegalStateException("more than 1 default extension name on extension " + type.getName()
                            + ": " + Arrays.toString(names));
                }
                // 设置默认值
                if (names.length == 1) cachedDefaultName = names[0];
            }
        }
        // 下面就开始从配置文件中加载扩展实现类
        Map<String, Class<?>> extensionClasses = new HashMap<String, Class<?>>();
        /**
         * 从META-INF/dubbo/internal目录下加载
         */
        loadFile(extensionClasses, DUBBO_INTERNAL_DIRECTORY);
        /**
         * 从META-INF/dubbo/目录下加载
         */
        loadFile(extensionClasses, DUBBO_DIRECTORY);
        /**
         * 从META-INF/services/下加载
         */
        loadFile(extensionClasses, SERVICES_DIRECTORY);
        return extensionClasses;
    }

    private void loadFile(Map<String, Class<?>> extensionClasses, String dir) {
        // 配置文件的名称
        // 这里type是扩展类，比如com.alibaba.dubbo.rpc.Protocol类
        String fileName = dir + type.getName();
        try {
            Enumeration<java.net.URL> urls;
            // 获取类加载器
            ClassLoader classLoader = findClassLoader();
            if (classLoader != null) {
                urls = classLoader.getResources(fileName);
            } else {
                urls = ClassLoader.getSystemResources(fileName);
            }
            if (urls != null) {
                // 遍历文件进行处理
                while (urls.hasMoreElements()) {
                    // 配置文件路径
                    java.net.URL url = urls.nextElement();
                    try {
                        BufferedReader reader = new BufferedReader(new InputStreamReader(url.openStream(), "utf-8"));
                        try {
                            String line = null;
                            // 每次处理一行
                            while ((line = reader.readLine()) != null) {
                                // #号以后的为注释
                                final int ci = line.indexOf('#');
                                // 注释去掉
                                if (ci >= 0) line = line.substring(0, ci);
                                line = line.trim();
                                if (line.length() > 0) {
                                    try {
                                        String name = null;
                                        // =号之前的为扩展名字，后面的为扩展类实现的全限定名
                                        int i = line.indexOf('=');
                                        if (i > 0) {
                                            name = line.substring(0, i).trim();
                                            line = line.substring(i + 1).trim();
                                        }
                                        if (line.length() > 0) {
                                            // 加载扩展类的实现
                                            Class<?> clazz = Class.forName(line, true, classLoader);
                                            // 查看类型是否匹配
                                            // type是Protocol接口
                                            // clazz就是Protocol的各个实现类
                                            if (!type.isAssignableFrom(clazz)) {
                                                throw new IllegalStateException("Error when load extension class(interface: " +
                                                        type + ", class line: " + clazz.getName() + "), class "
                                                        + clazz.getName() + "is not subtype of interface.");
                                            }
                                            /**
                                             * 判断是否有Adaptive 注解，如果有则直接赋值。 避免陷入死循环
                                             * 如果实现类是@Adaptive类型的，会赋值给cachedAdaptiveClass，这个用来存放被@Adaptive注解的实现类
                                             */
                                            if (clazz.isAnnotationPresent(Adaptive.class)) {
                                                if (cachedAdaptiveClass == null) {
                                                    cachedAdaptiveClass = clazz;
                                                } else if (!cachedAdaptiveClass.equals(clazz)) {
                                                    throw new IllegalStateException("More than 1 adaptive class found: "
                                                            + cachedAdaptiveClass.getClass().getName()
                                                            + ", " + clazz.getClass().getName());
                                                }
                                            } else {
                                                // 不是@Adaptice类型的类，就是没有注解@Adaptive的实现类
                                                try {
                                                    /**
                                                     * 判断是否是wrapper类型
                                                     * 如果得到的实现类的构造方法中的参数是扩展点类型的，就是一个Wrapper类.
                                                     * 比如ProtocolFilterWrapper，实现了Protocol类，而它的构造方法是这样public ProtocolFilterWrapper(Protocol protocol)
                                                     * 就说明这个类是一个包装类
                                                     */
                                                    clazz.getConstructor(type);

                                                    // cachedWrapperClasses用来存放当前扩展点实现类中的包装类
                                                    Set<Class<?>> wrappers = cachedWrapperClasses;
                                                    if (wrappers == null) {
                                                        cachedWrapperClasses = new ConcurrentHashSet<Class<?>>();
                                                        wrappers = cachedWrapperClasses;
                                                    }
                                                    wrappers.add(clazz);
                                                } catch (NoSuchMethodException e) {
                                                    // 没有上面提到的构造器，则说明不是wrapper类型
                                                    // 获取无参构造
                                                    clazz.getConstructor();

                                                    // 没有名字，就是配置文件中没有xxx=xxxx.com.xxx这种
                                                    if (name == null || name.length() == 0) {
                                                        // 去找@Extension注解中配置的值
                                                        name = findAnnotationName(clazz);
                                                        // 如果还没找到名字，从类名中获取
                                                        if (name == null || name.length() == 0) {
                                                            // 比如clazz是DubboProtocol，type是Protocol
                                                            // 这里得到的name就是dubbo
                                                            if (clazz.getSimpleName().length() > type.getSimpleName().length()
                                                                    && clazz.getSimpleName().endsWith(type.getSimpleName())) {
                                                                name = clazz.getSimpleName().substring(0, clazz.getSimpleName().length() - type.getSimpleName().length()).toLowerCase();
                                                            } else {
                                                                throw new IllegalStateException("No such extension name for the class " + clazz.getName() + " in the config " + url);
                                                            }
                                                        }
                                                    }
                                                    // 有可能配置了多个名字
                                                    String[] names = NAME_SEPARATOR.split(name);
                                                    if (names != null && names.length > 0) {
                                                        // 是否是Active类型的类
                                                        Activate activate = clazz.getAnnotation(Activate.class);
                                                        if (activate != null) {
                                                            // 第一个名字作为键，放进cachedActivates这个map中缓存
                                                            cachedActivates.put(names[0], activate);
                                                        }
                                                        for (String n : names) {
                                                            if (!cachedNames.containsKey(clazz)) {
                                                                // 放入Extension实现类与名称映射的缓存中去，每个class只对应第一个名称有效
                                                                cachedNames.put(clazz, n);
                                                            }
                                                            Class<?> c = extensionClasses.get(n);
                                                            if (c == null) {
                                                                // 放入到extensionClasses缓存中去，多个name可能对应一份extensionClasses
                                                                extensionClasses.put(n, clazz);
                                                            } else if (c != clazz) {
                                                                throw new IllegalStateException("Duplicate extension " + type.getName() + " name " + n + " on " + c.getName() + " and " + clazz.getName());
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    } catch (Throwable t) {
                                        IllegalStateException e = new IllegalStateException("Failed to load extension class(interface: " + type + ", class line: " + line + ") in " + url + ", cause: " + t.getMessage(), t);
                                        exceptions.put(line, e);
                                    }
                                }
                            } // end of while read lines
                        } finally {
                            reader.close();
                        }
                    } catch (Throwable t) {
                        logger.error("Exception when load extension class(interface: " +
                                type + ", class file: " + url + ") in " + url, t);
                    }
                } // end of while urls
            }
        } catch (Throwable t) {
            logger.error("Exception when load extension class(interface: " +
                    type + ", description file: " + fileName + ").", t);
        }
    }

    @SuppressWarnings("deprecation")
    private String findAnnotationName(Class<?> clazz) {
        com.alibaba.dubbo.common.Extension extension = clazz.getAnnotation(com.alibaba.dubbo.common.Extension.class);
        if (extension == null) {
            String name = clazz.getSimpleName();
            if (name.endsWith(type.getSimpleName())) {
                name = name.substring(0, name.length() - type.getSimpleName().length());
            }
            return name.toLowerCase();
        }
        return extension.value();
    }

    @SuppressWarnings("unchecked")
    private T createAdaptiveExtension() {
        try {
            /**
             * 1. 先通过getAdaptiveExtensionClass获取AdaptiveExtensionClass
             * 2. 然后获取其实例
             * 3. 最后进行注入处理
             */
            return injectExtension((T) getAdaptiveExtensionClass().newInstance());
        } catch (Exception e) {
            throw new IllegalStateException("Can not create adaptive extension " + type + ", cause: " + e.getMessage(), e);
        }
    }

    /**
     * 获取
     * @return
     */
    private Class<?> getAdaptiveExtensionClass() {
        /**
         * 1. 加载当前Extension的所有实现（这里举例是Protocol，只会加载Protocol的所有实现类），如果有@Adaptive类型的实现类，会赋值给cachedAdaptiveClass
         * 2. 目前只有AdaptiveExtensionFactory和AdaptiveCompiler两个实现类是被注解了@Adaptive
         * 3. 除了ExtensionFactory和Compiler类型的扩展之外，其他类型的扩展都是下面动态创建的的实现
         */
        getExtensionClasses();

        /**
         * 加载完所有的实现之后，发现有cachedAdaptiveClass不为空
         * 也就是说当前获取的自适应实现类是AdaptiveExtensionFactory或者是AdaptiveCompiler，就直接返回，这两个类是特殊用处的，不用代码生成，而是现成的代码
         */
        if (cachedAdaptiveClass != null) {
            return cachedAdaptiveClass;
        }

        /**
         * 没有找到Adaptive类型的实现，动态创建一个.
         * 比如Protocol的实现类，没有任何一个实现是用@Adaptive来注解的，只有Protocol接口的方法是有注解的
         * 这时候就需要来动态的生成了，也就是生成Protocol$Adaptive
         *
         */
        return cachedAdaptiveClass = createAdaptiveExtensionClass();
    }

    private Class<?> createAdaptiveExtensionClass() {
        /**
         * 生成一个默认的实现类的字节码
         */
        String code = createAdaptiveExtensionClassCode();
        // 获取到应用的类加载器
        ClassLoader classLoader = findClassLoader();

        /**
         * 获取编译器,dubbo默认使用javassist
         * 这里还是使用扩展点机制来找具体的Compiler的实现
         * 现在就知道cachedAdaptiveClass是啥意思了，如果没有AdaptiveExtensionFactory和AdaptiveCompiler这两个类，这里又要去走加载流程然后来生成扩展点类的代码，不就死循环了么。
         * 这里解析Compiler的实现类的时候，会在getAdaptiveExtensionClass中直接返回
         * 可以查看下AdaptiveCompiler这个类，如果我们没有指定，默认使用javassist
         * 这里Compiler是JavassistCompiler实例
         */
        com.alibaba.dubbo.common.compiler.Compiler compiler = ExtensionLoader.getExtensionLoader(com.alibaba.dubbo.common.compiler.Compiler.class).getAdaptiveExtension();
        //将代码转换成Class
        return compiler.compile(code, classLoader);
    }

    private String createAdaptiveExtensionClassCode() {
        StringBuilder codeBuidler = new StringBuilder();
        Method[] methods = type.getMethods();
        boolean hasAdaptiveAnnotation = false;
        for (Method m : methods) {
            if (m.isAnnotationPresent(Adaptive.class)) {
                hasAdaptiveAnnotation = true;
                break;
            }
        }
        // no need to generate adaptive class since there's no adaptive method found.
        if (!hasAdaptiveAnnotation)
            throw new IllegalStateException("No adaptive method on extension " + type.getName() + ", refuse to create the adaptive class!");

        codeBuidler.append("package " + type.getPackage().getName() + ";");
        codeBuidler.append("\nimport " + ExtensionLoader.class.getName() + ";");
        codeBuidler.append("\npublic class " + type.getSimpleName() + "$Adaptive" + " implements " + type.getCanonicalName() + " {");

        for (Method method : methods) {
            Class<?> rt = method.getReturnType();
            Class<?>[] pts = method.getParameterTypes();
            Class<?>[] ets = method.getExceptionTypes();

            Adaptive adaptiveAnnotation = method.getAnnotation(Adaptive.class);
            StringBuilder code = new StringBuilder(512);
            if (adaptiveAnnotation == null) {  // 如果没有Adaptive注解
                code.append("throw new UnsupportedOperationException(\"method ")
                        .append(method.toString()).append(" of interface ")
                        .append(type.getName()).append(" is not adaptive method!\");");
            } else {
                /**
                 *  如果有注解，表示使用默认的配置, @SPI的值， 里面调用工厂方法返回这个实例
                 */
                int urlTypeIndex = -1;
                for (int i = 0; i < pts.length; ++i) {
                    if (pts[i].equals(URL.class)) {
                        urlTypeIndex = i;
                        break;
                    }
                }
                // found parameter in URL type
                if (urlTypeIndex != -1) {
                    // Null Point check
                    String s = String.format("\nif (arg%d == null) throw new IllegalArgumentException(\"url == null\");",
                            urlTypeIndex);
                    code.append(s);

                    s = String.format("\n%s url = arg%d;", URL.class.getName(), urlTypeIndex);
                    code.append(s);
                }
                // did not find parameter in URL type
                else {
                    String attribMethod = null;

                    // find URL getter method
                    LBL_PTS:
                    for (int i = 0; i < pts.length; ++i) {
                        Method[] ms = pts[i].getMethods();
                        for (Method m : ms) {
                            String name = m.getName();
                            if ((name.startsWith("get") || name.length() > 3)
                                    && Modifier.isPublic(m.getModifiers())
                                    && !Modifier.isStatic(m.getModifiers())
                                    && m.getParameterTypes().length == 0
                                    && m.getReturnType() == URL.class) {
                                urlTypeIndex = i;
                                attribMethod = name;
                                break LBL_PTS;
                            }
                        }
                    }
                    if (attribMethod == null) {
                        throw new IllegalStateException("fail to create adaptive class for interface " + type.getName()
                                + ": not found url parameter or url attribute in parameters of method " + method.getName());
                    }

                    // Null point check
                    String s = String.format("\nif (arg%d == null) throw new IllegalArgumentException(\"%s argument == null\");",
                            urlTypeIndex, pts[urlTypeIndex].getName());
                    code.append(s);
                    s = String.format("\nif (arg%d.%s() == null) throw new IllegalArgumentException(\"%s argument %s() == null\");",
                            urlTypeIndex, attribMethod, pts[urlTypeIndex].getName(), attribMethod);
                    code.append(s);

                    s = String.format("%s url = arg%d.%s();", URL.class.getName(), urlTypeIndex, attribMethod);
                    code.append(s);
                }

                String[] value = adaptiveAnnotation.value();
                // value is not set, use the value generated from class name as the key
                if (value.length == 0) {
                    char[] charArray = type.getSimpleName().toCharArray();
                    StringBuilder sb = new StringBuilder(128);
                    for (int i = 0; i < charArray.length; i++) {
                        if (Character.isUpperCase(charArray[i])) {
                            if (i != 0) {
                                sb.append(".");
                            }
                            sb.append(Character.toLowerCase(charArray[i]));
                        } else {
                            sb.append(charArray[i]);
                        }
                    }
                    value = new String[]{sb.toString()};
                }

                boolean hasInvocation = false;
                for (int i = 0; i < pts.length; ++i) {
                    if (pts[i].getName().equals("com.alibaba.dubbo.rpc.Invocation")) {
                        // Null Point check
                        String s = String.format("\nif (arg%d == null) throw new IllegalArgumentException(\"invocation == null\");", i);
                        code.append(s);
                        s = String.format("\nString methodName = arg%d.getMethodName();", i);
                        code.append(s);
                        hasInvocation = true;
                        break;
                    }
                }

                String defaultExtName = cachedDefaultName;
                String getNameCode = null;
                for (int i = value.length - 1; i >= 0; --i) {
                    if (i == value.length - 1) {
                        if (null != defaultExtName) {
                            if (!"protocol".equals(value[i]))
                                if (hasInvocation)
                                    getNameCode = String.format("url.getMethodParameter(methodName, \"%s\", \"%s\")", value[i], defaultExtName);
                                else
                                    getNameCode = String.format("url.getParameter(\"%s\", \"%s\")", value[i], defaultExtName);
                            else
                                getNameCode = String.format("( url.getProtocol() == null ? \"%s\" : url.getProtocol() )", defaultExtName);
                        } else {
                            if (!"protocol".equals(value[i]))
                                if (hasInvocation)
                                    getNameCode = String.format("url.getMethodParameter(methodName, \"%s\", \"%s\")", value[i], defaultExtName);
                                else
                                    getNameCode = String.format("url.getParameter(\"%s\")", value[i]);
                            else
                                getNameCode = "url.getProtocol()";
                        }
                    } else {
                        if (!"protocol".equals(value[i]))
                            if (hasInvocation)
                                getNameCode = String.format("url.getMethodParameter(methodName, \"%s\", \"%s\")", value[i], defaultExtName);
                            else
                                getNameCode = String.format("url.getParameter(\"%s\", %s)", value[i], getNameCode);
                        else
                            getNameCode = String.format("url.getProtocol() == null ? (%s) : url.getProtocol()", getNameCode);
                    }
                }
                code.append("\nString extName = ").append(getNameCode).append(";");
                // check extName == null?
                String s = String.format("\nif(extName == null) " +
                                "throw new IllegalStateException(\"Fail to get extension(%s) name from url(\" + url.toString() + \") use keys(%s)\");",
                        type.getName(), Arrays.toString(value));
                code.append(s);

                s = String.format("\n%s extension = (%<s)%s.getExtensionLoader(%s.class).getExtension(extName);",
                        type.getName(), ExtensionLoader.class.getSimpleName(), type.getName());
                code.append(s);

                // return statement
                if (!rt.equals(void.class)) {
                    code.append("\nreturn ");
                }

                s = String.format("extension.%s(", method.getName());
                code.append(s);
                for (int i = 0; i < pts.length; i++) {
                    if (i != 0)
                        code.append(", ");
                    code.append("arg").append(i);
                }
                code.append(");");
            }

            codeBuidler.append("\npublic " + rt.getCanonicalName() + " " + method.getName() + "(");
            for (int i = 0; i < pts.length; i++) {
                if (i > 0) {
                    codeBuidler.append(", ");
                }
                codeBuidler.append(pts[i].getCanonicalName());
                codeBuidler.append(" ");
                codeBuidler.append("arg" + i);
            }
            codeBuidler.append(")");
            if (ets.length > 0) {
                codeBuidler.append(" throws ");
                for (int i = 0; i < ets.length; i++) {
                    if (i > 0) {
                        codeBuidler.append(", ");
                    }
                    codeBuidler.append(ets[i].getCanonicalName());
                }
            }
            codeBuidler.append(" {");
            codeBuidler.append(code.toString());
            codeBuidler.append("\n}");
        }
        codeBuidler.append("\n}");
        if (logger.isDebugEnabled()) {
            logger.debug(codeBuidler.toString());
        }
        return codeBuidler.toString();
    }

    @Override
    public String toString() {
        return this.getClass().getName() + "[" + type.getName() + "]";
    }

}