package javax.microedition.io; public interface HttpConnection extends ContentConnection { String GET="GET"; int HTTP_OK=200;
 void setRequestMethod(String m) throws java.io.IOException; int getResponseCode() throws java.io.IOException; }
