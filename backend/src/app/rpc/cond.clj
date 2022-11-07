;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.cond
  "Conditional loading middleware.

  A middleware consists mainly on wrapping a RPC method with
  conditional logic. It expects to to have some metadata set on the RPC
  method that will enable this middleware to retrieve the necessary data
  for process the conditional logic:

  - `::get-object` => should be a function that retrieves the minimum version
    of the object that will be used for calculate the KEY (etags in terms of
    the HTTP protocol).
  - `::key-fn` a function used to generate a string representation
    of the object. This function can be applied to the object returned by the
    `get-object` but also to the RPC return value (in case you don't provide
    the return value calculated key under `::key` metadata prop.
  - `::reuse-key?` enables reusing the key calculated on first time; usefull
    when the target object is not retrieved on the RPC (typical on retrieving
    dependent objects).
  "
  (:require
   [app.common.logging :as l]
   [app.rpc.helpers :as rph]
   [app.util.services :as-alias sv]
   [promesa.core :as p]
   [promesa.exec :as px]
   [yetti.response :as yrs]))

(def
  ^{:dynamic true
    :doc "Runtime flag for enable/disable conditional processing of RPC methods."}
  *enabled* false)

(defn- fmt-key
  [s]
  (when s
    (str "W/\"" s "\"")))

(defn wrap
  [{:keys [executor]} f {:keys [::get-object ::key-fn ::reuse-key?] :as mdata}]
  (if (and (ifn? get-object) (ifn? key-fn))
    (do
      (l/debug :hint "instrumenting method" :service (::sv/name mdata))
      (fn [cfg {:keys [::key] :as params}]
        (if *enabled*
          (->> (if (or key reuse-key?)
                 (->> (px/submit! executor (partial get-object cfg params))
                      (p/map key-fn)
                      (p/map fmt-key))
                 (p/resolved nil))
               (p/mapcat (fn [key']
                           (if (and (some? key)
                                    (= key key'))
                             (p/resolved (fn [_] (yrs/response 304)))
                             (->> (f cfg params)
                                  (p/map (fn [result]
                                           (->> (or (and reuse-key? key')
                                                    (-> result meta ::key fmt-key)
                                                    (-> result key-fn fmt-key))
                                                (rph/with-header result "etag")))))))))
          (f cfg params))))
    f))
