package com.bizzan.bitrade.es;

import com.alibaba.fastjson.JSONObject;
import com.bizzan.bitrade.config.ESConfig;

import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Elasticsearch 低级别 REST 客户端封装。
 * 升级说明：随根 POM 中间件统一升级，ES 客户端由 5.x 升级至 7.17.15；7.x 中 RestClient 不再支持
 * performRequest(method, endpoint, params, entity)，改为使用 Request 对象 + performRequest(Request)。
 */
@Slf4j
@Component
public class ESClient {

    @Autowired
    private ESConfig esConfig;

    public JSONObject getClient(String method, String endPoint, JSONObject params){

        log.info("=====method:"+method+"<>====endPoint:"+endPoint+"<>===params+"+params);
        final CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
        credentialsProvider.setCredentials(AuthScope.ANY,
                new UsernamePasswordCredentials(esConfig.getEsUsername(), esConfig.getEsPassword()));
            RestClient restClient = RestClient.builder(new HttpHost(esConfig.getPrivateNet(), esConfig.getEsPort()))
                .setHttpClientConfigCallback(new RestClientBuilder.HttpClientConfigCallback() {
                    @Override
                    public HttpAsyncClientBuilder customizeHttpClient(HttpAsyncClientBuilder httpClientBuilder) {
                        return httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider);
                    }
                }).build();
        try {
            // ES 7.x：使用 Request 封装请求，替代原 performRequest(method, endpoint, params, entity)
            Request request = new Request(method, endPoint);
            request.addParameter("pretty", "true");
            request.setJsonEntity(params.toJSONString());
            Response response = restClient.performRequest(request);
            log.info("======response:"+response);

            int statusCode = response.getStatusLine().getStatusCode();
            String result = EntityUtils.toString(response.getEntity());
            log.info("=====result:"+result);
            JSONObject jsonObject = JSONObject.parseObject(result);
            restClient.close();
            if (200 == statusCode || 201 == statusCode){
                return jsonObject;
            }else {
                log.info("es client 调用失败"+response);
                return null;
            }
        } catch (IOException e) {
            e.printStackTrace();
            log.info("es client 调用失败={}",e);
        }finally {
            try {
                restClient.close();
            } catch (IOException e) {
                e.printStackTrace();
                log.info("es client 调用失败={}",e);
            }
        }
        return null;
    }
}
