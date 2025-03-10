;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.renderer
  "Common renderer interface."
  (:require
   [app.common.spec :as us]
   [app.renderer.bitmap :as rb]
   [app.renderer.pdf :as rp]
   [app.renderer.svg :as rs]
   [cljs.spec.alpha :as s]))

(s/def ::name ::us/string)
(s/def ::suffix ::us/string)
(s/def ::type #{:png :jpeg :webp :pdf :svg})
(s/def ::page-id ::us/uuid)
(s/def ::file-id ::us/uuid)
(s/def ::share-id ::us/uuid)
(s/def ::scale ::us/number)
(s/def ::token ::us/string)
(s/def ::filename ::us/string)

(s/def ::object
  (s/keys :req-un [::id ::name ::suffix ::filename]
          :opt-un [::share-id]))

(s/def ::objects
  (s/coll-of ::object :min-count 1))

(s/def ::render-params
  (s/keys :req-un [::file-id ::page-id ::scale ::token ::type ::objects]))

(defn render
  [{:keys [type] :as params} on-object]
  (us/verify ::render-params params)
  (us/verify fn? on-object)
  (case type
    :png  (rb/render params on-object)
    :jpeg (rb/render params on-object)
    :webp (rb/render params on-object)
    :pdf  (rp/render params on-object)
    :svg  (rs/render params on-object)))

