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
   [buddy.core.codecs :as bc]
   [buddy.core.hash :as bh]
   [yetti.response :as-alias yres]))

(def
  ^{:dynamic true
    :doc "Runtime flag for enable/disable conditional processing of RPC methods."}
  *enabled* false)

(defn- encode
  [s]
  (-> s
      (bh/blake2b-256)
      (bc/bytes->b64-str true)))

(defn- fmt-key
  [s]
  (str "W/\"" (encode s) "\""))

(defn wrap
  [_ f {:keys [::get-object ::key-fn ::reuse-key?] :or {reuse-key? true} :as mdata}]
  (if (and (ifn? get-object) (ifn? key-fn))
    (do
      (l/trc :hint "instrumenting method" :service (::sv/name mdata))
      (fn [cfg {:keys [::key] :as params}]
        (if *enabled*
          (let [object (when (some? key)
                         (get-object cfg params))
                key'   (when (some? object)
                         (->> object (key-fn params) (fmt-key)))]
            (if (and (some? key) (= key key'))
              (fn [_] {::yres/status 304})
              (let [params (if (some? object)
                             (assoc params ::object object)
                             params)
                    result (f cfg params)
                    etag   (or (and reuse-key? key')
                               (some->> result meta ::key fmt-key)
                               (some->> result (key-fn params) fmt-key))]
                (rph/with-header result "etag" etag))))
          (f cfg params))))
    f))
