;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.logging
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.uuid :as uuid]
   [app.common.spec :as us]
   [cuerdas.core :as str]
   [clojure.spec.alpha :as s]
   [fipp.edn :as fpp]
   #?(:cljs [goog.log :as glog]))
  #?(:cljs (:require-macros [app.common.logging])
     :clj  (:import
            org.apache.logging.log4j.Level
            org.apache.logging.log4j.LogManager
            org.apache.logging.log4j.Logger
            org.apache.logging.log4j.ThreadContext
            org.apache.logging.log4j.CloseableThreadContext
            org.apache.logging.log4j.spi.LoggerContext)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CLJ Specific
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

#?(:clj (set! *warn-on-reflection* true))

(def ^:private reserved-props
  #{:level :cause ::logger ::async ::raw ::context})

(def ^:private props-xform
  (comp (partition-all 2)
        (remove (fn [[k]] (contains? reserved-props k)))
        (map vec)))

(defn build-message
  [props]
  (loop [pairs  (sequence props-xform props)
         result []]
    (if-let [[k v] (first pairs)]
      (recur (rest pairs)
             (conj result (str/concat (d/name k) "=" (pr-str v))))
      result)))

#?(:clj
   (def logger-context
     (LogManager/getContext false)))

#?(:clj
   (def logging-agent
     (agent nil :error-mode :continue)))

#?(:clj
   (defn stringify-data
     [val]
     (cond
       (string? val)
       val

       (instance? clojure.lang.Named val)
       (name val)

       (coll? val)
       (binding [*print-level* 8
                 *print-length* 25]
         (with-out-str (fpp/pprint val {:width 200})))

       :else
       (str val))))

#?(:clj
   (defn data->context-map
     ^java.util.Map
     [data]
     (into {}
           (comp (filter second)
                 (map (fn [[key val]]
                        [(stringify-data key)
                         (stringify-data val)])))
           data)))

#?(:clj
   (defmacro with-context
     [data & body]
     `(let [data# (data->context-map ~data)]
        (with-open [closeable# (CloseableThreadContext/putAll data#)]
          ~@body))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Common
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn get-logger
  [lname]
  #?(:clj  (.getLogger ^LoggerContext logger-context ^String lname)
     :cljs (glog/getLogger
            (cond
              (string? lname) lname
              (= lname :root) ""
              (simple-ident? lname) (name lname)
              (qualified-ident? lname) (str (namespace lname) "." (name lname))
              :else (str lname)))))

(defn get-level
  [level]
  #?(:clj
     (case level
       :trace Level/TRACE
       :debug Level/DEBUG
       :info  Level/INFO
       :warn  Level/WARN
       :error Level/ERROR
       :fatal Level/FATAL)
     :cljs
     (case level
       :off     (.-OFF ^js glog/Level)
       :shout   (.-SHOUT ^js glog/Level)
       :error   (.-SEVERE ^js  glog/Level)
       :severe  (.-SEVERE ^js glog/Level)
       :warning (.-WARNING ^js glog/Level)
       :warn    (.-WARNING ^js glog/Level)
       :info    (.-INFO ^js glog/Level)
       :config  (.-CONFIG ^js glog/Level)
       :debug   (.-FINE ^js glog/Level)
       :fine    (.-FINE ^js glog/Level)
       :finer   (.-FINER ^js glog/Level)
       :trace   (.-FINER ^js glog/Level)
       :finest  (.-FINEST ^js glog/Level)
       :all     (.-ALL ^js glog/Level))))

(defn write-log!
  [logger level exception message]
  #?(:clj
     (let [message (if (string? message) message (str/join ", " message))]
       (if exception
         (.log ^Logger    logger
               ^Level     level
               ^Object    message
               ^Throwable exception)
         (.log ^Logger logger
               ^Level  level
               ^Object message)))
     :cljs
     (when glog/ENABLED
       (let [logger (get-logger logger)
             level  (get-level level)]
         (when (and logger (glog/isLoggable logger level))
           (let [message (if (fn? message) (message) message)
                 message (if (string? message) message (str/join ", " message))
                 record  (glog/LogRecord. level message (.getName ^js logger))]
             (when exception (.setException record exception))
               (glog/publishLogRecord logger record)))))))

#?(:clj
   (defn enabled?
     [logger level]
     (.isEnabled ^Logger logger ^Level level)))

#?(:clj
   (defn get-error-context
     [error]
     (when-let [data (ex-data error)]
       (merge
        {:hint          (ex-message error)
         :spec-problems (some->> data ::s/problems (take 10) seq vec)
         :spec-value    (some->> data ::s/value)
         :data          (some-> data (dissoc ::s/problems ::s/value ::s/spec))}
        (when (and data (::s/problems data))
          {:spec-explain (us/pretty-explain data)})))))

(defmacro log
  [& props]
  (if (:ns &env) ; CLJS
    (let [{:keys [level cause ::logger ::raw]} props]
      `(write-log! ~(or logger (str *ns*)) ~level ~cause (or ~raw (fn [] (build-message ~(vec props))))))

    (let [{:keys [level cause ::logger ::async ::raw ::context] :or {async true}} props
          logger     (or logger (str *ns*))
          logger-sym (gensym "log")
          level-sym  (gensym "log")]
      `(let [~logger-sym (get-logger ~logger)
             ~level-sym  (get-level ~level)]
         (when (enabled? ~logger-sym ~level-sym)
           ~(if async
              `(do
                 (send-off logging-agent
                           (fn [_#]
                             (let [message# (or ~raw (build-message ~(vec props)))]
                               (with-context (-> {:id (uuid/next)}
                                                 (into ~context)
                                                 (into (get-error-context ~cause)))
                                 (try
                                   (write-log! ~logger-sym ~level-sym ~cause message#)
                                   (catch Throwable cause#
                                     (write-log! ~logger-sym (get-level :error) cause#
                                                 "unexpected error on writing log")))))))
                 nil)
              `(let [message# (or ~raw (build-message ~(vec props)))]
                 (write-log! ~logger-sym ~level-sym ~cause message#)
                 nil)))))))

(defmacro info
  [& params]
  `(log :level :info ~@params))

(defmacro error
  [& params]
  `(log :level :error ~@params))

(defmacro warn
  [& params]
  `(log :level :warn ~@params))

(defmacro debug
  [& params]
  `(log :level :debug ~@params))

(defmacro trace
  [& params]
  `(log :level :trace ~@params))

(defmacro set-level!
  ([level]
   (when (:ns &env)
     `(set-level* ~(str *ns*) ~level)))
  ([n level]
   (when (:ns &env)
     `(set-level* ~n ~level))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CLJS Specific
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

#?(:cljs
   (def ^:private colors
     {:gray3  "#8e908c"
      :gray4  "#969896"
      :gray5  "#4d4d4c"
      :gray6  "#282a2e"
      :black  "#1d1f21"
      :red    "#c82829"
      :blue   "#4271ae"
      :orange "#f5871f"}))

#?(:cljs
   (defn- level->color
     [level]
     (letfn [(get-level-value [l] (.-value ^js (get-level l)))]
       (condp <= (get-level-value level)
         (get-level-value :error) (get colors :red)
         (get-level-value :warn)  (get colors :orange)
         (get-level-value :info)  (get colors :blue)
         (get-level-value :debug) (get colors :gray4)
         (get-level-value :trace) (get colors :gray3)
         (get colors :gray2)))))

#?(:cljs
   (defn- level->short-name
     [l]
     (case l
       :fine "DBG"
       :debug "DBG"
       :finer "TRC"
       :trace "TRC"
       :info "INF"
       :warn "WRN"
       :warning "WRN"
       :error "ERR"
       (subs (.-name ^js (get-level l)) 0 3))))

#?(:cljs
   (defn set-level*
     "Set the level (a keyword) of the given logger, identified by name."
     [name lvl]
     (some-> (get-logger name)
             (glog/setLevel (get-level lvl)))))

#?(:cljs
   (defn set-levels!
     [lvls]
     (doseq [[logger level] lvls
             :let [level (if (string? level) (keyword level) level)]]
       (set-level* logger level))))

#?(:cljs
   (defn- prepare-message
     [message]
     (loop [kvpairs  (seq message)
            message  []
            specials []]
       (if (nil? kvpairs)
         [message specials]
         (let [[k v] (first kvpairs)]
           (cond
             (= k :err)
             (recur (next kvpairs)
                    message
                    (conj specials [:error nil v]))

             (and (qualified-ident? k)
                  (= "js" (namespace k)))
             (recur (next kvpairs)
                    message
                    (conj specials [:js (name k) (if (object? v) v (clj->js v))]))

             :else
             (recur (next kvpairs)
                    (conj message (str/concat (d/name k) "=" (pr-str v)))
                    specials)))))))

#?(:cljs
   (defn default-handler
     [{:keys [message level logger-name exception] :as params}]
     (let [header-styles (str "font-weight: 600; color: " (level->color level))
           normal-styles (str "font-weight: 300; color: " (get colors :gray6))
           level-name    (level->short-name level)
           header        (str "%c" level-name " [" logger-name "] ")]

       (if (string? message)
         (let [message (str header "%c" message)]
           (js/console.log message header-styles normal-styles))
         (let [[message specials] (prepare-message message)]
           (if (seq specials)
             (let [message (str header "%c" message)]
               (js/console.group message header-styles normal-styles)
               (doseq [[type n v] specials]
                 (case type
                   :js (js/console.log n v)
                   :error (if (ex/ex-info? v)
                            (js/console.error (pr-str v))
                            (js/console.error v))))
               (js/console.groupEnd message))
             (let [message (str header "%c" message)]
               (js/console.log message header-styles normal-styles)))))

       (when exception
         (when-let [data (ex-data exception)]
           (js/console.error "cause data:" (pr-str data)))
         (js/console.error (.-stack exception))))))


#?(:cljs
   (defn record->map
     [^js record]
     {:seqn (.-sequenceNumber_ record)
      :time (.-time_ record)
      :level (keyword (str/lower (.-name (.-level_ record))))
      :message (.-msg_ record)
      :logger-name (.-loggerName_ record)
      :exception (.-exception_ record)}))

#?(:cljs
   (defonce default-console-handler
     (comp default-handler record->map)))

#?(:cljs
   (defn initialize!
     []
     (let [l (get-logger :root)]
       (glog/removeHandler l default-console-handler)
       (glog/addHandler l default-console-handler)
       nil)))
