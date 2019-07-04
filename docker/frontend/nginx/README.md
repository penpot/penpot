# Setting up NGNIX

You will need to complete the following tasks to setup your dockerized proxy server:

1. Include/Create SSL keys
2. Alter your backend upstream
3. Confirm your backend's path

## Include/Create SSL Keys

Have your key and csr in the nginx/keys directory as server.key and server.crt. These are copied into the docker image on build and used to serve your website or proxy your services.

### Generate your own self signed certificate
```bash
openssl req \
       -newkey rsa:2048 -nodes -keyout nginx/keys/server.key \
       -x509 -out nginx/keys/server.crt
```

This command from your project root will create the keys needed to start docker with self signed certificates. Note that if you are going to deploy this site for production you will want to replace these and rebuild your image with valid (purchased) SSL certificates. All the fields are optional. Do not set any challenge passwords.

If you want validated certificates but are not looking to purchase them; then checkout [Let's Encrypt](https://letsencrypt.org) which is a free SSL certification service.

## Alter your backend upstream

The upstream is a block used to load balance different destinations important to your proxy. In this example the upstream is used to proxy requests to your backend without worrying about XSS configurations.

We have preloaded some examples of what this looks like in the `nginx/conf.d/default.conf` file. You can certainly only specify one server in the block if that is your only server.

## Confirm your backend's path

Assuming your website uses a backend collection of APIs, you can setup your nginx service to reverse proxy to them avoiding any XSS configuration needs. The provided default.conf includes a `/api/` location block to serve as an example. You can replace api in `/api/` with any path you want to have forwarded to your backend.

There is only one setting you need to adjust in this block and that is the `proxy_cookie_domain`. Assuming you have a production domain you would change `my.uxbox.com` to be your domain. If you do not have a production domain it is safe to leave this as is or delete.

## Extending the configuration

You can include more servers or configuration settings by adding any named file in `nginx/conf.d`. These files are automatically consumed by nginx on startup.

[Visit NGINX's beginnner's guide](http://nginx.org/en/docs/beginners_guide.html) for additional help.