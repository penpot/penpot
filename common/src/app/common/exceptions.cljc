;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.exceptions
  "A helpers for work with exceptions."
  #?(:cljs (:require-macros [app.common.exceptions]))
  (:require
   #?(:clj [clojure.stacktrace :as strace])
   [app.common.pprint :as pp]
   [app.common.schema :as sm]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]
   [expound.alpha :as expound])
  #?(:clj
     (:import
      clojure.lang.IPersistentMap)))

#?(:clj (set! *warn-on-reflection* true))

(defmacro error
  [& {:keys [type hint] :as params}]
  `(ex-info ~(or hint (name type))
            (merge
             ~(dissoc params :cause ::data)
             ~(::data params))
            ~(:cause params)))

(defmacro raise
  [& params]
  `(throw (error ~@params)))

;; FIXME deprecate
(defn try*
  [f on-error]
  (try (f) (catch #?(:clj Throwable :cljs :default) e (on-error e))))

;; http://clj-me.cgrand.net/2013/09/11/macros-closures-and-unexpected-object-retention/
;; Explains the use of ^:once metadata

(defmacro ignoring
  [& exprs]
  (if (:ns &env)
    `(try ~@exprs (catch :default e# nil))
    `(try ~@exprs (catch Throwable e# nil))))

(defmacro try!
  [& exprs]
  (if (:ns &env)
    `(try ~@exprs (catch :default e# e#))
    `(try ~@exprs (catch Throwable e# e#))))

(defn ex-info?
  [v]
  (instance? #?(:clj clojure.lang.IExceptionInfo :cljs cljs.core.ExceptionInfo) v))

(defn error?
  [v]
  (instance? #?(:clj clojure.lang.IExceptionInfo :cljs cljs.core.ExceptionInfo) v))

(defn exception?
  [v]
  (instance? #?(:clj java.lang.Throwable :cljs js/Error) v))

#?(:clj
   (defn runtime-exception?
     [v]
     (instance? RuntimeException v)))

(defn explain
  ([data] (explain data nil))
  ([data {:keys [max-problems] :or {max-problems 10} :as opts}]
   (cond
     ;; ;; NOTE: a special case for spec validation errors on integrant
     (and (= (:reason data) :integrant.core/build-failed-spec)
          (contains? data :explain))
     (explain (:explain data) opts)

     (and (contains? data ::s/problems)
          (contains? data ::s/value)
          (contains? data ::s/spec))
     (binding [s/*explain-out* expound/printer]
       (with-out-str
         (s/explain-out (update data ::s/problems #(take max-problems %)))))

     (contains? data ::sm/explain)
     (pp/pprint-str (sm/humanize-data (::sm/explain data))
                    :level 3
                    :length 10))))

#?(:clj
(defn format-throwable
  [^Throwable cause & {:keys [summary? detail? header? data? explain? chain? data-level data-length trace-length]
                       :or {summary? true
                            detail? true
                            header? true
                            data? true
                            explain? true
                            chain? true
                            data-length 10
                            data-level 3}}]

  (letfn [(print-trace-element [^StackTraceElement e]
            (let [class (.getClassName e)
                  method (.getMethodName e)]
              (let [match (re-matches #"^([A-Za-z0-9_.-]+)\$(\w+)__\d+$" (str class))]
                (if (and match (= "invoke" method))
                  (apply printf "%s/%s" (rest match))
                  (printf "%s.%s" class method))))
            (printf "(%s:%d)" (or (.getFileName e) "") (.getLineNumber e)))

          (print-explain [explain]
            (print "    xp: ")
            (let [[line & lines] (str/lines explain)]
              (print line)
              (newline)
              (doseq [line lines]
                (println "       " line))))

          (print-data [data]
            (when (seq data)
              (print "    dt: ")
              (let [[line & lines] (str/lines (pp/pprint-str data :level data-level :length data-length ))]
                (print line)
                (newline)
                (doseq [line lines]
                  (println "       " line)))))

          (print-trace-title [^Throwable cause]
            (print   " →  ")
            (printf "%s: %s" (.getName (class cause)) (first (str/lines (ex-message cause))))

            (when-let [^StackTraceElement e (first (.getStackTrace ^Throwable cause))]
              (printf " (%s:%d)" (or (.getFileName e) "") (.getLineNumber e)))

            (newline))

          (print-summary [^Throwable cause]
            (let [causes (loop [cause (ex-cause cause)
                                result []]
                           (if cause
                             (recur (ex-cause cause)
                                    (conj result cause))
                             result))]
              (when header?
                (println "SUMMARY:"))
              (print-trace-title cause)
              (doseq [cause causes]
                (print-trace-title cause))))

          (print-trace [^Throwable cause]
            (print-trace-title cause)
            (let [st (.getStackTrace cause)]
              (print "    at: ")
              (if-let [e (first st)]
                (print-trace-element e)
                (print "[empty stack trace]"))
              (newline)

              (doseq [e (if (nil? trace-length) (rest st) (take (dec trace-length) (rest st)))]
                (print "        ")
                (print-trace-element e)
                (newline))))

          (print-detail [^Throwable cause]
            (print-trace cause)
            (when-let [data (ex-data cause)]
              (when data?
                (print-data (dissoc data ::s/problems ::s/spec ::s/value ::sm/explain)))
              (when explain?
                (if-let [explain (explain data)]
                  (print-explain explain)))))

          (print-all [^Throwable cause]
            (when summary?
              (print-summary cause))

            (when detail?
              (when header?
                (println "DETAIL:"))

              (print-detail cause)
              (when chain?
                (loop [cause cause]
                  (when-let [cause (ex-cause cause)]
                    (newline)
                    (print-detail cause)
                    (recur cause))))))
          ]

    (with-out-str
      (print-all cause)))))

#?(:clj
(defn print-throwable
  [cause & {:as opts}]
  (println (format-throwable cause opts))))
