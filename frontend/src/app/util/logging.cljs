;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2020 UXBOX Labs SL
;;
;; This code is highly inspired on the lambdaisland/glogi library but
;; adapted and simplified to our needs. The adapted code shares the
;; same license. You can found the origianl source code here:
;; https://github.com/lambdaisland/glogi

(ns app.util.logging
  (:require
   [goog.log :as glog]
   [goog.debug.Logger :as Logger]
   [goog.debug.Logger.Level :as Level]
   [goog.debug.Console :as Console]
   [cuerdas.core :as str]
   [goog.object :as gobj])
  (:import
   [goog.debug Logger Console LogRecord]
   [goog.debug.Logger Level])
  (:require-macros [app.util.logging]))

(defn- logger-name
  [s]
  (cond
    (string? s) s
    (= s :root) ""
    (simple-ident? s) (name s)
    (qualified-ident? s) (str (namespace s) "." (name s))
    :else (str s)))

(defn get-logger
  [n]
  (if (instance? Logger n)
    n
    (glog/getLogger (logger-name n))))

(def root (get-logger :root))


(def levels
  {:off     Level/OFF
   :shout   Level/SHOUT
   :error   Level/SEVERE
   :severe  Level/SEVERE
   :warning Level/WARNING
   :warn    Level/WARNING
   :info    Level/INFO
   :config  Level/CONFIG
   :debug   Level/FINE
   :fine    Level/FINE
   :finer   Level/FINER
   :trace   Level/FINER
   :finest  Level/FINEST
   :all     Level/ALL})

(def colors
  {:gray3    "#8e908c"
   :gray4    "#969896"
   :gray5    "#4d4d4c"
   :gray6    "#282a2e"
   :black    "#1d1f21"
   :red      "#c82829"
   :blue     "#4271ae"
   :orange   "#f5871f"})

(defn- get-level-value
  [level]
  (if (instance? Level level)
    (.-value ^Level level)
    (.-value ^Level (get levels level))))

(defn- level->color
  [level]
  (condp <= (get-level-value level)
    (get-level-value :error) (get colors :red)
    (get-level-value :warn)  (get colors :orange)
    (get-level-value :info)  (get colors :blue)
    (get-level-value :debug) (get colors :gray4)
    (get-level-value :trace) (get colors :gray3)
    (get colors :gray2)))

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
    (subs (.-name ^Level (get levels l)) 0 3)))

(defn- make-log-record
  [level message name exception]
  (let [record (LogRecord. level message name)]
    (when exception
      (.setException record exception))
    record))

(defn log
  "Output a log message to the given logger, optionally with an exception to be
  logged."
  ([name lvl message]
   (log name lvl message nil))
  ([name lvl message exception]
   (when glog/ENABLED
     (when-let [l (get-logger name)]
       (.logRecord ^Logger l (make-log-record (get levels lvl) message name exception))))))

(defn set-level*
  "Set the level (a keyword) of the given logger, identified by name."
  [name lvl]
  (assert (contains? levels lvl))
  (when-let [l (get-logger name)]
    (.setLevel ^Logger l (get levels lvl))))

(defn set-levels!
  [lvls]
  (doseq [[logger level] lvls
          :let [level (if (string? level) (keyword level) level)]]
    (set-level* logger level)))

(defn add-handler!
  ([handler-fn]
   (add-handler! root handler-fn))
  ([logger-or-name handler-fn]
   (when-let [l (get-logger logger-or-name)]
     (letfn [(handler [^LogRecord record]
               (handler-fn {:seqn (.-sequenceNumber_ record)
                            :time (.-time_ record)
                            :level (keyword (str/lower (.-name (.-level_ record))))
                            :message (.-msg_ record)
                            :logger-name (.-loggerName_ record)
                            :exception (.-exception_ record)}))]
       (unchecked-set handler "handler-fn" handler-fn)
       (.addHandler ^Logger l handler)))))

(defn add-handler-once!
  ([handler-fn]
   (add-handler-once! root handler-fn))
  ([logger-or-name handler-fn]
   (when-let [l (get-logger logger-or-name)]
     (when-not (some (comp #{handler-fn} #(gobj/get % "handler-fn"))
                     (.-handlers_ l))
       (add-handler! l handler-fn)))))

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
                 specials))))))

(defonce default-console-handler
  (fn [{:keys [message exception level logger-name]}]
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
                  :error (if (instance? cljs.core.ExceptionInfo v)
                           (js/console.error (pr-str v))
                           (js/console.error v))))
              (js/console.groupEnd message))
            (let [message (str header "%c" (pr-str message))]
              (js/console.log message header-styles normal-styles))))))))

(defn initialize!
  []
  (when-let [instance Console/instance]
    (.setCapturing ^Console instance false))

  (add-handler-once! default-console-handler))
