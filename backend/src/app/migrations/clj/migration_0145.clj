;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.migrations.clj.migration-0145
  "Migrate plugins references on profiles"
  (:require
   [app.common.data :as d]
   [app.common.logging :as l]
   [app.db :as db]
   [cuerdas.core :as str]))

(def ^:private replacements
  {"https://colors-to-tokens-plugin.pages.dev"
   "https://colors-to-tokens.plugins.penpot.app"

   "https://contrast-penpot-plugin.pages.dev"
   "https://contrast.plugins.penpot.app"

   "https://create-palette-penpot-plugin.pages.dev"
   "https://create-palette.plugins.penpot.app"

   "https://icons-penpot-plugin.pages.dev"
   "https://icons.plugins.penpot.app"

   "https://lorem-ipsum-penpot-plugin.pages.dev"
   "https://lorem-ipsum.plugins.penpot.app"

   "https://rename-layers-penpot-plugin.pages.dev"
   "https://rename-layers.plugins.penpot.app"

   "https://table-penpot-plugin.pages.dev"
   "https://table.plugins.penpot.app"})

(defn- fix-url
  [url]
  (reduce-kv (fn [url prefix replacement]
               (if (str/starts-with? url prefix)
                 (reduced (str replacement (subs url (count prefix))))
                 url))
             url
             replacements))


(defn- fix-manifest
  [manifest]
  (-> manifest
      (d/update-when :url fix-url)
      (d/update-when :host fix-url)))

(defn- fix-plugins-data
  [props]
  (d/update-in-when props [:plugins :data]
                    (fn [data]
                      (reduce-kv (fn [data id manifest]
                                   (let [manifest' (fix-manifest manifest)]
                                     (if (= manifest manifest')
                                       data
                                       (assoc data id manifest'))))
                                 data
                                 data))))

(def ^:private sql:get-profiles
  "SELECT id, props FROM profile
    WHERE props ?? '~:plugins'
    ORDER BY created_at")

(defn migrate
  [conn]
  (->> (db/plan conn [sql:get-profiles])
       (run! (fn [{:keys [id props]}]
               (when-let [props (some-> props db/decode-transit-pgobject)]
                 (let [props' (fix-plugins-data props)]
                   (when (not= props props')
                     (l/inf :hint "fixing plugins data on profile props" :profile-id (str id))
                     (db/update! conn :profile
                                 {:props (db/tjson props')}
                                 {:id id}
                                 {::db/return-keys false}))))))))

