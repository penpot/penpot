;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.plugins
  (:require
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as pcb]
   [app.common.time :as ct]
   [app.main.data.changes :as dch]
   [app.main.data.event :as ev]
   [app.main.data.modal :as modal]
   [app.main.data.notifications :as ntf]
   [app.main.store :as st]
   [app.plugins.register :as preg]
   [app.util.globals :as ug]
   [app.util.http :as http]
   [app.util.i18n :as i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(defn save-plugin-permissions-peek
  [id permissions]
  (ptk/reify ::save-plugin-permissions-peek
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:plugins-permissions-peek :data id] permissions))))

(defn fetch-manifest
  [plugin-url]
  (->> (http/send! {:method :get
                    :uri plugin-url
                    :omit-default-headers true
                    :response-type :json})
       (rx/map :body)
       (rx/map #(preg/parse-manifest plugin-url %))))

(defn save-current-plugin
  [id]
  (ptk/reify ::save-current-plugin
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:workspace-local :open-plugins] (fnil conj #{}) id))))

(defn remove-current-plugin
  [id]
  (ptk/reify ::remove-current-plugin
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:workspace-local :open-plugins] (fnil disj #{}) id))))

(defn- load-plugin!
  [{:keys [plugin-id name description host code icon permissions]}]
  (try
    (st/emit! (save-current-plugin plugin-id))
    (.ɵloadPlugin
     ^js ug/global
     #js {:pluginId plugin-id
          :name name
          :description description
          :host host
          :code code
          :icon icon
          :permissions (apply array permissions)}
     (fn []
       (st/emit! (remove-current-plugin plugin-id))))

    (catch :default e
      (st/emit! (remove-current-plugin plugin-id))
      (.error js/console "Error" e))))

(defn open-plugin!
  [{:keys [url] :as manifest} user-can-edit?]
  (if url
    ;; If the saved manifest has a URL we fetch the manifest to check
    ;; for updates
    (->> (fetch-manifest url)
         (rx/subs!
          (fn [new-manifest]
            (let [new-manifest       (merge new-manifest (select-keys manifest [:plugin-id]))
                  permissions        (:permissions new-manifest)
                  is-edition-plugin? (or (contains? permissions "content:write")
                                         (contains? permissions "library:write"))]
              (st/emit! (save-plugin-permissions-peek (:plugin-id new-manifest) permissions))
              (cond
                (and is-edition-plugin? (not user-can-edit?))
                (st/emit! (ntf/warn (tr "workspace.plugins.error.need-editor")))
                (not= (:permissions new-manifest) (:permissions manifest))
                (modal/show!
                 :plugin-permissions-update
                 {:plugin new-manifest
                  :on-accept
                  #(do
                     (preg/install-plugin! new-manifest)
                     (load-plugin! new-manifest))})

                (not= new-manifest manifest)
                (do (preg/install-plugin! new-manifest)
                    (load-plugin! manifest))
                :else
                (load-plugin! manifest))))
          (fn []
            ;; Error fetching the manifest we'll load the plugin with the
            ;; old manifest
            (load-plugin! manifest))))
    (load-plugin! manifest)))

(defn close-plugin!
  [{:keys [plugin-id]}]
  (try
    (.ɵunloadPlugin ^js ug/global plugin-id)
    (catch :default e
      (.error js/console "Error" e))))

(defn close-current-plugin
  [& {:keys [close-only-edition-plugins?]}]
  (ptk/reify ::close-current-plugin
    ptk/EffectEvent
    (effect [_ state _]
      (let [ids (dm/get-in state [:workspace-local :open-plugins])]
        (doseq [id ids]
          (let [plugin             (preg/get-plugin id)
                permissions        (or (dm/get-in state [:plugins-permissions-peek :data id])
                                       (:permissions plugin))
                is-edition-plugin? (or (contains? permissions "content:write")
                                       (contains? permissions "library:write"))]

            (when (or (not close-only-edition-plugins?)
                      is-edition-plugin?)
              (close-plugin! plugin))))))))

(defn delay-open-plugin
  [plugin]
  (ptk/reify ::delay-open-plugin
    ptk/UpdateEvent
    (update [_ state]
      (assoc state ::open-plugin (:plugin-id plugin)))))

(defn check-open-plugin
  []
  (ptk/reify ::check-open-plugin
    ptk/WatchEvent
    (watch [_ state _]
      (let [user-can-edit? (dm/get-in state [:permissions :can-edit])]
        (when-let [pid (::open-plugin state)]
          (let [plugin (preg/get-plugin pid)]
            (open-plugin! plugin user-can-edit?)
            (rx/of (ev/event {::ev/name "start-plugin"
                              ::ev/origin "workspace"
                              :name (:name plugin)
                              :host (:host plugin)})
                   #(dissoc % ::open-plugin))))))))

(defn- update-plugin-permissions-peek
  [{:keys [plugin-id url]}]
  (when url
      ;; If the saved manifest has a URL we fetch the manifest to check
      ;; for updates
    (->> (fetch-manifest url)
         (rx/subs!
          (fn [new-manifest]
            (let [permissions  (:permissions new-manifest)]
              (when permissions
                (st/emit! (save-plugin-permissions-peek plugin-id permissions)))))
          (fn [_err]
            ;; on error do nothing
            )))))

(defn update-plugins-permissions-peek
  []
  (ptk/reify ::update-plugins-permissions-peek
    ptk/UpdateEvent
    (update [_ state]
      (let [now        (ct/now)
            expiration (ct/in-past {:days 1})
            updated-at (dm/get-in state [:plugins-permissions-peek :updated-at] 0)
            expired?   (> expiration updated-at)]

        (if expired?
          (let [plugins (preg/plugins-list)]
            (doseq [plugin plugins]
              (update-plugin-permissions-peek plugin))
            (-> state
                (assoc-in [:plugins-permissions-peek :updated-at] now)))

          state)))))

(defn set-plugin-data
  ([file-id type namespace key value]
   (set-plugin-data file-id type nil nil namespace key value))

  ([file-id type id namespace key value]
   (set-plugin-data file-id type id nil namespace key value))

  ([file-id type id page-id namespace key value]
   (dm/assert! (contains? #{:file :page :shape :color :typography :component} type))
   (dm/assert! (or (nil? id) (uuid? id)))
   (dm/assert! (or (nil? page-id) (uuid? page-id)))
   (dm/assert! (uuid? file-id))
   (dm/assert! (keyword? namespace))
   (dm/assert! (string? key))
   (dm/assert! (or (nil? value) (string? value)))

   (ptk/reify ::set-file-plugin-data
     ptk/WatchEvent
     (watch [it state _]
       (let [file-data (dm/get-in state [:files file-id :data])
             changes   (-> (pcb/empty-changes it)
                           (pcb/with-file-data file-data)
                           (assoc :file-id file-id)
                           (pcb/set-plugin-data type id page-id namespace key value))]
         (rx/of (dch/commit-changes changes)))))))
