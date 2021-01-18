# Charlie

A man-in-the-middle (MITM) HTTP/S proxy tool.



## TODO: route all traffic through the proxy on macOS:
sudo networksetup -setwebproxy Ethernet proxyserver.example.com 80 off
networksetup -listallnetworkservices


Setting up a proxy with networksetup: (check you available adapters with networksetup -listallnetworkservices before this)

networksetup -setwebproxy "Wi-fi" 127.0.0.1 8080
If required, you can setup authentication with the following syntax: [-setwebproxy networkservice domain portnumber authenticated username password]

Turning the proxy on or off:

networksetup -setwebproxystate "Wi-fi" off
View the proxy status:

networksetup -getwebproxy "Wi-Fi"


## TODO: netcat in clojure/java


## adding proxy to aleph
https://github.com/ztellman/aleph/pull/352/files


## mitm proxy using netty
https://github.com/hsiafan/cute-proxy/blob/d774856f6caf1ad0d80b404cd07f1b60b69150be/src/main/java/net/dongliu/proxy/netty/handler/HttpProxyHandler.java


## SOCKS Proxy



## reify vs proxy vs deftype
https://groups.google.com/forum/#!msg/clojure/pZFl8gj1lMs/qVfIjQ4jDDMJ
