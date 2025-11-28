package com.nageoffer.ai.ragent.framework.exception.kb;

import com.nageoffer.ai.ragent.framework.exception.ServiceException;

/**
 * 向量表重复创建异常
 */
public class VectorCollectionAlreadyExistsException extends ServiceException {

    public VectorCollectionAlreadyExistsException(String collectionName) {
        super("向量集合已存在，禁止重复创建：" + collectionName);
    }
}