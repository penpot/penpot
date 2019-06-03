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
  {"uxbox.config.url" (:uxbox-api-url env "http://127.0.0.1:6060/api")
   "uxbox.config.viewurl" (:uxbox-view-url env "/view/")
   "uxbox.config.isdemo" demo?})

(def default-build-options
  {:cache-analysis false
   :parallel-build false
   :language-in  :ecmascript6
   :language-out :ecmascript5
   :closure-defines closure-defines
   :optimizations :none
   :verbose true
   :static-fns false
   :pretty-print true
   :elide-asserts false})

(defn get-output-options
  [name dist? map?]
  (let [prefix (if dist? "dist/js" "resources/public/js")
        opts {:main (symbol (str "uxbox." name))
              :output-to (str prefix "/" name ".js")
              :output-dir (str prefix "/" name)
              :source-map (str prefix "/" name ".js.map")
              :asset-path (str "js/" name)}]
    (cond-> opts
      dist? (dissoc opts :source-map))))

(defmethod task "dist"
  [[_ name]]
  (api/build (api/inputs "src" "test")
             (merge default-build-options
                    (get-output-options name true true)
                    {:optimizations :advanced
                     :static-fns true
                     :elide-asserts true})))

(defmethod task "build"
  [[_ name]]
  (api/build (api/inputs "src" "test")
             (merge default-build-options
                    (get-output-options name false true)
                    {:optimizations :simple})))

(defmethod task "figwheel"
  [args]
  (figwheel/start
   {:open-url false
    :auto-testing false
    :css-dirs ["resources/public/css"
               "resources/public/view/css"]
    :watch-dirs ["src" "test"]}
   {:id "main"
    :options (merge default-build-options
                    (get-output-options "main" false false))}
   {:id "view"
    :options (merge default-build-options
                    (get-output-options "view" false false))}))

;;; Build script entrypoint. This should be the last expression.

(task *command-line-args*)
