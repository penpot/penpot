;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.util.dispatcher
  "A generic service dispatcher implementation."
  (:refer-clojure :exclude [defmethod])
  (:require
   [clojure.spec.alpha :as s]
   [promesa.core :as p]
   [expound.alpha :as expound]
   [sieppari.core :as sp]
   [sieppari.context :as spx]
   [uxbox.common.exceptions :as ex])
  (:import
   clojure.lang.IDeref
   clojure.lang.MapEntry
   java.util.Map
   java.util.HashMap))

(definterface IDispatcher
  (^void add [key f]))

(defn- wrap-handler
  [items handler]
  (reduce #(%2 %1) handler items))

(deftype Dispatcher [reg attr wrap-fns]
  IDispatcher
  (add [this key f]
    (let [f (wrap-handler wrap-fns f)]
      (.put ^Map reg key f)
      this))

  clojure.lang.IDeref
  (deref [_]
    {:registry reg
     :attr attr
     :wrap-fns wrap-fns})

  clojure.lang.IFn
  (invoke [_ params]
    (let [key (get params attr)
          f   (.get ^Map reg key)]
      (when (nil? f)
        (ex/raise :type :not-found
                  :code :method-not-found
                  :hint "No method found for the current request."))
      (f params))))

(defn dispatcher?
  [v]
  (instance? IDispatcher v))

(defmacro defservice
  [sname & {:keys [dispatch-by wrap]}]
  `(def ~sname (Dispatcher. (HashMap.) ~dispatch-by ~wrap)))

(defn parse-defmethod
  [args]
  (loop [r {}
         s 0
         v (first args)
         n (rest args)]
    (case s
      0 (if (symbol? v)
          (recur (assoc r :sym v) 1 (first n) (rest n))
          (throw (ex-info "first arg to `defmethod` should be a symbol" {})))
      1 (if (qualified-keyword? v)
          (recur (-> r
                     (assoc :key (keyword (name v)))
                     (assoc :meta {:spec v :doc nil}))
                 3 (first n) (rest n))
          (recur r (inc s) v n))
      2  (if (simple-keyword? v)
          (recur (-> r
                     (assoc :key v)
                     (assoc :meta {:doc nil}))
                 3 (first n) (rest n))
          (throw (ex-info "second arg to `defmethod` should be a keyword" {})))
      3 (if (string? v)
          (recur (update r :meta assoc :doc v) (inc s) (first n) (rest n))
          (recur r 4 v n))
      4 (if (map? v)
          (recur (update r :meta merge v) (inc s) (first n) (rest n))
          (recur r 5 v n))
      5 (if (vector? v)
          (assoc r :args v :body n)
          (throw (ex-info "missing arguments vector" {}))))))

(defn add-method
  [^Dispatcher dsp key f meta]
  (let [f (with-meta f meta)]
    (.add dsp key f)
    dsp))

(defmacro defmethod
  [& args]
  (let [{:keys [key meta sym args body]} (parse-defmethod args)
        f `(fn ~args ~@body)]
    `(do
       (s/assert dispatcher? ~sym)
       (add-method ~sym ~key ~f ~meta))))

(defn wrap-spec
  [handler]
  (let [mdata (meta handler)
        spec (s/get-spec (:spec mdata))]
    (if (nil? spec)
      handler
      (with-meta
        (fn [params]
          (let [result (s/conform spec params)]
            (if (not= result ::s/invalid)
              (handler result)
              (let [data (s/explain-data spec params)]
                (ex/raise :type :validation
                          :code :spec-validation
                          :explain (with-out-str
                                     (expound/printer data))
                          :data (::s/problems data))))))
        (assoc mdata ::wrap-spec true)))))

(defn wrap-error
  [handler]
  (let [mdata (meta handler)]
    (with-meta
      (fn [params]
        (try
          (-> (handler params)
              (p/catch' (fn [error]
                          (ex/raise :type :service-error
                                    :name (:spec mdata)
                                    :cause error))))
          (catch Throwable error
            (p/rejected (ex/error :type :service-error
                                  :name (:spec mdata)
                                  :cause error)))))
      (assoc mdata ::wrap-error true))))

