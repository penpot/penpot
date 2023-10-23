;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.common
  "A general purpose events."
  (:require
   [app.common.types.components-list :as ctkl]
   [app.config :as cf]
   [app.main.data.messages :as msg]
   [app.main.data.modal :as modal]
   [app.main.features :as features]
   [app.main.repo :as rp]
   [app.util.i18n :refer [tr]]
   [beicon.core :as rx]
   [potok.core :as ptk]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SHARE LINK
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn share-link-created
  [link]
  (ptk/reify ::share-link-created
    ptk/UpdateEvent
    (update [_ state]
      (update state :share-links (fnil conj []) link))))

(defn create-share-link
  [params]
  (ptk/reify ::create-share-link
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! :create-share-link params)
           (rx/map share-link-created)))))

(defn delete-share-link
  [{:keys [id] :as link}]
  (ptk/reify ::delete-share-link
    ptk/UpdateEvent
    (update [_ state]
      (update state :share-links
              (fn [links]
                (filterv #(not= id (:id %)) links))))

    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! :delete-share-link {:id id})
           (rx/ignore)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; NOTIFICATIONS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn force-reload!
  []
  (.reload js/location))

(defn handle-notification
  [{:keys [message code level] :as params}]
  (ptk/reify ::show-notification
    ptk/WatchEvent
    (watch [_ _ _]
      (case code
        :upgrade-version
        (when (or (not= (:version params) (:full cf/version))
                  (true? (:force params)))
          (rx/of (msg/dialog
                  :content (tr "notifications.by-code.upgrade-version")
                  :controls :inline-actions
                  :type level
                  :actions [{:label "Refresh" :callback force-reload!}]
                  :tag :notification)))

        (rx/of (msg/dialog
                :content message
                :controls :close
                :type level
                :tag :notification))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SHARED LIBRARY
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn show-shared-dialog
  [file-id add-shared]
  (ptk/reify ::show-shared-dialog
    ptk/WatchEvent
    (watch [_ state _]
      (let [features (features/get-team-enabled-features state)
            data     (:workspace-data state)
            file     (:workspace-file state)]
        (->> (if (and data file)
               (rx/of {:name             (:name file)
                       :components-count (count (ctkl/components-seq data))
                       :graphics-count   (count (:media data))
                       :colors-count     (count (:colors data))
                       :typography-count (count (:typographies data))})
               (rp/cmd! :get-file-summary {:id file-id :features features}))
             (rx/map (fn [summary]
                       (let [count (+ (:components-count summary)
                                      (:graphics-count summary)
                                      (:colors-count summary)
                                      (:typography-count summary))]
                         (modal/show
                          {:type :confirm
                           :message ""
                           :title (tr "modals.add-shared-confirm.message" (:name summary))
                           :hint (if (zero? count) (tr "modals.add-shared-confirm-empty.hint") (tr "modals.add-shared-confirm.hint"))
                           :cancel-label (if (zero? count) (tr "labels.cancel") :omit)
                           :accept-label (tr "modals.add-shared-confirm.accept")
                           :accept-style :primary
                           :on-accept add-shared})))))))))
