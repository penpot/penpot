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

;; Shortcuts definitions
(def shortcuts
  {:toggle-theme    {:tooltip (ds/alt "M")
                     :command (ds/a-mod "m")
                     :subsections [:general-dashboard]
                     :fn #(st/emit! (with-meta (du/toggle-theme)
                                      {::ev/origin "dashboard:shortcuts"}))}})

(def shortcuts-sidebar-navigation
  {:go-to-drafts       {:tooltip "G D"
                        :command "g d"
                        :subsections [:navigation-dashboard]
                        :fn #(st/emit! (dcm/go-to-dashboard-files :project-id :default))}

   :go-to-libs         {:tooltip "G L"
                        :command "g l"
                        :subsections [:navigation-dashboard]
                        :fn #(st/emit! (dcm/go-to-dashboard-libraries))}})

(def shortcut-search
  {:go-to-search       {:tooltip (ds/meta "F")
                        :command (ds/c-mod "f")
                        :subsections [:navigation-dashboard]
                        :fn #(st/emit! (dcm/go-to-dashboard-search))}})

(def shortcut-create-new-project
  {:create-new-project {:tooltip "+"
                        :command "+"
                        :subsections [:general-dashboard]
                        :fn #(st/emit! (dd/create-element))}})

;; Shortcuts combinations for files, drafts, libraries and fonts sections
(def shortcuts-dashboard
  (merge shortcuts
         shortcuts-sidebar-navigation))

(def shortcuts-projects
  (merge shortcuts
         shortcuts-sidebar-navigation
         shortcut-search
         shortcut-create-new-project))

(def shortcuts-drafts-libraries
  (merge shortcuts
         shortcuts-sidebar-navigation
         shortcut-search))

(defn get-tooltip [shortcut]
  (assert (contains? shortcuts shortcut) (str shortcut))
  (get-in shortcuts [shortcut :tooltip]))
