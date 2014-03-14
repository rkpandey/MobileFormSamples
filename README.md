MobileFormSamples
=================

Samples for mobile forms. All samples are organized in folders with their project POM inside it.

Sample 1 (sub-proxy): Mobile Form Proxy
This is a Submission Proxy based on [Mobile Form Service Proxy](http://blogs.adobe.com/foxes/mobile-form-service-proxy-lc11-0-1-es4sp1-new/) blog post. To build the project you can run 'mvn install'. You can deploy the sub-proxy.war created in the target forlder to any webserver.
If your webserver is running on a different machine than LiveCycle Server then you need to update lcserver.properties file in src/resources with LC Server and port. 
The sample.properties file in src/resources maintains a map between the submitUrl ID visible to user and the actual submitUrl.
You can pass submitServiceProxy like following while rendering the form.
 
http://localhost:8080/lc/content/xfaforms/profiles/test.html?contentRoot=repository:///applications/forms/1.0&template=test.xdp&submitServiceProxy=http://localhost:8080/sub-proxy/all

If you don't pass the submitUrl parameter, you will get the data as xml back in the browser.
