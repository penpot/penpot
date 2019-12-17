(require '[clojure.pprint :refer [pprint]])
(require '[clojure.java.shell :as shell])
(require '[figwheel.main.api :as figwheel])
(require '[environ.core :refer [env]])
(require '[cljs.build.api :as api]
         '[cljs.repl :as repl]
         '[cljs.repl.node :as node])
(require '[rebel-readline.core]
         '[rebel-readline.clojure.main]
         '[rebel-readline.clojure.line-reader]
         '[rebel-readline.clojure.service.local]
         '[rebel-readline.cljs.service.local]
         '[rebel-readline.cljs.repl])

(defmulti task first)

(defmethod task :default
  [args]
  (let [all-tasks  (-> task methods (dissoc :default) keys sort)
        interposed (->> all-tasks (interpose ", ") (apply str))]
    (println "Unknown or missing task. Choose one of:" interposed)
    (System/exit 1)))

;; --- Generic Build Options

(def debug? (boolean (:uxbox-debug env nil)))
(def demo? (boolean (:uxbox-demo env nil)))

(def closure-defines
  {"uxbox.config.url" (:uxbox-api-url env "http://localhost:6060/api")
   "uxbox.config.viewurl" (:uxbox-view-url env "/view/index.html")
   "uxbox.config.isdemo" demo?})

(def default-build-options
  {:cache-analysis true
   :parallel-build true
   :language-in  :ecmascript6
   :language-out :ecmascript5
   :closure-defines closure-defines
   :optimizations :none
   :infer-externs true
   :verbose true
   :source-map true
   :static-fns false
   :pretty-print true
   :elide-asserts false})

(def dist-build-options
  {:optimizations :advanced
   :pretty-print false
   :static-fns true
   ;; :fn-invoke-direct true
   :elide-asserts true})

;; --- Specific Build Options

(def main-build-options
  {:output-dir "resources/public/js"
   :asset-path "/js"
   :modules {:main {:entries #{"uxbox.main"}
                    :output-to "resources/public/js/main.js"}
             :view {:entries #{"uxbox.view"}
                    :output-to "resources/public/js/view.js"
                    }}})

(def worker-build-options
  {:main 'uxbox.worker
   :target :webworker
   :output-to "resources/public/js/worker.js"
   :output-dir "resources/public/js/worker"
   :asset-path "/js/worker"})

(def main-dist-build-options
  (-> (merge default-build-options
             main-build-options
             dist-build-options)
      (assoc :output-dir "dist/js")
      (assoc-in [:modules :main :output-to] "dist/js/main.js")
      (assoc-in [:modules :view :output-to] "dist/js/view.js")))

(def main-dbg-dist-build-options
  (merge main-dist-build-options
         {:optimizations :advanced
          :pseudo-names true
          :pretty-print true}))

(def worker-dist-build-options
  (merge default-build-options
         worker-build-options
         dist-build-options
         {:output-to "dist/js/worker.js"
          :output-dir "dist/js/worker"
          :source-map "dist/js/worker.js.map"}))

(def worker-dbg-dist-build-options
  (merge worker-dist-build-options
         {:optimizations :advanced
          :pseudo-names true
          :pretty-print true}))

;; --- Tasks Definitions

(defmethod task "dist:main"
  [args]
  (let [cfg main-dist-build-options]
    (pprint cfg)
    (api/build (api/inputs "src") cfg)))

(defmethod task "dist:worker"
  [args]
  (let [cfg worker-dist-build-options]
    ;; (pprint cfg)
    (api/build (api/inputs "src") cfg)))

(defmethod task "dbg-dist:main"
  [args]
  (let [cfg main-dbg-dist-build-options]
    ;; (pprint cfg)
    (api/build (api/inputs "src") cfg)))

(defmethod task "dbg-dist:worker"
  [args]
  (let [cfg worker-dbg-dist-build-options]
    ;; (pprint cfg)
    (api/build (api/inputs "src") cfg)))

(defmethod task "dist:all"
  [args]
  (task ["dist:main"])
  (task ["dist:worker"]))

(defmethod task "dbg-dist:all"
  [args]
  (task ["dbg-dist:main"])
  (task ["dbg-dist:worker"]))

(defmethod task "repl:node"
  [args]
  (rebel-readline.core/with-line-reader
    (rebel-readline.clojure.line-reader/create
     (rebel-readline.cljs.service.local/create))
    (cljs.repl/repl
     (node/repl-env)
     :prompt (fn []) ;; prompt is handled by line-reader
     :read (rebel-readline.cljs.repl/create-repl-read)
     :output-dir "out"
     :cache-analysis false)))

;; --- Tests Tasks

(defmethod task "build:tests"
  [& args]
  (api/build (api/inputs "src" "test")
             (assoc default-build-options
                    :main 'uxbox.tests.main
                    :verbose true
                    :target :nodejs
                    :source-map true
                    :output-to "target/tests/main.js"
                    :output-dir "target/tests/main"
                    :optimizations :none)))

(defmethod task "watch:tests"
  [args]
  (println "Start watch loop...")
  (letfn [(run-tests []
            (let [{:keys [out err]} (shell/sh "node" "target/tests/main.js")]
              (println out err)))
          (start-watch []
            (try
              (api/watch (api/inputs "src" "test")
                         (assoc default-build-options
                                :main 'uxbox.tests.main
                                :watch-fn run-tests
                                :target :nodejs
                                :source-map true
                                :output-to "target/tests/main.js"
                                :output-dir "target/tests/main"
                                :optimizations :none))
              (catch Exception e
                (println "ERROR:" e)
                (Thread/sleep 2000)
                start-watch)))]
    (trampoline start-watch)))


;; --- Figwheel Config & Tasks

(def figwheel-builds
  {:main {:id "main"
          :options (merge default-build-options main-build-options)}
   :worker {:id "worker"
            :options (merge default-build-options worker-build-options)}})

(def figwheel-options
  {:open-url false
   :pprint-config false
   :load-warninged-code false
   :auto-testing false
   :reload-clj-files true
   :css-dirs ["resources/public/css"]
   :ring-server-options {:port 3449 :host "0.0.0.0"}
   :watch-dirs ["src" "test" "../common"]})

(defmethod task "figwheel"
  [& args]
  (figwheel/start
   figwheel-options
   (:main figwheel-builds)
   (:worker figwheel-builds)))

;;; Build script entrypoint. This should be the last expression.

(task *command-line-args*)
