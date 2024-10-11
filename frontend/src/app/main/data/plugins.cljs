;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.plugins
  (:require
   [app.common.data.macros :as dm]
   [app.main.data.modal :as modal]
   [app.main.store :as st]
   [app.plugins.register :as preg]
   [app.util.globals :as ug]
   [app.util.http :as http]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

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
  [{:keys [url] :as manifest}]
  (if url
    ;; If the saved manifest has a URL we fetch the manifest to check
    ;; for updates
    (->> (fetch-manifest url)
         (rx/subs!
          (fn [new-manifest]
            (let [new-manifest (merge new-manifest (select-keys manifest [:plugin-id]))]
              (cond
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
  []
  (ptk/reify ::close-current-plugin
    ptk/EffectEvent
    (effect [_ state _]
      (let [ids (dm/get-in state [:workspace-local :open-plugins])]
        (doseq [id ids]
          (close-plugin! (preg/get-plugin id)))))))

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
      (when-let [pid (::open-plugin state)]
        (open-plugin! (preg/get-plugin pid))
        (rx/of #(dissoc % ::open-plugin))))))
