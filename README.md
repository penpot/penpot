# UXBox #

## Development ##

Grab the code and run:

```
$ ./scripts/figwheel
```

This will compile ClojureScript whenever you make changes and serve the application in [localhost](http://localhost:3449/).
Open the page.

### ClojureScript browser-connected REPL ###

The aforementioned command also starts a [nrepl](https://github.com/clojure/tools.nrepl) (network REPL) in the port 7888.

You can connect to it from a shell using the following command:

```
$ lein repl :connect 7888
```

In Emacs you can use [cider's](https://github.com/clojure-emacs/cider) `M-x cider-connect` command and tell it that nREPL is
running on `localhost:7888` to connect.

After connecting to nREPL, run the following Clojure code in it:

```
user> (use 'figwheel-sidecar.repl-api)
user> (cljs-repl)
```

After that, a figwheel message will appear and the prompt will change to `cljs.user>`. We can now evaluate ClojureScript in the
browser from the REPL.


### Static resources generation ###

The project's static resources are processed using [gulp](http://gulpjs.com/). First of all, install the npm dependencies running:

```
npm install
```

To start watching the files and process them with each change, run:

```
npm run watch
```

To process the resources just once, run:

```
npm run dist
```


### Transformation from HTML to hiccup ###

For transforming the generated HTMLs to hiccup form, execute the following command:

```
$ lein with-profile +front hicv 2clj resources/public/templates/*.html
```

The `.clj` files in the `hicv` directory will contain the hiccup versions of the HTML templates.


## License ##

TODO
