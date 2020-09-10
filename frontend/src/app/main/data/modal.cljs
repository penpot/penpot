;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.data.modal
  (:require
   [potok.core :as ptk]))

(defn show-modal [id type props]
  (ptk/reify ::show-modal
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc ::modal {:id id
                         :type type
                         :props props
                         :allow-click-outside false})))))

(defn hide-modal []
  (ptk/reify ::hide-modal
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (dissoc ::modal)))))

(defn update-modal [options]
  (ptk/reify ::update-modal
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (update ::modal merge options)))))

