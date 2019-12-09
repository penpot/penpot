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
   [uxbox.util.spec :as us]
   [uxbox.util.exceptions :as ex])
  (:import
   clojure.lang.IDeref
   clojure.lang.MapEntry
   java.util.Map
   java.util.HashMap))

(definterface IDispatcher
  (^void add [key f metadata]))

(deftype Dispatcher [reg attr interceptors]
  IDispatcher
  (add [this key f metadata]
    (.put ^Map reg key (MapEntry/create f metadata))
    this)

  clojure.lang.IDeref
  (deref [_]
    {:registry reg
     :attr attr
     :interceptors interceptors})

  clojure.lang.IFn
  (invoke [_ params]
    (let [key (get params attr)
          entry (.get ^Map reg key)]
      (if (nil? entry)
        (p/rejected (ex/error :type :not-found
                              :code :method-not-found
                              :hint "No method found for the current request."))
        (let [f (.key ^MapEntry entry)
              m (.val ^MapEntry entry)
              d (p/deferred)]

          (sp/execute (conj interceptors f)
                      (with-meta params m)
                      #(p/resolve! d %)
                      #(p/reject! d %))
          d)))))

(defn dispatcher?
  [v]
  (instance? IDispatcher v))

(defmacro defservice
  [sname {:keys [dispatch-by interceptors]}]
  `(defonce ~sname (Dispatcher. (HashMap.)
                                ~dispatch-by
                                ~interceptors)))
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

(defmacro defmethod
  [& args]
  (let [{:keys [key meta sym args body]} (parse-defmethod args)
        f `(fn ~args ~@body)]
    `(do
       (s/assert dispatcher? ~sym)
       (.add ~sym ~key ~f ~meta)
       ~sym)))

(def spec-interceptor
  "An interceptor that conforms the request with the user provided
  spec."
  {:enter (fn [{:keys [request] :as data}]
            (let [{:keys [spec]} (meta request)]
              (if-let [spec (s/get-spec spec)]
                (let [result (s/conform spec request)]
                  (if (not= result ::s/invalid)
                    (assoc data :request result)
                    (let [data (s/explain-data spec request)]
                      (ex/raise :type :validation
                                :code :spec-validation
                                :explain (with-out-str
                                           (expound/printer data))
                                :data (::s/problems data)))))
                data)))})

(def wrap-errors
  {:error
   (fn [data]
     (let [error (:error data)
           mdata (meta (:request data))]
       (assoc data :error (ex/error :type :service-error
                                    :name (:spec mdata)
                                    :cause error))))})


