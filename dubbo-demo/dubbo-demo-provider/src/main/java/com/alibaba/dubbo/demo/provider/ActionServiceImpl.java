package com.alibaba.dubbo.demo.provider;

import com.alibaba.dubbo.demo.ActionService;

/**
 * @author: zhengjianglong
 * @create: 2018-05-01 16:03
 */
public class ActionServiceImpl implements ActionService {
    @Override
    public int add(int a, int b) {
        return a + b;
    }
}
