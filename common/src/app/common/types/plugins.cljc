;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.plugins
  (:require
   [app.common.schema :as sm]
   [app.common.schema.generators :as sg]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SCHEMAS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private schema:string
  [:schema {:gen/gen (sg/word-string)} :string])

(def ^:private schema:keyword
  [:schema {:gen/gen (->> (sg/word-string)
                          (sg/fmap keyword))}
   :keyword])

(def schema:plugin-data
  (sm/register!
   ^{::sm/type ::plugin-data}
   [:map-of {:gen/max 5}
    schema:keyword
    [:map-of {:gen/max 5}
     schema:string
     schema:string]]))

(def ^:private schema:registry-entry
  [:map
   [:plugin-id :string]
   [:name :string]
   [:description {:optional true} :string]
   [:host :string]
   [:code :string]
   [:icon {:optional true} :string]
   [:permissions [:set :string]]])

(def schema:plugin-registry
  [:map
   [:ids [:vector :string]]
   [:data
    [:map-of {:gen/max 5}
     :string
     schema:registry-entry]]])

(sm/register! ::plugin-registry schema:plugin-registry)
(sm/register! ::registry-entry schema:registry-entry)
