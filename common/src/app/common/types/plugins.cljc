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

(def schema:registry-entry
  [:map
   [:plugin-id :string]
   [:version {:optional true} :int]
   [:name [:string {:max 500}]]
   [:description {:optional true} [:string {:max 4096}]]
   [:host [:string {:max 500}]]
   [:code [:string {:max 1048576}]] ;; 1 MiB chars; byte budget enforced by profile-props-max-size
   [:icon {:optional true} [:string {:max 262144}]] ;; 256 KiB chars; see above
   [:permissions schema:permissions]])

(def schema:plugin-registry
  [:map
   [:ids [:vector {:max max-plugins} :string]]
   [:data
    [:map-of {:gen/max 5 :max max-plugins}
     :string
     schema:registry-entry]]])
