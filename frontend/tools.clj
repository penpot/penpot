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
   :language-out :ecmascript6
   :closure-defines closure-defines
   :optimizations :none
   :verbose false
   :static-fns false
   :pretty-print true
   :elide-asserts false})

(defn get-output-options
  [name dist? map?]
  (let [prefix (if dist? "dist/js" "resources/public/js")
        srcmap (if (= map? ::path)
                 (str prefix "/" name ".js.map")
                 map?)]
    {:main (symbol (str "uxbox." name))
     :output-to (str prefix "/" name ".js")
     :output-dir (str prefix "/" name)
     :source-map srcmap
     :asset-path (str "/js/" name)}))

(defmethod task "dist"
  [[_ name]]
  (api/build (api/inputs "src")
             (merge default-build-options
                    (get-output-options name true ::path)
                    (when (= name "worker")
                      {:target :webworker})
                    {:optimizations :advanced
                     :pretty-print false
                     :static-fns true
                     :elide-asserts true})))

(defmethod task "build"
  [[_ name]]
  (api/build (api/inputs "src")
             (merge default-build-options
                    (get-output-options name true true)
                    (when (= name "worker")
                      {:target :webworker})
                    {:optimizations :none})))

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


(def figwheel-builds
  {:main {:id "main"
          :options (merge default-build-options
                          (get-output-options "main" false true))}
   :view {:id "view"
          :options (merge default-build-options
                          (get-output-options "view" false true))}
   :worker {:id "worker"
            :options (merge default-build-options
                            {:target :webworker}
                            (get-output-options "worker" false true))}})

(def figwheel-options
  {:open-url false
   :load-warninged-code true
   :auto-testing false
   :css-dirs ["resources/public/css"
              "resources/public/view/css"]
   :ring-server-options {:port 3449 :host "0.0.0.0"}
   :watch-dirs ["src" "test"]})

(defmethod task "figwheel"
  [& args]
  (figwheel/start
   figwheel-options
   (:main figwheel-builds)
   (:view figwheel-builds)
   (:worker figwheel-builds)))

(defmethod task "figwheel-single"
  [[_ name]]
  (when-let [build (get figwheel-builds (keyword name))]
    (figwheel/start
     figwheel-options
     build
     (:worker figwheel-builds))))

(defmethod task "build-all"
  [args]
  (task ["build" "main"])
  (task ["build" "view"])
  (task ["build" "worker"]))

(defmethod task "dist-all"
  [args]
  (task ["dist" "main"])
  (task ["dist" "view"])
  (task ["dist" "worker"]))

;;; Build script entrypoint. This should be the last expression.

(task *command-line-args*)
