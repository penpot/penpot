;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.dashboard.import
  (:require
   [app.main.ui.components.file-uploader :refer [file-uploader]]
   [app.main.worker :as uw]
   [app.util.dom :as dom]
   [app.util.logging :as log]
   [beicon.core :as rx]
   [rumext.alpha :as mf]))

(log/set-level! :debug)

(defn use-import-file
  [project-id on-finish-import]
  (mf/use-callback
   (mf/deps project-id on-finish-import)
   (fn [files]
     (when files
       (let [files (->> files (mapv dom/create-uri))]
         (->> (uw/ask-many!
               {:cmd :import-file
                :project-id project-id
                :files files})

              (rx/subs
               (fn [result]
                 (log/debug :action "import-result" :result result))

               (fn [err]
                 (log/debug :action "import-error" :result err))

               (fn []
                 (log/debug :action "import-end")
                 (when on-finish-import (on-finish-import))))))))))

(mf/defc import-form
  {::mf/forward-ref true}
  [{:keys [project-id on-finish-import]} external-ref]

  (let [on-file-selected (use-import-file project-id on-finish-import)]
    [:form.import-file
     [:& file-uploader {:accept ".penpot"
                        :multi true
                        :ref external-ref
                        :on-selected on-file-selected}]]))




