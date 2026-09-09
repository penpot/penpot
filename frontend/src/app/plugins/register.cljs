;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.plugins.register
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.schema :as sm]
   [app.common.types.plugins :as ctp]
   [app.common.uri :as u]
   [app.common.uuid :as uuid]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.util.object :as obj]
   [beicon.v2.core :as rx]
   [promesa.core :as p]))

;; Needs to be here because moving it to `app.main.data.workspace.mcp` will
;; cause a circular dependency
(def mcp-plugin-id "96dfa740-005d-8020-8007-55ede24a2bae")

;; Promise that resolves when plugins runtime is initialized.
;; Lives here to avoid circular dependency: workspace.mcp -> app.plugins -> app.plugins.api -> workspace
(defonce ^:private runtime-ready-promise (p/deferred))

(defn wait-for-runtime
  "Returns a promise that resolves when plugins runtime is initialized."
  []
  runtime-ready-promise)

(defn signal-runtime-ready
  "Signals that plugins runtime has been initialized. Called by app.plugins/init-plugins-runtime."
  []
  (when (p/pending? runtime-ready-promise)
    (p/resolve runtime-ready-promise true)))

;; Stores the installed plugins information
(defonce ^:private registry (atom {}))

(defn plugins-list
  "Retrieves the plugin data as an ordered list of plugin elements"
  []
  (->> (:ids @registry)
       (mapv #(dm/get-in @registry [:data %]))))

(defn get-plugin
  [id]
  (dm/get-in @registry [:data id]))

(defn parse-manifest
  "Read the manifest.json defined by the plugins definition and transforms it into an
  object that will be stored in the register."
  [plugin-url ^js manifest]
  (let [name (obj/get manifest "name")
        desc (obj/get manifest "description")
        code (obj/get manifest "code")
        icon (obj/get manifest "icon")
        vers (d/nilv (obj/get manifest "version") 1)

        permissions (into #{} (obj/get manifest "permissions" []))
        permissions
        (cond-> permissions
          (contains? permissions "content:write")
          (conj "content:read")

          (contains? permissions "library:write")
          (conj "library:read")

          (contains? permissions "comment:write")
          (conj "comment:read")

          (contains? permissions "clipboard:write")
          (conj "clipboard:read"))

        plugin-url
        (u/uri plugin-url)

        origin
        (if (= vers 1)
          (-> plugin-url
              (assoc :path "")
              (str))
          (-> plugin-url
              (u/join ".")
              (str)))

        prev-plugin
        (->> (:data @registry)
             (vals)
             (d/seek (fn [plugin]
                       (and (= name (:name plugin))
                            (= origin (:host plugin))))))

        plugin-id
        (d/nilv (:plugin-id prev-plugin) (str (uuid/next)))

        manifest
        (d/without-nils
         {:plugin-id plugin-id
          :url (str plugin-url)
          :version vers
          :name name
          :description desc
          :host origin
          :code code
          :icon icon
          :permissions (into #{} (map str) permissions)})]
    (if (sm/validate ctp/schema:registry-entry manifest)
      manifest
      (.error js/console (clj->js (sm/explain ctp/schema:registry-entry manifest))))))

(defn load-from-store
  []
  (reset! registry (get-in @st/state [:profile :props :plugins] {})))

(defn init
  []
  (load-from-store))

(declare remove-plugin!)

;; Tracks plugin ids with a persist request in flight, so rapid repeated
;; install/remove clicks on the same plugin cannot stack RPC writes.
(defonce ^:private in-flight (atom #{}))

(defonce ^:private in-flight-listeners (atom #{}))

(defn subscribe-in-flight!
  "Subscribes f, called with the in-flight id set on every change.
  Calls f immediately with the current set. Returns f."
  [f]
  (swap! in-flight-listeners conj f)
  (f @in-flight)
  f)

(defn unsubscribe-in-flight!
  [f]
  (swap! in-flight-listeners disj f)
  nil)

(defn- notify-in-flight!
  []
  (let [ids @in-flight]
    (doseq [f @in-flight-listeners]
      (f ids))))

(defn- track!
  [plugin-id]
  (swap! in-flight conj plugin-id)
  (notify-in-flight!))

(defn- release!
  [plugin-id]
  (swap! in-flight disj plugin-id)
  (notify-in-flight!))

(defn- validation-error?
  [err]
  (= :validation (:type (ex-data err))))

(defn- drop-local!
  [{:keys [plugin-id]}]
  (swap! registry #(-> %
                       (update :ids (fn [ids] (vec (remove (partial = plugin-id) ids))))
                       (update :data dissoc plugin-id))))

(defn- insert-at
  [ids idx id]
  (let [v   (vec ids)
        idx (max 0 (min idx (count v)))]
    (vec (concat (subvec v 0 idx) [id] (subvec v idx)))))

(defn install-plugin!
  [plugin]
  (let [plugin-id (:plugin-id plugin)
        previous  (get-plugin plugin-id)
        prev-idx  (.indexOf (vec (:ids @registry)) plugin-id)]
    (when-not (contains? @in-flight plugin-id)
      (track! plugin-id)
      (letfn [(update-ids [ids]
                (conj
                 (->> ids (remove #(= % (:plugin-id plugin))))
                 (:plugin-id plugin)))]
        (swap! registry #(-> %
                             (update :ids update-ids)
                             (update :data assoc (:plugin-id plugin) plugin)))
        (->> (rp/cmd! :add-profile-plugin {:plugin plugin})
             (rx/subs! (fn [_]
                         (release! plugin-id))
                       (fn [err]
                         (release! plugin-id)
                         (if (validation-error? err)
                           ;; The server kept the previous version (if any)
                           ;; in its original position: drop the optimistic
                           ;; entry and restore both position and data.
                           (if previous
                             (swap! registry #(-> %
                                                  (update :ids (fn [ids]
                                                                 (insert-at (remove (partial = plugin-id) ids)
                                                                            prev-idx
                                                                            plugin-id)))
                                                  (assoc-in [:data plugin-id] previous)))
                             (drop-local! plugin))
                           ;; One-shot compensating write with terminal
                           ;; callbacks: never re-arms tracking or rollback.
                           (do
                             (drop-local! plugin)
                             (->> (rp/cmd! :remove-profile-plugin {:plugin-id plugin-id})
                                  (rx/subs! (fn [_] nil)
                                            (fn [err2]
                                              (.error js/console "Rollback remove failed:" err2))))))
                         (.error js/console "Failed to install plugin:" err))))))))

(defn remove-plugin!
  [{:keys [plugin-id]}]
  (let [stored   (get-plugin plugin-id)
        prev-idx (.indexOf (vec (:ids @registry)) plugin-id)]
    (when-not (contains? @in-flight plugin-id)
      (track! plugin-id)
      (letfn [(update-ids [ids]
                (->> ids
                     (remove #(= % plugin-id))))]
        (swap! registry #(-> %
                             (update :ids update-ids)
                             (update :data dissoc plugin-id)))
        (->> (rp/cmd! :remove-profile-plugin {:plugin-id plugin-id})
             (rx/subs! (fn [_]
                         (release! plugin-id))
                       (fn [err]
                         (release! plugin-id)
                         (when stored
                           ;; Restore at the original position; the server
                           ;; still holds the entry on validation errors.
                           (swap! registry #(-> %
                                                (update :ids insert-at prev-idx plugin-id)
                                                (update :data assoc plugin-id stored)))
                           ;; One-shot compensating write with terminal
                           ;; callbacks on any other failure.
                           (when-not (validation-error? err)
                             (->> (rp/cmd! :add-profile-plugin {:plugin stored})
                                  (rx/subs! (fn [_] nil)
                                            (fn [err2]
                                              (.error js/console "Rollback install failed:" err2))))))
                         (.error js/console "Failed to remove plugin:" err))))))))

(defn check-permission
  [plugin-id permission]
  (or (= plugin-id "00000000-0000-0000-0000-000000000000")
      (= plugin-id mcp-plugin-id)
      (let [{:keys [permissions]} (dm/get-in @registry [:data plugin-id])]
        (contains? permissions permission))))

(defn get-plugin-data
  [state plugin-id]
  (get-in state [:profile :props :plugins :data plugin-id]))
