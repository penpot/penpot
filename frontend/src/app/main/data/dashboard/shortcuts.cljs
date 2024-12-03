;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.dashboard.shortcuts
  (:require
   [app.main.data.common :as dcm]
   [app.main.data.dashboard :as dd]
   [app.main.data.event :as ev]
   [app.main.data.profile :as du]
   [app.main.data.shortcuts :as ds]
   [app.main.store :as st]))

(def shortcuts
  {:go-to-search       {:tooltip (ds/meta "F")
                        :command (ds/c-mod "f")
                        :subsections [:navigation-dashboard]
                        :fn #(st/emit! (dcm/go-to-dashboard-search))}

   :go-to-drafts       {:tooltip "G D"
                        :command "g d"
                        :subsections [:navigation-dashboard]
                        :fn #(st/emit! (dcm/go-to-dashboard-files :project-id :default))}

   :go-to-libs         {:tooltip "G L"
                        :command "g l"
                        :subsections [:navigation-dashboard]
                        :fn #(st/emit! (dcm/go-to-dashboard-libraries))}

   :create-new-project {:tooltip "+"
                        :command "+"
                        :subsections [:general-dashboard]
                        :fn #(st/emit! (dd/create-element))}

   :toggle-theme    {:tooltip (ds/alt "M")
                     :command (ds/a-mod "m")
                     :subsections [:general-dashboard]
                     :fn #(st/emit! (with-meta (du/toggle-theme)
                                      {::ev/origin "dashboard:shortcuts"}))}})



(defn get-tooltip [shortcut]
  (assert (contains? shortcuts shortcut) (str shortcut))
  (get-in shortcuts [shortcut :tooltip]))
