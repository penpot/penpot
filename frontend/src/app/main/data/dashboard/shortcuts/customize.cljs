;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.dashboard.shortcuts.customize
  (:require
   [app.main.data.event :as ev]
   [app.main.data.profile :as du]
   [app.main.data.shortcuts :as ds]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(defn set-custom-shortcut
  [shortcut-key new-command conflicting-key group-key]
  (ptk/reify ::set-custom-shortcut
    ptk/WatchEvent
    (watch [_ state _]
      (let [current-customs (get-in state [:profile :props :custom-shortcuts] {})
            group-map       (get current-customs group-key)
            group-map       (if (map? group-map) group-map {})
            group-map       (assoc group-map shortcut-key new-command)
            ;; clear conflicting shortcut in the same group
            group-map       (if conflicting-key
                              (assoc group-map conflicting-key "")
                              group-map)
            new-customs      (assoc current-customs group-key group-map)]
        (rx/of
         (ev/event {::ev/name "set-custom-shortcut"})
         (du/update-profile-props {:custom-shortcuts new-customs})
         (ds/rebind-shortcuts))))))


(defn reset-custom-shortcut
  [shortcut-key default-command group-key]

  (ptk/reify ::reset-custom-shortcut
    ptk/WatchEvent
    (watch [_ state _]
      (let [current-customs (get-in state [:profile :props :custom-shortcuts] {})
            group-map       (get current-customs group-key)
            group-map       (if (map? group-map) group-map {})

            ;; Find any action that is currently using the default command as its custom shortcut
            owner-key       (when (and default-command (not= default-command ""))
                              (some (fn [[k v]]
                                      (when (if (vector? default-command)
                                              (some #{v} default-command)
                                              (= v default-command))
                                        k))
                                    group-map))

            ;; Remove the shortcut being reset
            group-map       (dissoc group-map shortcut-key)

            ;; Disable the owner that was using the default command
            group-map       (if (and owner-key (not= owner-key shortcut-key))
                              (assoc group-map owner-key "")
                              group-map)

            new-customs      (if (empty? group-map)
                               (dissoc current-customs group-key)
                               (assoc current-customs group-key group-map))]
        (rx/of
         (ev/event {::ev/name "reset-custom-shortcut"})
         (du/update-profile-props {:custom-shortcuts new-customs})
         (ds/rebind-shortcuts))))))

(defn reset-all-custom-shortcuts
  []
  (ptk/reify ::reset-all-custom-shortcuts
    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (ev/event {::ev/name "reset-all-custom-shortcuts"})
             (du/update-profile-props {:custom-shortcuts {}})
             (ds/rebind-shortcuts)))))
