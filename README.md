# UXBox #

[![Travis Badge](https://img.shields.io/travis/uxbox/front.svg?style=flat)](https://travis-ci.org/uxbox/front "Travis Badge (frontend)")
[![Travis Badge](https://img.shields.io/travis/uxbox/back.svg?style=flat)](https://travis-ci.org/uxbox/back "Travis Badge (backend)")


## Development ##

### Frontend ###

Grab the code and run:

```
$ lein with-profile +front figwheel
```

This will compile ClojureScript whenever you make changes and serve the application in [localhost](http://localhost:3449/).
Open the page.

#### ClojureScript browser-connected REPL ####

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

#### Static resources generation ####

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

#### Testing ####

For running the tests from a shell, run the following command:

```
$ lein cljsbuild once test
```

If you want to run the tests from a ClojureScript REPL, you can do it like so (given that you want to run the tests contained in the `uxbox.core-test` namespace):

```
cljs.user> (require '[cljs.test :as t])
cljs.user> (t/run-tests 'uxbox.core-test)
```

Note that the test output will appear in the browser and in the shell where you launched the `lein fighweel` command.


#### Transformation from HTML to hiccup ####

For transforming the generated HTMLs to hiccup form, execute the following command:

```
$ lein with-profile +front hicv 2clj resources/public/templates/*.html
```

The `.clj` files in the `hicv` directory will contain the hiccup versions of the HTML templates.

### Backend ###

#### REPL ####

You can start a Clojure REPL with the following command:

```
$ lein repl
```

In Emacs you can use [cider's](https://github.com/clojure-emacs/cider) `M-x cider-jack-in` command in the proyect directory
to have a REPL in your editor.

#### Testing ####

For running the tests from a shell, run the following command:

```
$ lein test
```

If you want to run the tests from a Clojure REPL, you can do it like so (given that you want to run the tests contained in the `uxbox.core-test` namespace):

```
user> (require '[clojure.test :as t])
user> (t/run-tests 'uxbox.core-test)
```


## License ##

TODO
