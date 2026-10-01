;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.types.plugins
  (:require
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
  [:map-of {:gen/max 5 :title "PluginsData"}
   schema:keyword
   [:map-of {:gen/max 5}
    schema:string
    schema:string]])

(def valid-permissions
  "Set of valid plugin permissions that can be granted to plugins."
  #{"content:read" "content:write"
    "library:read" "library:write"
    "comment:read" "comment:write"
    "clipboard:read" "clipboard:write"
    "user:read"
    "allow:downloads"
    "allow:localstorage"})

(def schema:permissions
  "Schema for plugin permissions - a set of valid permission strings."
  [:set {:gen/max 11} (into [:enum] (sort valid-permissions))])

(def max-plugins
  "Maximum number of plugins a profile can hold."
  50)

(def registry-entry-max-lengths
  "Maximum length (in chars) of the bounded registry entry strings.
  `:code` and `:icon` hold manifest paths, not content."
  {:name 500
   :description 4096
   :host 500
   :code 500
   :icon 500})

(def schema:registry-entry
  [:map
   [:plugin-id :string]
   [:version {:optional true} :int]
   [:name [:string {:max (:name registry-entry-max-lengths)}]]
   [:description {:optional true} [:string {:max (:description registry-entry-max-lengths)}]]
   [:host [:string {:max (:host registry-entry-max-lengths)}]]
   [:code [:string {:max (:code registry-entry-max-lengths)}]]
   [:icon {:optional true} [:string {:max (:icon registry-entry-max-lengths)}]]
   [:permissions schema:permissions]])

(def schema:plugin-registry
  [:map
   [:ids [:vector {:max max-plugins} :string]]
   [:data
    [:map-of {:gen/max 5 :max max-plugins}
     :string
     schema:registry-entry]]])
