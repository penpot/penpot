(require '[clojure.pprint :refer [pprint]])
(require '[clojure.java.shell :as shell])
(require '[figwheel.main.api :as figwheel])
(require '[cljs.build.api :as api])
(require '[environ.core :refer [env]])

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
   :modules {:common {:entries #{}
                      :output-to "resources/public/js/common.js"}
             :main {:entries #{"uxbox.main"}
                    :output-to "resources/public/js/main.js"
                    :depends-on #{:common}}
             :view {:entries #{"uxbox.view"}
                    :output-to "resources/public/js/view.js"
                    :depends-on #{:common}}}})

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
      (assoc-in [:modules :common :output-to] "dist/js/common.js")
      (assoc-in [:modules :main :output-to] "dist/js/main.js")
      (assoc-in [:modules :view :output-to] "dist/js/view.js")))

(def main-build-build-options
  (merge main-dist-build-options
         {:optimizations :none}))

(def worker-dist-build-options
  (merge default-build-options
         worker-build-options
         dist-build-options
         {:output-to "dist/js/worker.js"
          :output-dir "dist/js/worker"
          :source-map "dist/js/worker.js.map"}))

(def worker-build-build-options
  (merge worker-dist-build-options
         {:optimizations :none
          :source-map true}))

;; --- Tasks Definitions

(defmethod task "dist:main"
  [args]
  (let [cfg main-dist-build-options]
    ;; (pprint cfg)
    (api/build (api/inputs "src") cfg)))

(defmethod task "dist:worker"
  [args]
  (let [cfg worker-dist-build-options]
    ;; (pprint cfg)
    (api/build (api/inputs "src") cfg)))

(defmethod task "build:main"
  [args]
  (let [cfg main-build-build-options]
    ;; (pprint cfg)
    (api/build (api/inputs "src") cfg)))

(defmethod task "build:worker"
  [args]
  (let [cfg worker-build-build-options]
    ;; (pprint cfg)
    (api/build (api/inputs "src") cfg)))

(defmethod task "build:all"
  [args]
  (task ["build:main"])
  (task ["build:worker"]))

(defmethod task "dist:all"
  [args]
  (task ["dist:main"])
  (task ["dist:worker"]))


;; --- Tests Tasks

(defmethod task "build-tests"
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

;; --- Figwheel Config & Tasks

(def figwheel-builds
  {:main {:id "main"
          :options (merge default-build-options main-build-options)}
   :worker {:id "worker"
            :options (merge default-build-options worker-build-options)}})

(def figwheel-options
  {:open-url false
   :pprint-config false
   :load-warninged-code true
   :auto-testing false
   :css-dirs ["resources/public/css"]
   :ring-server-options {:port 3449 :host "0.0.0.0"}
   :watch-dirs ["src" "test"]})

(defmethod task "figwheel"
  [& args]
  (figwheel/start
   figwheel-options
   (:main figwheel-builds)
   (:worker figwheel-builds)))

;;; Build script entrypoint. This should be the last expression.

(task *command-line-args*)
