;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.logging
  (:require
   [app.common.exceptions :as ex]
   [clojure.pprint :refer [pprint]]
   [cuerdas.core :as str]
   #?(:cljs [goog.log :as glog]))
  #?(:cljs (:require-macros [app.common.logging]))
  #?(:clj
     (:import
      org.apache.logging.log4j.Level
      org.apache.logging.log4j.LogManager
      org.apache.logging.log4j.Logger
      org.apache.logging.log4j.ThreadContext
      org.apache.logging.log4j.message.MapMessage
      org.apache.logging.log4j.spi.LoggerContext)))

#?(:clj
   (defn build-map-message
     [m]
     (let [message (MapMessage. (count m))]
       (reduce-kv #(.with ^MapMessage %1 (name %2) %3) message m))))

#?(:clj
  (def logger-context
    (LogManager/getContext false)))

#?(:clj
  (def logging-agent
    (agent nil :error-mode :continue)))

(defn get-logger
  [lname]
  #?(:clj  (.getLogger ^LoggerContext logger-context ^String lname)
     :cljs
      (glog/getLogger
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
     (if exception
       (.log ^Logger    logger
             ^Level     level
             ^Object    message
             ^Throwable exception)
       (.log ^Logger logger
             ^Level  level
             ^Object message))
     :cljs
     (when glog/ENABLED
       (when-let [l (get-logger logger)]
         (let [level  (get-level level)
               record (glog/LogRecord. level message (.getName ^js l))]
           (when exception (.setException record exception))
           (glog/publishLogRecord l record))))))

#?(:clj
   (defn enabled?
     [logger level]
     (.isEnabled ^Logger logger ^Level level)))

(defmacro log
  [& {:keys [level cause ::logger ::async ::raw] :as props}]
  (if (:ns &env) ; CLJS
    `(write-log! ~(or logger (str *ns*))
                 ~level
                 ~cause
                 ~(dissoc props :level :cause ::logger ::raw))
    (let [props      (dissoc props :level :cause ::logger ::async ::raw)
          logger     (or logger (str *ns*))
          logger-sym (gensym "log")
          level-sym  (gensym "log")]
      `(let [~logger-sym (get-logger ~logger)
             ~level-sym  (get-level ~level)]
         (if (enabled? ~logger-sym ~level-sym)
           ~(if async
              `(send-off logging-agent
                         (fn [_#]
                           (let [message# (or ~raw (build-map-message ~props))]
                             (write-log! ~logger-sym ~level-sym ~cause message#))))
              `(let [message# (or ~raw (build-map-message ~props))]
                 (write-log! ~logger-sym ~level-sym ~cause message#))))))))

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
;; CLJ Specific
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

#?(:clj
   (defn update-thread-context!
     [data]
     (run! (fn [[key val]]
             (ThreadContext/put
              (name key)
              (cond
                (coll? val)
                (binding [clojure.pprint/*print-right-margin* 120]
                  (with-out-str (pprint val)))
                (instance? clojure.lang.Named val) (name val)
                :else (str val))))
           data)))

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
     (loop [kvpairs (seq message)
            message (array-map)
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
                    (assoc message k v)
                    specials)))))))

#?(:cljs
   (defn default-handler
     [{:keys [message level logger-name]}]
     (let [header-styles (str "font-weight: 600; color: " (level->color level))
           normal-styles (str "font-weight: 300; color: " (get colors :gray6))
           level-name    (level->short-name level)
           header        (str "%c" level-name " [" logger-name "] ")]

       (if (string? message)
         (let [message (str header "%c" message)]
           (js/console.log message header-styles normal-styles))
         (let [[message specials] (prepare-message message)]
           (if (seq specials)
             (let [message (str header "%c" (pr-str message))]
               (js/console.group message header-styles normal-styles)
               (doseq [[type n v] specials]
                 (case type
                   :js (js/console.log n v)
                   :error (if (ex/ex-info? v)
                            (js/console.error (pr-str v))
                            (js/console.error v))))
               (js/console.groupEnd message))
             (let [message (str header "%c" (pr-str message))]
               (js/console.log message header-styles normal-styles))))))))

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


