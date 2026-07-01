;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.shortcuts.customize
  (:require
   [app.main.data.profile :as du]
   [app.main.data.shortcuts :as ds]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(defn set-custom-shortcut
  [shortcut-key new-command conflicting-key]
  (ptk/reify ::set-custom-shortcut
    ptk/WatchEvent
    (watch [_ state _]
      (let [current-customs (get-in state [:profile :props :custom-shortcuts] {})
            new-customs     (-> current-customs
                                (assoc shortcut-key new-command)
                                (cond-> conflicting-key (assoc conflicting-key "")))]
        (rx/of (du/update-profile-props {:custom-shortcuts new-customs})
               (ds/rebind-shortcuts))))))

(defn reset-custom-shortcut
  [shortcut-key]
  (ptk/reify ::reset-custom-shortcut
    ptk/WatchEvent
    (watch [_ state _]
      (let [current-customs (get-in state [:profile :props :custom-shortcuts] {})
            new-customs     (or (not-empty (dissoc current-customs shortcut-key)) {})]
        (rx/of (du/update-profile-props {:custom-shortcuts new-customs})
               (ds/rebind-shortcuts))))))

(defn reset-all-custom-shortcuts
  []
  (ptk/reify ::reset-all-custom-shortcuts
    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (du/update-profile-props {:custom-shortcuts {}})
             (ds/rebind-shortcuts)))))
