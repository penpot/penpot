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

(def max-code-length
  "Maximum length for the plugin code (or code url) entry."
  (* 64 1024))

(def max-registry-entries
  "Maximum number of plugins that can be registered on a profile."
  100)

(def schema:registry-entry
  [:map
   [:plugin-id [:string {:max 250}]]
   [:version {:optional true} :int]
   [:name [:string {:max 250}]]
   [:description {:optional true} [:string {:max 1000}]]
   [:host [:string {:max 2048}]]
   [:code [:string {:max max-code-length}]]
   [:icon {:optional true} [:string {:max 2048}]]
   [:permissions schema:permissions]])

(def schema:plugin-registry
  [:map
   [:ids [:vector {:max max-registry-entries} :string]]
   [:data
    [:map-of {:gen/max 5 :max max-registry-entries}
     :string
     schema:registry-entry]]])
