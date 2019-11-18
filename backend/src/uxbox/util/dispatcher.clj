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
   java.util.Map
   java.util.List
   java.util.Map$Entry
   java.util.HashMap))

(definterface IDispatcher
  (^void add [key f metadata]))

(deftype Dispatcher [reg attr interceptors]
  IDispatcher
  (add [this key f metadata]
    (.put ^Map reg key (Map/entry f metadata))
    nil)

  clojure.lang.IFn
  (invoke [_ params]
    (let [key (get params attr)
          entry (.get ^Map reg key)]
      (if (nil? entry)
        (p/rejected (ex/error :type :not-found
                              :code :method-not-found
                              :hint "No method found for the current request."))
        (let [f (.getKey ^Map$Entry entry)
              m (.getValue ^Map$Entry entry)
              d (p/deferred)]

          (sp/execute (conj interceptors f)
                      (with-meta params m)
                      #(p/resolve! d %)
                      #(p/reject! d %))
          d)))))

(defn dispatcher?
  [v]
  (instance? Dispatcher v))

(defmacro defservice
  [sname {:keys [dispatch-by interceptors]}]
  `(defonce ~sname (Dispatcher. (HashMap.)
                                ~dispatch-by
                                ~interceptors)))

(defmacro defmethod
  [sname key metadata args & rest]
  (s/assert symbol? sname)
  (s/assert keyword? key)
  (s/assert map? metadata)
  (s/assert vector? args)
  (let [f `(fn ~args ~@rest)]
    `(do
       (s/assert dispatcher? ~sname)
       (.add ~sname ~key ~f ~metadata)
       ~sname)))

(def spec-interceptor
  "An interceptor that conforms the request with the user provided
  spec."
  {:enter (fn [{:keys [request] :as data}]
            (let [{:keys [spec]} (meta request)]
              (if spec
                (let [result (s/conform spec request)]
                  (if (not= result ::s/invalid)
                    (assoc data :request result)
                    (let [data (s/explain-data spec request)]
                      (ex/raise :type :validation
                                :code :spec-validation
                                :explain (with-out-str
                                           (expound/printer data))
                                :data data))))
                data)))})
