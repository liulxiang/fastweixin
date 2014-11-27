package com.github.sd4324530.fastweixin.api;

import com.github.sd4324530.fastweixin.api.config.ApiConfig;
import com.github.sd4324530.fastweixin.api.enums.ResultType;
import com.github.sd4324530.fastweixin.api.response.BaseResponse;
import com.github.sd4324530.fastweixin.api.response.GetTokenResponse;
import com.github.sd4324530.fastweixin.util.BeanUtil;
import com.github.sd4324530.fastweixin.util.JSONUtil;
import com.github.sd4324530.fastweixin.util.NetWorkCenter;
import org.apache.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * API基类，提供一些通用方法
 * 包含自动刷新token、通用get post请求等
 * @author peiyu
 * @since 1.2
 */
public abstract class BaseAPI {

    private static final Logger log = LoggerFactory.getLogger(BaseAPI.class);

    protected static final String BASE_API_URL = "https://api.weixin.qq.com/";

    protected final ApiConfig config;

    //用于刷新token时锁住config，防止多次刷新
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    private final Lock readLock = lock.readLock();

    private final Lock writeLock = lock.writeLock();

    public BaseAPI(ApiConfig config) {
        this.config = config;
    }

    /**
     * 刷新token
     */
    protected void refreshToken() {
        log.debug("开始刷新access_token......");
        writeLock.lock();
        try {
            if(config.refreshing.compareAndSet(false, true)) {
                String url = BASE_API_URL + "cgi-bin/token?grant_type=client_credential&appid=" + this.config.getAppid() + "&secret=" + this.config.getSecret();
                NetWorkCenter.get(url, null, new NetWorkCenter.ResponseCallback() {
                    @Override
                    public void onResponse(int resultCode, String resultJson) {
                        if (HttpStatus.SC_OK == resultCode) {
                            GetTokenResponse response = JSONUtil.toBean(resultJson, GetTokenResponse.class);
                            BaseAPI.this.config.setAccess_token(response.getAccess_token());
                            log.debug("刷新access_token成功.....");
                        }
                        else {
                            log.warn("获取access_token失败....");
                            log.warn("信息:{}", resultJson);
                        }
                    }
                });
            }
        } finally {
            config.refreshing.set(false);
            writeLock.unlock();
        }
    }

    /**
     * 通用post请求
     * @param url 地址，其中token用#代替
     * @param json 参数，json格式
     * @return 请求结果
     */
    protected BaseResponse executePost(String url, String json) {
        BaseResponse response = null;
        BeanUtil.requireNonNull(url, "url is null");

        readLock.lock();
        try {
           //需要传token
           url = url.replace("#",config.getAccess_token());
            response = NetWorkCenter.post(url, json);
        } finally {
            readLock.unlock();
        }

        if(null == response || ResultType.ACCESS_TOKEN_TIMEOUT.toString().equals(response.getErrcode())) {
            if(!config.refreshing.get()) {
                refreshToken();
            }
            readLock.lock();
            try {
                log.debug("接口调用重试....");
                TimeUnit.SECONDS.sleep(1);
                response = NetWorkCenter.post(url, json);
            } catch (InterruptedException e) {
                log.error("线程休眠异常", e);
            } catch (Exception e) {
                log.error("请求出现异常", e);
            } finally {
                readLock.unlock();
            }
        }

        return response;
    }

    /**
     * 通用post请求
     * @param url 地址，其中token用#代替
     * @return 请求结果
     */
    protected BaseResponse executeGet(String url) {
        BaseResponse response = null;
        BeanUtil.requireNonNull(url, "url is null");

        readLock.lock();
        try {
            //需要传token
            url = url.replace("#",config.getAccess_token());
            response = NetWorkCenter.get(url);
        } finally {
            readLock.unlock();
        }

        if(null == response || ResultType.ACCESS_TOKEN_TIMEOUT.toString().equals(response.getErrcode())) {
            if (!config.refreshing.get()) {
                refreshToken();
            }
            readLock.lock();
            try {
                log.debug("接口调用重试....");
                TimeUnit.SECONDS.sleep(1);
                response = NetWorkCenter.get(url);
            } catch (InterruptedException e) {
                log.error("线程休眠异常", e);
            } catch (Exception e) {
                log.error("请求出现异常", e);
            }finally {
                readLock.unlock();
            }
        }
        return response;
    }
}
