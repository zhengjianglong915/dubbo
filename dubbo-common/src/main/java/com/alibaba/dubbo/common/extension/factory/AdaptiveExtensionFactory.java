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
package com.alibaba.dubbo.common.extension.factory;

import com.alibaba.dubbo.common.extension.Adaptive;
import com.alibaba.dubbo.common.extension.ExtensionFactory;
import com.alibaba.dubbo.common.extension.ExtensionLoader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * AdaptiveExtensionFactory 默认的工厂，Adaptive就表示模式工厂
 */
@Adaptive
public class AdaptiveExtensionFactory implements ExtensionFactory {

    private final List<ExtensionFactory> factories;

    /**
     * 其他工厂都没提供这个构造函数
     */
    public AdaptiveExtensionFactory() {
        /**
         *  dubbo 内部默认实现的对象工厂是 SpiExtensionFactory 和 SpringExtensionFactory，
         *  他们经过 TreeMap 排好序的查找顺序是优先先从 SpiExtensionFactory 获取
         */
        ExtensionLoader<ExtensionFactory> loader = ExtensionLoader.getExtensionLoader(ExtensionFactory.class);
        List<ExtensionFactory> list = new ArrayList<ExtensionFactory>();
        for (String name : loader.getSupportedExtensions()) {
            list.add(loader.getExtension(name));
        }
        factories = Collections.unmodifiableList(list);
    }

    public <T> T getExtension(Class<T> type, String name) {
        /**
         *  dubbo 内部默认实现的对象工厂是 SpiExtensionFactory 和 SpringExtensionFactory，
         *  他们经过 TreeMap 排好序的查找顺序是优先先从 SpiExtensionFactory 获取
         */
        for (ExtensionFactory factory : factories) {
            T extension = factory.getExtension(type, name);
            if (extension != null) {
                return extension;
            }
        }
        return null;
    }

}
