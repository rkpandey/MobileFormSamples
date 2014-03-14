package com.adobe.sample.rpandey;

import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.apache.commons.io.IOUtils;
import org.apache.http.ConnectionClosedException;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.params.HttpClientParams;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.params.HttpProtocolParams;

import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.*;
import java.io.*;
import java.util.*;

/**
 * Created with IntelliJ IDEA.
 * User: rpandey
 * Date: 3/12/14
 * Time: 3:15 PM
 */
public class MobileFormProxy extends HttpServlet {
    private static final String LC_SUBMISSION_URL = "/lc/bin/xfaforms/submitaction";
    private static final String PARAM_PACKET = "packet";
    private static final String PARAM_SUBMIT_URL = "submitUrl";

    private static final Properties SubmitUrlMaps;

    private static final HashSet<String> blacklistHeaders = new HashSet<String>();
    private static final HashSet<String> blacklistResponseHeaders = new HashSet<String>();


    private static final String PACKET_FORM = "form";
    private static final String LCServer;

    static {
        //property file that maintains a map between submitUrl hints and actual submitUrl
        SubmitUrlMaps = new Properties();
        try {
            SubmitUrlMaps.load(MobileFormProxy.class.getResourceAsStream("/submit.properties"));
        } catch (IOException e) {
            e.printStackTrace();
        }

        Properties LCServerInfo = new Properties();

        try {
            LCServerInfo.load(MobileFormProxy.class.getResourceAsStream("/lcserver.properties"));
        } catch (IOException e) {
            e.printStackTrace();
        }

        if(LCServerInfo != null )
            LCServer = LCServerInfo.getProperty("lcserver");
        else
            LCServer = "";

        //since we are playing with CONTENT so just ignore these
        blacklistHeaders.add("CONTENT-LENGTH");
        blacklistHeaders.add("CONTENT-TYPE");
        //transfer encoding is meant for communication between HTTP Client and SubmitURL so don't set it
        blacklistResponseHeaders.add("TRANSFER-ENCODING");
        blacklistResponseHeaders.add("CONTENT-LENGTH");
    }

    private HttpResponse executeRequest(CloseableHttpClient httpClient, final HttpServletRequest httpServletRequest, HttpUriRequest httpUriRequest) throws IOException {
        //HttpProtocolParams.setUserAgent(httpClient.getParams(), httpServletRequest.getHeader("User-Agent"));
        //HttpClientParams.setRedirecting(httpClient.getParams(), true);

        //set up all headers
        Enumeration headers = httpServletRequest.getHeaderNames();
        while(headers.hasMoreElements()){
            String headerName = (String)headers.nextElement();
            if(!blacklistHeaders.contains(headerName.toUpperCase())){
                httpUriRequest.setHeader(headerName,httpServletRequest.getHeader(headerName));
            }
        }

        try {
            return httpClient.execute(httpUriRequest);
        }
        finally {
            //httpClient.close();
        }

    }

    //function to replicate HttpResponse to httpServlet response
    private void replicateResponse(HttpResponse httpResponse, HttpServletResponse httpServletResponse) throws IOException {

        for(Header respHeader : httpResponse.getAllHeaders()) {
            String headerName = respHeader.getName();
            if(!blacklistResponseHeaders.contains(headerName.toUpperCase())) {
                httpServletResponse.setHeader(headerName, respHeader.getValue());
            }
        }

        httpServletResponse.setStatus(httpResponse.getStatusLine().getStatusCode());

        if(httpResponse.getStatusLine().getStatusCode() < 500){
            HttpEntity respEntity = httpResponse.getEntity();

            if(respEntity != null) {
                Header contentType = respEntity.getContentType();
                if(contentType != null) {
                    httpServletResponse.setContentType(contentType.getValue());
                }
                //write response to original output stream
                try{
                    respEntity.writeTo(httpServletResponse.getOutputStream());
                }
                catch(final ConnectionClosedException ignore) { //to workaround a bug in httpclient

                }
            }
        }

    }


    @Override
    protected void doHead(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse) throws ServletException, IOException {
        //pass this head request to LC_URL
        CloseableHttpClient httpClient = HttpClients.createDefault();
        HttpHead httpHead = new HttpHead(LCServer+LC_SUBMISSION_URL);
        replicateResponse(executeRequest(httpClient, httpServletRequest, httpHead), httpServletResponse);
    }

    @Override
    protected void doPost(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse) throws ServletException, IOException {
        try {
            MultipartEntityBuilder multipartEntityBuilder = MultipartEntityBuilder.create();
            List<FileItem> items = new ServletFileUpload(new DiskFileItemFactory()).parseRequest(httpServletRequest);
            String packet = null, submitUrl = null;

            for(FileItem item: items) {
                //copy all form field except submit url
                if(item.isFormField() && !PARAM_SUBMIT_URL.equalsIgnoreCase(item.getFieldName())) {
                    multipartEntityBuilder.addTextBody(item.getFieldName(), item.getString("UTF-8"));
                }

                if(item.isFormField() && item.isInMemory()) {

                    if(PARAM_PACKET.equalsIgnoreCase(item.getFieldName()) && packet == null) {
                        packet = item.getString();
                    }

                    if(PARAM_SUBMIT_URL.equalsIgnoreCase(item.getFieldName()) && submitUrl == null) {
                        submitUrl = item.getString();
                    }
                }
            }

            CloseableHttpClient httpClient = HttpClients.createDefault();
            HttpPost lcPost = new HttpPost(LCServer+LC_SUBMISSION_URL);
            lcPost.setEntity(multipartEntityBuilder.build());
            try{
                HttpResponse response = executeRequest(httpClient, httpServletRequest, lcPost);

                if(PACKET_FORM.equalsIgnoreCase(packet)) {
                    //server side scripts or webservice
                    replicateResponse(response, httpServletResponse);
                }
                else {
                    if(response.getStatusLine().getStatusCode() < 500){
                        //data xml
                        HttpEntity resEntity = response.getEntity();
                        InputStream dataStream = null;
                        if(resEntity != null) {
                            dataStream = resEntity.getContent();
                        }

                        log("dataXML received.");
                        //you can submit to the url hint specified in submitUrl
                        String actualSubmitUrl = submitUrl == null ? "" : SubmitUrlMaps.getProperty(submitUrl,"");
                        if(!"".equals(actualSubmitUrl) && dataStream != null) {
                            //prepare another request and send the URL
                            HttpPost finalDataPost = new HttpPost(actualSubmitUrl);
                            finalDataPost.setHeader("Content-Type", "application/xml");
                            //one can use InputStreamEntity
                            byte[] dataXML = IOUtils.toByteArray(dataStream);
                            finalDataPost.setEntity(new ByteArrayEntity(dataXML));
                            CloseableHttpClient httpClient2 = HttpClients.createDefault();
                            try{
                                //execute and replicate response
                                replicateResponse(executeRequest(httpClient2, httpServletRequest,finalDataPost), httpServletResponse);
                            }
                            finally {
                                httpClient2.close();
                            }
                        }
                        else {
                            replicateResponse(response, httpServletResponse);
                        }
                    }
                    else {
                        replicateResponse(response, httpServletResponse);
                    }
                }

            }
            finally {
                httpClient.close();
            }
        } catch (FileUploadException e) {
            httpServletResponse.setStatus(500);
            throw new ServletException("Cannot parse multi-part request",e);
        }
        finally {

        }

    }

}


