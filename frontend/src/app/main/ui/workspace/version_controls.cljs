;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.workspace.version-controls
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.time :as ct]
   [app.main.data.workspace.versions :as dwv]
   [app.main.store :as st]
   [app.main.ui.ds.notifications.actionable :refer [actionable*]]
   [app.util.i18n :refer [tr]]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def ^:private ref:controls
  (l/derived dwv/get-version-controls st/state))

(defn- get-snapshot-label
  "Keep the same names as the History sidebar, not internal snapshot labels."
  [snapshot]
  (cond
    (dwv/repair-snapshot? snapshot)
    (tr "workspace.versions.repair.version"
        (ct/format-inst (:created-at snapshot) :localized-date))

    (= "system" (:created-by snapshot))
    (tr "workspace.versions.autosaved.version"
        (ct/format-inst (:created-at snapshot) :localized-date))

    :else
    (or (:label snapshot)
        (tr "workspace.versions.preview.unnamed"))))

(mf/defc version-controls*
  [{:keys [file-id]}]
  (let [{:keys [phase opening snapshot-id snapshot token]} (mf/deref ref:controls)
        on-exit    (mf/use-fn (mf/deps token) #(st/emit! (dwv/exit-preview token)))
        on-restore (mf/use-fn (mf/deps snapshot-id token) #(st/emit! (dwv/enter-restore snapshot-id token)))
        on-cancel  (mf/use-fn (mf/deps token) #(st/emit! (dwv/cancel-restore token)))
        on-confirm (mf/use-fn (mf/deps token) #(st/emit! (dwv/confirm-restore token)))]
    (when (= file-id (:current-file-id opening))
      (case phase
        :preview
        [:> actionable* {:class (stl/css :version-controls)
                         :cancel-label (tr "labels.exit")
                         :accept-label (tr "labels.restore")
                         :on-cancel on-exit
                         :on-accept on-restore}
         (tr "workspace.versions.preview-banner-title" (get-snapshot-label snapshot))]

        :confirm-restore
        [:> actionable* {:class (stl/css :version-controls)
                         :cancel-label (tr "workspace.updates.dismiss")
                         :accept-label (tr "labels.restore")
                         :on-cancel on-cancel
                         :on-accept on-confirm}
         (tr "workspace.versions.restore-warning")]

        :restoring
        [:> actionable* {:class (stl/css :version-controls)}
         (tr "workspace.versions.restore-warning")]

        nil))))
